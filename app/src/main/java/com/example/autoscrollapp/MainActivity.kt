package com.example.autoscrollapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var isFirstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(60, 80, 60, 80)
        }

        // Title
        layout.addView(TextView(this).apply {
            text = "⏭️ NextShort"
            textSize = 32f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        })

        layout.addView(TextView(this).apply {
            text = "Auto Scroll YouTube Shorts"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 40)
        })

        // Status
        val isEnabled = isAccessibilityServiceEnabled()
        layout.addView(TextView(this).apply {
            text = if (isEnabled) "✅ Service AKTIF" else "❌ Service NONAKTIF"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(if (isEnabled) Color.parseColor("#4CAF50") else Color.RED)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        })

        // Tombol Aksesibilitas
        layout.addView(makeButton(
            if (isEnabled) "⚙️ Matikan Service" else "⚙️ Nyalakan Service",
            if (isEnabled) "#FF5722" else "#4CAF50"
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        addSpacer(layout, 20)

        // Tombol izin notifikasi (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            layout.addView(makeButton("🔔 Izinkan Notifikasi", "#2196F3") {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            })
            addSpacer(layout, 20)
        }

        // Cara pakai
        layout.addView(TextView(this).apply {
            text = "📖 Cara Pakai"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 40, 0, 20)
        })

        layout.addView(TextView(this).apply {
            text = """1️⃣ Nyalakan service di Pengaturan Aksesibilitas
   → Cari "NextShort" → Nyalakan toggle

2️⃣ Buka YouTube → Klik tab "Shorts" (bukan Home!)

3️⃣ Lihat notifikasi NextShort di status bar:
   • "SHORTS ✅" = Halaman Shorts terdeteksi
   • "Progress: XX%" = Sedang memantau video
   • "Auto Scroll! ⬆️" = Pindah ke video baru

4️⃣ Gunakan tombol ⏸/▶ di notifikasi untuk Jeda/Lanjut

5️⃣ Tidur dengan tenang 😴"""
            textSize = 15f
            setPadding(0, 0, 0, 40)
            setLineSpacing(10f, 1f)
        })

        layout.addView(TextView(this).apply {
            text = "ℹ️ Tentang"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 20)
        })

        layout.addView(TextView(this).apply {
            text = """NextShort mendeteksi progress bar video YouTube Shorts. Ketika video selesai (looping), NextShort otomatis scroll ke video berikutnya.

App ini gratis, tanpa iklan, tanpa langganan, dan tidak mengumpulkan data apapun. Privasi Anda 100% terjaga."""
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 40)
            setLineSpacing(6f, 1f)
        })

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun makeButton(text: String, color: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 16f
            setBackgroundColor(Color.parseColor(color))
            setTextColor(Color.WHITE)
            setPadding(40, 24, 40, 24)
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }
    }

    private fun addSpacer(layout: LinearLayout, height: Int) {
        layout.addView(TextView(this).apply { setPadding(0, height, 0, 0) })
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
        } else {
            recreate()
        }
    }
}
