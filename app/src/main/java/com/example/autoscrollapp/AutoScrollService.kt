package com.example.autoscrollapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
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
    private var isMonitoring = false   
    private var lastProgressPercent = -1f
    private var progressStableCount = 0
    private var lastScrollTime = 0L

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                tryAutoDetect()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()
        showNotification("NextShort Siap ✅", "Tombol mengambang sudah tampil di layar")
        createFloatingOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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

        val statusText = TextView(this).apply {
            id = View.generateViewId()
            text = "NextShort"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            tag = "status"
        }

        val toggleBtn = makeOverlayButton("▶", "#4CAF50").apply {
            tag = "toggle"
            setOnClickListener {
                isMonitoring = !isMonitoring
                if (isMonitoring) {
                    (this as TextView).text = "⏸"
                    this.background = makeButtonBg("#FF5722")
                    statusText.text = "Aktif"
                    statusText.setTextColor(Color.parseColor("#4CAF50"))
                    showNotification("▶️ Monitoring AKTIF", "Mencoba deteksi garis merah...")
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
                    false 
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(overlayView, params)
                    Math.abs(event.rawX - initialTouchX) > 20 || Math.abs(event.rawY - initialTouchY) > 20
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

    // ==================== AUTO DETECTION (Lebar Garis Merah) ====================
    private fun tryAutoDetect() {
        val root = rootInActiveWindow ?: return

        val allNodes = mutableListOf<NodeData>()
        collectAllNodes(root, allNodes, 0)

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // STRATEGI KHUSUS: Cari View yang sangat tipis (tinggi < 30px) di layar bagian bawah (>70% tinggi layar)
        // Ini adalah cara paling ampuh mendeteksi GARIS MERAH YouTube Shorts
        val thinViews = allNodes.filter { 
            it.height > 0 && it.height < 30 && it.bottomY > (screenH * 0.7)
        }

        if (thinViews.isNotEmpty()) {
            for (v in thinViews) {
                // Konversi lebar view menjadi persen layar
                val percent = (v.width.toFloat() / screenW.toFloat()) * 100f
                if (percent in 1f..100f) {
                    
                    // Deteksi loop: Jika lebarnya tadi >80% dari layar, lalu anjlok jadi <20%, artinya kembali ke awal!
                    if (lastProgressPercent >= 80f && percent < 20f) {
                        Log.d(TAG, "🔄 Garis Merah Reset! (${lastProgressPercent.toInt()}% -> ${percent.toInt()}%)")
                        lastProgressPercent = percent
                        showNotification("▶️ Garis Merah Reset", "Mendeteksi loop video!")
                        performScroll()
                        return
                    }
                    
                    if (percent > lastProgressPercent || percent < lastProgressPercent) {
                        lastProgressPercent = percent
                        updateStatusText("📏 ${percent.toInt()}%")
                        showNotification("▶️ Membaca Garis Merah", "Lebar: ${percent.toInt()}%")
                    }
                }
            }
            return
        }

        updateStatusText("Mencari...")
    }

    private fun updateStatusText(text: String) {
        overlayView?.let { container ->
            container.findViewWithTag<TextView>("status")?.let {
                handler.post { it.text = text }
            }
        }
    }

    data class NodeData(
        val className: String,
        val viewId: String,
        val width: Int,
        val height: Int,
        val bottomY: Int,
        val rangeInfo: AccessibilityNodeInfo.RangeInfo?
    )

    private fun collectAllNodes(node: AccessibilityNodeInfo, list: MutableList<NodeData>, depth: Int) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        list.add(NodeData(
            className = node.className?.toString()?.substringAfterLast('.') ?: "?",
            viewId = node.viewIdResourceName?.substringAfterLast('/') ?: "",
            width = rect.width(),
            height = rect.height(),
            bottomY = rect.bottom,
            rangeInfo = node.rangeInfo
        ))
        if (depth < 15) {
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) collectAllNodes(child, list, depth + 1)
            }
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
                    showNotification("▶️ Monitoring AKTIF", "Menunggu garis merah...")
                }, 1500)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                isScrolling = false
                updateStatusText("Aktif")
            }
        }, null)
    }

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
