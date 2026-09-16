package com.example.autoscrollapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoScrollService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isScrolling = false
    private var isActive = false
    private var lastProgressValue = 0f

    private val checkProgressRunnable = object : Runnable {
        override fun run() {
            if (isActive) {
                checkVideoProgress()
                handler.postDelayed(this, 1000) // Cek setiap 1 detik
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoScrollService", "Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: ""
        if (packageName == "com.google.android.youtube") {
            if (!isActive) {
                isActive = true
                lastProgressValue = 0f
                handler.postDelayed(checkProgressRunnable, 1000)
            }
        } else {
            if (isActive) {
                isActive = false
                handler.removeCallbacks(checkProgressRunnable)
            }
        }
    }

    private fun checkVideoProgress() {
        val rootNode = rootInActiveWindow ?: return
        val seekBarNode = findSeekBarNode(rootNode)

        if (seekBarNode != null) {
            val rangeInfo = seekBarNode.rangeInfo
            if (rangeInfo != null) {
                val currentProgress = rangeInfo.current
                val maxProgress = rangeInfo.max

                Log.d("AutoScrollService", "Progress: $currentProgress / $maxProgress")

                // Deteksi ketika video selesai dan mengulang (looping)
                // Jika progress saat ini jauh lebih kecil dari progress sebelumnya (misal dari 50 kembali ke 0)
                if (lastProgressValue > 0 && currentProgress < lastProgressValue && currentProgress < (maxProgress * 0.2f)) {
                    Log.d("AutoScrollService", "Video looped! Scrolling to next short.")
                    lastProgressValue = 0f
                    performScroll()
                } else {
                    lastProgressValue = currentProgress
                }
            } else {
                // Alternatif: baca dari contentDescription jika rangeInfo tidak tersedia
                val desc = seekBarNode.contentDescription?.toString()
                Log.d("AutoScrollService", "SeekBar desc: $desc")
                // Parsing teks durasi dari sini jika formatnya "X menit Y detik dari Z menit..."
            }
        }
    }

    private fun findSeekBarNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className == "android.widget.SeekBar") {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findSeekBarNode(child)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    override fun onInterrupt() {
        isActive = false
        handler.removeCallbacks(checkProgressRunnable)
    }

    private fun performScroll() {
        if (isScrolling) return
        isScrolling = true

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        val startX = screenWidth / 2f
        val startY = screenHeight * 0.8f
        val endY = screenHeight * 0.2f

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(startX, endY)

        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0, 500)
        gestureBuilder.addStroke(stroke)

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                handler.postDelayed({ isScrolling = false }, 2000) // Tunggu 2 detik sebelum bisa scroll lagi
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                isScrolling = false
            }
        }, null)
    }
}
