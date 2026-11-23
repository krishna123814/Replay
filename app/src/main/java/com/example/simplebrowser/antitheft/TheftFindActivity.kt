package com.example.simplebrowser.antitheft

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 🔎 "Mujhe lauta do" — Telegram `findme` command se khulta hai, lock screen ke upar bhi dikhta hai.
 * Band karne ka koi aasan button nahi (sirf phone wapas aane par app se band hota hai).
 */
class TheftFindActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFB00020.toInt())
            setPadding(60, 60, 60, 60)
        }
        fun tv(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 24)
        }
        root.addView(tv("🔎 Ye phone kho gaya hai", 26f, true))
        root.addView(tv("Agar aapko ye phone mila hai, meharbani karke is number par call ya WhatsApp karo:", 16f))
        root.addView(tv(TheftEngine.FIND_ME_NUMBER, 32f, true))
        val callBtn = Button(this).apply {
            text = "📞 Abhi call karo"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${TheftEngine.FIND_ME_NUMBER}")))
                } catch (_: Throwable) { }
            }
        }
        root.addView(callBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 40 })
        setContentView(root)
    }

    // Back/Home se band na ho — jab tak owner app se "lost mode band karo" na kare
    override fun onBackPressed() { /* ignore */ }
}
