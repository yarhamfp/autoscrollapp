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
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        })

        // Status check
        val isEnabled = isAccessibilityServiceEnabled()
        layout.addView(TextView(this).apply {
            text = if (isEnabled) "✅ Service AKTIF" else "❌ Service NONAKTIF"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(if (isEnabled) Color.parseColor("#4CAF50") else Color.RED)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        })

        // Button nyala/mati
        layout.addView(Button(this).apply {
            text = if (isEnabled) "⚙️ Matikan Service" else "⚙️ Nyalakan Service"
            textSize = 16f
            setBackgroundColor(if (isEnabled) Color.parseColor("#FF5722") else Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setPadding(40, 30, 40, 30)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        // Spacer
        layout.addView(TextView(this).apply {
            text = ""
            setPadding(0, 40, 0, 0)
        })

        // Cara pakai
        layout.addView(TextView(this).apply {
            text = "📖 Cara Pakai"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 20)
        })

        layout.addView(TextView(this).apply {
            text = """1️⃣ Tekan tombol di atas untuk membuka Pengaturan Aksesibilitas

2️⃣ Cari "NextShort" → Nyalakan toggle

3️⃣ Buka YouTube → Masuk ke Shorts

4️⃣ Anda akan melihat notifikasi di atas layar yang menunjukkan status:
   • "YouTube terdeteksi ✅" = Sudah siap
   • "Progress: XX%" = Sedang memantau video
   • "Auto Scroll! ⬆️" = Pindah ke video baru

5️⃣ Untuk MEMATIKAN, kembali ke sini dan tekan tombol di atas

💡 Tips: Pastikan notifikasi dari app ini tidak diblokir di pengaturan HP Anda"""
            textSize = 15f
            setPadding(0, 0, 0, 40)
            setLineSpacing(8f, 1f)
        })

        // Notification permission button (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            layout.addView(Button(this).apply {
                text = "🔔 Izinkan Notifikasi"
                textSize = 14f
                setBackgroundColor(Color.parseColor("#2196F3"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
                }
            })
        }

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    private var isFirstResume = true

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
        } else {
            // Refresh status ketika kembali dari pengaturan
            recreate()
        }
    }
}
