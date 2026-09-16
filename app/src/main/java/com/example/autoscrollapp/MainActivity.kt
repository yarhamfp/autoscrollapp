package com.example.autoscrollapp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }

        val titleText = TextView(this).apply {
            text = "Auto Scroll YouTube Shorts"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 50)
        }

        val descText = TextView(this).apply {
            text = "Aplikasi ini akan melakukan scroll otomatis setiap 15 detik HANYA saat aplikasi YouTube sedang dibuka.\n\nUntuk menyalakan/mematikan, klik tombol di bawah ini lalu cari 'Auto Scroll App' di menu Aksesibilitas dan ubah toggle-nya."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 100)
        }

        val button = Button(this).apply {
            text = "Buka Pengaturan (Nyala/Mati)"
            setBackgroundColor(Color.parseColor("#FF0000"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        layout.addView(titleText)
        layout.addView(descText)
        layout.addView(button)

        setContentView(layout)
    }
}
