package com.example.autoscrollapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class AutoScrollService : AccessibilityService() {

    companion object {
        private const val TAG = "NextShort"
        private const val CHANNEL_ID = "nextshort_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isScrolling = false
    private var isMonitoring = false   // User sudah klik "Mulai"
    private var lastProgressPercent = -1f
    private var progressStableCount = 0
    private var lastScrollTime = 0L

    // Untuk draggable overlay
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Auto-detection polling
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                tryAutoDetect()
                handler.postDelayed(this, 1000)
            }
        }
    }

    // ==================== SERVICE LIFECYCLE ====================
    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()
        showNotification("NextShort Siap ✅", "Tombol mengambang sudah tampil di layar")
        createFloatingOverlay()
        Log.d(TAG, "Service connected, overlay created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Kita tidak lagi bergantung pada event untuk deteksi
        // Semua dikontrol via floating button
    }

    override fun onInterrupt() {
        isMonitoring = false
        handler.removeCallbacks(checkRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingOverlay()
        isMonitoring = false
        handler.removeCallbacks(checkRunnable)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    // ==================== FLOATING OVERLAY ====================
    private fun createFloatingOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E0222222"))
                cornerRadius = 30f
            }
        }

        // Status text (kecil)
        val statusText = TextView(this).apply {
            id = View.generateViewId()
            text = "NextShort"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            tag = "status"
        }

        // Tombol MULAI / STOP
        val toggleBtn = makeOverlayButton("▶", "#4CAF50").apply {
            tag = "toggle"
            setOnClickListener {
                isMonitoring = !isMonitoring
                if (isMonitoring) {
                    (this as TextView).text = "⏸"
                    this.background = makeButtonBg("#FF5722")
                    statusText.text = "Aktif"
                    statusText.setTextColor(Color.parseColor("#4CAF50"))
                    showNotification("▶️ Monitoring AKTIF", "Mencoba deteksi otomatis + manual skip tersedia")
                    handler.removeCallbacks(checkRunnable)
                    handler.postDelayed(checkRunnable, 500)
                } else {
                    (this as TextView).text = "▶"
                    this.background = makeButtonBg("#4CAF50")
                    statusText.text = "NextShort"
                    statusText.setTextColor(Color.WHITE)
                    showNotification("⏸ Monitoring DIJEDA", "Tekan ▶ untuk melanjutkan")
                    handler.removeCallbacks(checkRunnable)
                }
            }
        }

        // Tombol SKIP (scroll ke video berikutnya)
        val skipBtn = makeOverlayButton("⏭", "#2196F3").apply {
            setOnClickListener {
                performScroll()
            }
        }

        container.addView(statusText)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
        }
        btnRow.addView(toggleBtn, LinearLayout.LayoutParams(120, 120).apply { setMargins(4, 4, 4, 4) })
        btnRow.addView(skipBtn, LinearLayout.LayoutParams(120, 120).apply { setMargins(4, 4, 4, 4) })
        container.addView(btnRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 300
        }

        // Draggable
        container.setOnTouchListener(createDragListener(params))

        windowManager?.addView(container, params)
        overlayView = container
    }

    private fun makeOverlayButton(label: String, bgColor: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = makeButtonBg(bgColor)
        }
    }

    private fun makeButtonBg(color: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = 60f
        }
    }

    private fun createDragListener(params: WindowManager.LayoutParams): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false // Jangan consume agar onClick tetap jalan
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(overlayView, params)
                    // Consume hanya jika sudah geser cukup jauh
                    val moved = Math.abs(event.rawX - initialTouchX) > 20 || Math.abs(event.rawY - initialTouchY) > 20
                    moved
                }
                else -> false
            }
        }
    }

    private fun removeFloatingOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    // ==================== AUTO DETECTION (Background) ====================
    private fun tryAutoDetect() {
        val root = rootInActiveWindow ?: return

        // Kumpulkan semua node
        val allNodes = mutableListOf<NodeData>()
        collectAllNodes(root, allNodes, 0)

        // Cari node dengan RangeInfo
        for (nd in allNodes) {
            val range = nd.rangeInfo ?: continue
            if (range.max <= 0) continue

            val percent = (range.current / range.max) * 100f
            Log.d(TAG, "Progress: ${percent.toInt()}% [${nd.className}] id=${nd.viewId}")

            updateStatusText("${percent.toInt()}%")
            showNotification("▶️ Progress: ${percent.toInt()}%", "Auto-detection aktif | ${nd.viewId}")

            // Deteksi loop
            if (lastProgressPercent >= 80f && percent < 20f) {
                lastProgressPercent = percent
                performScroll()
                return
            }
            if (percent >= 95f) {
                progressStableCount++
                if (progressStableCount >= 4) {
                    progressStableCount = 0
                    performScroll()
                    return
                }
            } else {
                progressStableCount = 0
            }
            lastProgressPercent = percent
            return
        }

        // Cari teks waktu
        val timePattern = Regex("\\d+:\\d{2}")
        for (nd in allNodes) {
            val combined = "${nd.text} ${nd.contentDesc}"
            val matches = timePattern.findAll(combined).toList()
            if (matches.size >= 2) {
                val current = parseTime(matches[0].value)
                val total = parseTime(matches[1].value)
                if (total > 0) {
                    val percent = (current.toFloat() / total.toFloat()) * 100f
                    updateStatusText("⏱${current}s")
                    showNotification("⏱ $current / $total detik", "Time detection aktif")

                    if (lastProgressPercent >= 80f && percent < 20f) {
                        lastProgressPercent = percent
                        performScroll()
                        return
                    }
                    lastProgressPercent = percent
                    return
                }
            }
        }

        // Tidak ada yang terdeteksi — tetap coba, user bisa manual skip
        updateStatusText("Aktif")
    }

    private fun updateStatusText(text: String) {
        overlayView?.let { container ->
            container.findViewWithTag<TextView>("status")?.let {
                handler.post { it.text = text }
            }
        }
    }

    // ==================== DATA COLLECTION ====================
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
            text = node.text?.toString()?.take(60) ?: "",
            contentDesc = node.contentDescription?.toString()?.take(60) ?: "",
            rangeInfo = node.rangeInfo
        ))
        if (depth < 15) {
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) collectAllNodes(child, list, depth + 1)
            }
        }
    }

    private fun parseTime(t: String): Int {
        val p = t.split(":")
        return when (p.size) {
            2 -> (p[0].toIntOrNull() ?: 0) * 60 + (p[1].toIntOrNull() ?: 0)
            else -> 0
        }
    }

    // ==================== SCROLL ====================
    private fun performScroll() {
        if (isScrolling) return
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < 2000) return
        lastScrollTime = now
        isScrolling = true

        showNotification("⬆️ Scroll!", "Pindah ke video berikutnya...")
        updateStatusText("⬆️")

        val dm = resources.displayMetrics
        val path = Path()
        path.moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.75f)
        path.lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.25f)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                handler.postDelayed({
                    isScrolling = false
                    lastProgressPercent = -1f
                    progressStableCount = 0
                    updateStatusText("Aktif")
                    showNotification("▶️ Monitoring AKTIF", "Menunggu video selesai...")
                }, 1500)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                isScrolling = false
                updateStatusText("Aktif")
            }
        }, null)
    }

    // ==================== NOTIFICATION ====================
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "NextShort", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Status NextShort"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
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
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }
}
