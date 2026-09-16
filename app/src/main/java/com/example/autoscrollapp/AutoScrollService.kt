package com.example.autoscrollapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

class AutoScrollService : AccessibilityService() {

    companion object {
        private const val TAG = "NextShort"
        private const val CHANNEL_ID = "nextshort_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE = "com.example.autoscrollapp.TOGGLE"

        var isEnabled = true  // User bisa toggle via notifikasi
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isYouTubeActive = false
    private var isScrolling = false
    private var lastProgressPercent = -1f
    private var progressStableCount = 0
    private var lastScrollTime = 0L
    private var debugInfo = ""

    // Receiver untuk tombol Play/Stop di notifikasi
    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE) {
                isEnabled = !isEnabled
                if (isEnabled) {
                    showNotification("▶️ NextShort AKTIF", "Menunggu YouTube Shorts...")
                } else {
                    showNotification("⏸️ NextShort DIJEDA", "Tekan Play untuk melanjutkan")
                    handler.removeCallbacks(checkRunnable)
                }
            }
        }
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isYouTubeActive && isEnabled) {
                inspectScreen()
                handler.postDelayed(this, 800)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()
        showNotification("▶️ NextShort AKTIF", "Buka YouTube Shorts untuk mulai")

        val filter = IntentFilter(ACTION_TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, filter)
        }

        Log.d(TAG, "Service connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!isEnabled) return

        val pkg = event.packageName?.toString() ?: return

        if (pkg == "com.google.android.youtube") {
            if (!isYouTubeActive) {
                isYouTubeActive = true
                lastProgressPercent = -1f
                progressStableCount = 0
                showNotification("▶️ YouTube Terbuka", "Mencari video Shorts...")
                handler.removeCallbacks(checkRunnable)
                handler.postDelayed(checkRunnable, 500)
            }
        } else {
            if (isYouTubeActive) {
                isYouTubeActive = false
                handler.removeCallbacks(checkRunnable)
                showNotification("▶️ NextShort AKTIF", "YouTube tidak terbuka")
            }
        }
    }

    // ==================== INSPEKSI LAYAR ====================
    private fun inspectScreen() {
        if (!isEnabled) return

        val root = rootInActiveWindow
        if (root == null) {
            showNotification("⚠️ Tidak bisa membaca layar", "root = null")
            return
        }

        // Kumpulkan SEMUA node untuk analisis
        val allNodes = mutableListOf<NodeData>()
        collectAllNodes(root, allNodes, 0)

        // ====== STRATEGI 1: Cari node dengan RangeInfo ======
        val rangeNodes = allNodes.filter { it.rangeInfo != null }
        if (rangeNodes.isNotEmpty()) {
            for (rn in rangeNodes) {
                val range = rn.rangeInfo!!
                val max = range.max
                if (max <= 0) continue

                val percent = (range.current / max) * 100f
                Log.d(TAG, "📊 RangeInfo: ${range.current}/$max = ${percent.toInt()}% [${rn.className}] id=${rn.viewId}")
                showNotification("▶️ Shorts Diputar", "Progress: ${percent.toInt()}% | ${rn.className}")

                if (lastProgressPercent >= 80f && percent < 20f) {
                    Log.d(TAG, "🔄 Loop detected via RangeInfo!")
                    lastProgressPercent = percent
                    triggerScroll()
                    return
                }
                if (percent >= 95f) {
                    progressStableCount++
                    if (progressStableCount >= 4) {
                        Log.d(TAG, "⏹ Stuck at end via RangeInfo!")
                        progressStableCount = 0
                        triggerScroll()
                        return
                    }
                } else {
                    progressStableCount = 0
                }
                lastProgressPercent = percent
                return
            }
        }

        // ====== STRATEGI 2: Cari viewId mengandung kata kunci progress ======
        val progressKeywords = listOf("progress", "seek", "time_bar", "scrubber", "slider", "playback")
        val progressIdNodes = allNodes.filter { node ->
            val id = node.viewId.lowercase()
            progressKeywords.any { id.contains(it) }
        }
        if (progressIdNodes.isNotEmpty()) {
            val info = progressIdNodes.joinToString(", ") { "${it.viewId}[${it.className}]" }
            Log.d(TAG, "🔍 Found progress-related IDs: $info")
            showNotification("🔍 Progress ID ditemukan", info.take(80))

            // Cek rangeInfo pada node ini
            for (pn in progressIdNodes) {
                if (pn.rangeInfo != null) {
                    handleRangeDetection(pn.rangeInfo!!, pn.className)
                    return
                }
            }
        }

        // ====== STRATEGI 3: Cari teks waktu (0:15, 1:30, dll) ======
        val timePattern = Regex("\\d+:\\d{2}")
        val timeNodes = allNodes.filter { node ->
            val combined = "${node.text} ${node.contentDesc}"
            timePattern.containsMatchIn(combined)
        }
        if (timeNodes.isNotEmpty()) {
            val timeTexts = timeNodes.map { "${it.text}${it.contentDesc}".trim() }
            Log.d(TAG, "⏱ Found time texts: $timeTexts")
            showNotification("⏱️ Waktu terdeteksi", timeTexts.joinToString(" | ").take(80))

            // Coba parse pasangan waktu (current / total)
            for (tn in timeNodes) {
                val combined = "${tn.text} ${tn.contentDesc}"
                val matches = timePattern.findAll(combined).toList()
                if (matches.size >= 2) {
                    val current = parseTimeToSeconds(matches[0].value)
                    val total = parseTimeToSeconds(matches[1].value)
                    if (total > 0) {
                        val percent = (current.toFloat() / total.toFloat()) * 100f
                        showNotification("▶️ Shorts Diputar", "⏱ $current/$total detik (${percent.toInt()}%)")

                        if (lastProgressPercent >= 80f && percent < 20f) {
                            lastProgressPercent = percent
                            triggerScroll()
                            return
                        }
                        lastProgressPercent = percent
                        return
                    }
                }
            }
        }

        // ====== STRATEGI 4: Deteksi halaman Shorts ======
        val isShortsPage = detectShortsPage(allNodes)

        // ====== DEBUG: Ringkasan node tree ======
        val uniqueClasses = allNodes.map { it.className }.distinct().sorted()
        val totalNodes = allNodes.size
        val nodesWithText = allNodes.count { it.text.isNotEmpty() }
        val nodesWithDesc = allNodes.count { it.contentDesc.isNotEmpty() }
        val nodesWithId = allNodes.count { it.viewId.isNotEmpty() }
        val nodesWithRange = rangeNodes.size

        val shortStatus = if (isShortsPage) "SHORTS ✅" else "BUKAN SHORTS"
        debugInfo = "$shortStatus | Total:$totalNodes Text:$nodesWithText Desc:$nodesWithDesc Id:$nodesWithId Range:$nodesWithRange"

        showNotification("🔍 $shortStatus | Mencari...", debugInfo)

        // Log beberapa class names untuk debugging
        Log.d(TAG, "=== Node Tree Summary ===")
        Log.d(TAG, "Total nodes: $totalNodes")
        Log.d(TAG, "Classes: ${uniqueClasses.joinToString(", ")}")
        for (node in allNodes.take(50)) {
            if (node.viewId.isNotEmpty() || node.text.isNotEmpty() || node.contentDesc.isNotEmpty() || node.rangeInfo != null) {
                Log.d(TAG, "  [${node.className}] id=${node.viewId} text=\"${node.text}\" desc=\"${node.contentDesc}\" range=${node.rangeInfo}")
            }
        }
    }

    // ==================== DETEKSI HALAMAN SHORTS ====================
    private fun detectShortsPage(nodes: List<NodeData>): Boolean {
        // Shorts page biasanya punya tombol Like, Comment, Share yang tersusun vertikal
        // dan video fullscreen
        val shortsIndicators = listOf("like", "dislike", "comment", "share", "subscribe", "remix", "shorts")
        var matchCount = 0
        for (node in nodes) {
            val combined = "${node.text} ${node.contentDesc} ${node.viewId}".lowercase()
            for (indicator in shortsIndicators) {
                if (combined.contains(indicator)) {
                    matchCount++
                    break
                }
            }
        }
        // Jika ada 3+ indikator, kemungkinan besar ini halaman Shorts
        return matchCount >= 3
    }

    // ==================== HELPER: Kumpulkan semua node ====================
    data class NodeData(
        val className: String,
        val viewId: String,
        val text: String,
        val contentDesc: String,
        val rangeInfo: AccessibilityNodeInfo.RangeInfo?
    )

    private fun collectAllNodes(node: AccessibilityNodeInfo, list: MutableList<NodeData>, depth: Int) {
        list.add(NodeData(
            className = node.className?.toString()?.substringAfterLast('.') ?: "?",
            viewId = node.viewIdResourceName?.substringAfterLast('/') ?: "",
            text = node.text?.toString()?.take(50) ?: "",
            contentDesc = node.contentDescription?.toString()?.take(50) ?: "",
            rangeInfo = node.rangeInfo
        ))

        if (depth < 15) {
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    collectAllNodes(child, list, depth + 1)
                }
            }
        }
    }

    // ==================== HELPER: Handle range detection ====================
    private fun handleRangeDetection(range: AccessibilityNodeInfo.RangeInfo, className: String) {
        val max = range.max
        if (max <= 0) return

        val percent = (range.current / max) * 100f
        showNotification("▶️ Shorts Diputar", "Progress: ${percent.toInt()}% [$className]")

        if (lastProgressPercent >= 80f && percent < 20f) {
            lastProgressPercent = percent
            triggerScroll()
            return
        }
        if (percent >= 95f) {
            progressStableCount++
            if (progressStableCount >= 4) {
                progressStableCount = 0
                triggerScroll()
                return
            }
        } else {
            progressStableCount = 0
        }
        lastProgressPercent = percent
    }

    // ==================== HELPER: Parse time ====================
    private fun parseTimeToSeconds(time: String): Int {
        val parts = time.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    }

    // ==================== SCROLL ====================
    private fun triggerScroll() {
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < 3000) return
        lastScrollTime = now
        performScroll()
    }

    private fun performScroll() {
        if (isScrolling) return
        isScrolling = true

        showNotification("⬆️ Auto Scroll!", "Pindah ke video berikutnya...")
        Log.d(TAG, "🚀 Scrolling!")

        val dm = resources.displayMetrics
        val screenH = dm.heightPixels
        val screenW = dm.widthPixels

        val path = Path()
        path.moveTo(screenW / 2f, screenH * 0.75f)
        path.lineTo(screenW / 2f, screenH * 0.25f)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                handler.postDelayed({
                    isScrolling = false
                    lastProgressPercent = -1f
                    progressStableCount = 0
                    showNotification("▶️ Shorts Diputar", "Menunggu video selesai...")
                }, 2000)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                isScrolling = false
            }
        }, null)
    }

    // ==================== NOTIFICATION ====================
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "NextShort Status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Status auto scroll NextShort"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun showNotification(title: String, text: String) {
        val toggleIntent = Intent(ACTION_TOGGLE).apply {
            setPackage(packageName)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionIcon = if (isEnabled) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val actionText = if (isEnabled) "⏸ Jeda" else "▶ Lanjut"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(actionIcon, actionText, togglePendingIntent)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    // ==================== LIFECYCLE ====================
    override fun onInterrupt() {
        isYouTubeActive = false
        handler.removeCallbacks(checkRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(toggleReceiver) } catch (_: Exception) {}
        isYouTubeActive = false
        handler.removeCallbacks(checkRunnable)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }
}
