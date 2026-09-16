package com.example.autoscrollapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

class AutoScrollService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoScroll"
        private const val CHANNEL_ID = "auto_scroll_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isScrolling = false
    private var isYouTubeActive = false
    private var lastProgressPercent = -1f
    private var progressStableCount = 0
    private var lastScrollTime = 0L

    // Polling: cek progress setiap 800ms
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isYouTubeActive) {
                inspectScreen()
                handler.postDelayed(this, 800)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ Service Connected!")
        createNotificationChannel()
        showNotification("Service aktif", "Buka YouTube Shorts untuk mulai auto-scroll")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        if (pkg == "com.google.android.youtube") {
            if (!isYouTubeActive) {
                isYouTubeActive = true
                lastProgressPercent = -1f
                progressStableCount = 0
                showNotification("YouTube terdeteksi ✅", "Menunggu video Shorts...")
                handler.removeCallbacks(checkRunnable)
                handler.postDelayed(checkRunnable, 1000)
            }
        } else {
            if (isYouTubeActive) {
                isYouTubeActive = false
                handler.removeCallbacks(checkRunnable)
                showNotification("Service aktif", "YouTube tidak terbuka")
            }
        }
    }

    private fun inspectScreen() {
        val root = rootInActiveWindow ?: return

        // Strategi 1: Cari node dengan RangeInfo (progress bar)
        val rangeNode = findNodeWithRangeInfo(root)
        if (rangeNode != null) {
            handleRangeNode(rangeNode)
            return
        }

        // Strategi 2: Cari node SeekBar atau ProgressBar
        val progressNode = findNodeByClass(root, listOf(
            "android.widget.SeekBar",
            "android.widget.ProgressBar"
        ))
        if (progressNode != null) {
            val range = progressNode.rangeInfo
            if (range != null) {
                handleRangeNode(progressNode)
                return
            }
        }

        // Strategi 3: Cari node yang contentDescription-nya mengandung pola waktu
        val timeNode = findNodeWithTimeDescription(root)
        if (timeNode != null) {
            handleTimeDescription(timeNode)
            return
        }

        // Strategi 4: Cari SEMUA node dan log class name-nya (untuk debug)
        // Hanya log sekali setiap 10 detik agar tidak spam
        if (System.currentTimeMillis() - lastScrollTime > 10000) {
            dumpNodeTree(root, 0)
        }
    }

    // ==================== STRATEGI 1: RangeInfo ====================
    private fun findNodeWithRangeInfo(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.rangeInfo != null) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeWithRangeInfo(child)
            if (found != null) return found
        }
        return null
    }

    private fun handleRangeNode(node: AccessibilityNodeInfo) {
        val range = node.rangeInfo ?: return
        val max = range.max
        if (max <= 0) return

        val percent = (range.current / max) * 100f
        Log.d(TAG, "📊 Progress: ${range.current}/${max} = ${percent.toInt()}%")
        showNotification("Shorts sedang diputar ▶️", "Progress: ${percent.toInt()}%")

        // Deteksi looping: progress turun drastis dari tinggi ke rendah
        if (lastProgressPercent >= 85f && percent < 15f) {
            Log.d(TAG, "🔄 Video looped! (${lastProgressPercent.toInt()}% → ${percent.toInt()}%)")
            lastProgressPercent = percent
            triggerScroll()
            return
        }

        // Deteksi stuck di akhir: progress di atas 95% selama beberapa kali pengecekan
        if (percent >= 95f) {
            progressStableCount++
            if (progressStableCount >= 4) { // ~3.2 detik stuck di akhir
                Log.d(TAG, "⏹️ Video stuck at end!")
                progressStableCount = 0
                triggerScroll()
                return
            }
        } else {
            progressStableCount = 0
        }

        lastProgressPercent = percent
    }

    // ==================== STRATEGI 2: Class Name ====================
    private fun findNodeByClass(node: AccessibilityNodeInfo, classNames: List<String>): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if (classNames.any { cls.contains(it, ignoreCase = true) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByClass(child, classNames)
            if (found != null) return found
        }
        return null
    }

    // ==================== STRATEGI 3: Time Description ====================
    private fun findNodeWithTimeDescription(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        // Cari pola waktu seperti "0:15 / 0:30", "0:15 of 0:30", "15 detik dari 30 detik"
        if (desc.matches(Regex(".*\\d+:\\d+.*\\d+:\\d+.*"))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeWithTimeDescription(child)
            if (found != null) return found
        }
        return null
    }

    private fun handleTimeDescription(node: AccessibilityNodeInfo) {
        val desc = node.contentDescription?.toString() ?: return
        Log.d(TAG, "⏱️ Time node found: $desc")
        showNotification("Shorts sedang diputar ▶️", "Time: $desc")

        // Coba parse "current / total" format
        val timePattern = Regex("(\\d+):(\\d+).*(\\d+):(\\d+)")
        val match = timePattern.find(desc) ?: return

        val currentMin = match.groupValues[1].toIntOrNull() ?: return
        val currentSec = match.groupValues[2].toIntOrNull() ?: return
        val totalMin = match.groupValues[3].toIntOrNull() ?: return
        val totalSec = match.groupValues[4].toIntOrNull() ?: return

        val currentTotal = currentMin * 60 + currentSec
        val totalTotal = totalMin * 60 + totalSec

        if (totalTotal > 0) {
            val percent = (currentTotal.toFloat() / totalTotal.toFloat()) * 100f

            if (lastProgressPercent >= 85f && percent < 15f) {
                lastProgressPercent = percent
                triggerScroll()
                return
            }
            lastProgressPercent = percent
        }
    }

    // ==================== DEBUG: Dump Node Tree ====================
    private fun dumpNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString() ?: "null"
        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val range = node.rangeInfo

        if (cls != "android.view.View" && cls != "android.widget.FrameLayout" && cls != "android.widget.LinearLayout") {
            Log.d(TAG, "${indent}[$cls] id=$viewId desc=\"$desc\" text=\"$text\" range=$range")
        }

        if (depth < 10) { // Batasi kedalaman
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                dumpNodeTree(child, depth + 1)
            }
        }
    }

    // ==================== SCROLL ====================
    private fun triggerScroll() {
        val now = System.currentTimeMillis()
        // Minimum 3 detik antara scroll
        if (now - lastScrollTime < 3000) return
        lastScrollTime = now
        performScroll()
    }

    private fun performScroll() {
        if (isScrolling) return
        isScrolling = true

        showNotification("Auto Scroll! ⬆️", "Pindah ke video berikutnya...")
        Log.d(TAG, "🚀 Performing scroll!")

        val dm = resources.displayMetrics
        val screenH = dm.heightPixels
        val screenW = dm.widthPixels

        val startX = screenW / 2f
        val startY = screenH * 0.75f
        val endY = screenH * 0.25f

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(startX, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "✅ Scroll completed!")
                handler.postDelayed({
                    isScrolling = false
                    lastProgressPercent = -1f
                    progressStableCount = 0
                    showNotification("Shorts sedang diputar ▶️", "Menunggu video selesai...")
                }, 2000)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "❌ Scroll cancelled")
                isScrolling = false
            }
        }, null)
    }

    // ==================== NOTIFICATION ====================
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NextShort Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Menampilkan status auto scroll"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun showNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun clearNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    // ==================== LIFECYCLE ====================
    override fun onInterrupt() {
        isYouTubeActive = false
        handler.removeCallbacks(checkRunnable)
        clearNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        isYouTubeActive = false
        handler.removeCallbacks(checkRunnable)
        clearNotification()
    }
}
