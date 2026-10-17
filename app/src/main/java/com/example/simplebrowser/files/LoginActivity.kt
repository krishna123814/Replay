package com.example.simplebrowser.files

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Session.hasCreds()) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        val pad = dp(24)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_VERTICAL
        col.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "📁 Lion Files"
        title.textSize = 28f
        title.setTextColor(Color.BLACK)

        val sub = TextView(this)
        sub.text = "Apne server (WebDAV) se connect karo"
        sub.setTextColor(Color.BLACK)
        sub.setPadding(0, dp(4), 0, dp(20))

        val url = EditText(this)
        url.hint = "Server URL"
        url.setSingleLine()
        url.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        url.setText(Session.baseUrl)

        val user = EditText(this)
        user.hint = "Username"
        user.setSingleLine()
        user.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        user.setText(Session.user)

        val pass = EditText(this)
        pass.hint = "Password"
        pass.setSingleLine()
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        val btn = Button(this)
        btn.text = "Connect"

        val status = TextView(this)
        status.setTextColor(0xFFC62828.toInt())
        status.setPadding(0, dp(12), 0, 0)

        col.addView(title)
        col.addView(sub)
        col.addView(url, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        col.addView(user, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        col.addView(pass, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        col.addView(btn, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        col.addView(status)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.WHITE)
        scroll.isFillViewport = true
        scroll.addView(col)
        setContentView(scroll)

        btn.setOnClickListener {
            var u = url.text.toString().trim()
            val name = user.text.toString().trim()
            val pw = pass.text.toString()
            if (u.isEmpty() || name.isEmpty() || pw.isEmpty()) {
                status.setTextColor(0xFFC62828.toInt())
                status.text = "Teeno cheezein bharo"
                return@setOnClickListener
            }
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            u = Dav.withSlash(u)

            btn.isEnabled = false
            status.setTextColor(Color.BLACK)
            status.text = "Connect ho raha hai…"
            Session.set(u, name, pw)

            Thread {
                try {
                    Dav.list(u)
                    runOnUiThread {
                        Session.persist(this)
                        startActivity(Intent(this, HomeActivity::class.java))
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Session.load(this)
                        btn.isEnabled = true
                        status.setTextColor(0xFFC62828.toInt())
                        status.text = "❌ " + (e.message ?: "connect nahi hua")
                    }
                }
            }.start()
        }
    }
}
