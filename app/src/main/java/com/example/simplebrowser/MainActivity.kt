package com.example.simplebrowser

import android.Manifest
import com.example.simplebrowser.files.FloatAppService
import com.example.simplebrowser.files.FloatApps
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                afterPerm?.invoke()
            } else {
                Toast.makeText(this, "Permission nahi mili, floating nahi chalega", Toast.LENGTH_LONG).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Notification allow ho ya na ho, floating chalane ki koshish karo
            ensureOverlayThenStart()
        }

    // permission milne ke baad jo floating chalana hai (browser ya file manager)
    private var afterPerm: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnBrowser).setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        // File Manager / Money / Routine: har ek ke saath "Float" toggle.
        // ON = floating window, OFF = normal poori screen. Choice yaad rahti hai.
        setupApp(
            R.id.btnFiles, R.id.swFiles, FloatApps.FILES,
            com.example.simplebrowser.files.HomeActivity::class.java
        )
        setupApp(
            R.id.btnMoney, R.id.swMoney, FloatApps.MONEY,
            com.example.simplebrowser.money.MoneyActivity::class.java
        )
        setupApp(
            R.id.btnRoutine, R.id.swRoutine, FloatApps.ROUTINE,
            com.example.simplebrowser.routine.RoutineActivity::class.java
        )
        findViewById<Button>(R.id.btnFloating).setOnClickListener { runFloating { startFloating() } }
        findViewById<Button>(R.id.btnStopFloating).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Floating band", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupApp(btnId: Int, swId: Int, app: String, normal: Class<*>) {
        val prefs = getSharedPreferences("MainPrefs", MODE_PRIVATE)
        val key = "float_$app"
        val sw = findViewById<SwitchCompat>(swId)
        sw.isChecked = prefs.getBoolean(key, true)
        sw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        findViewById<Button>(btnId).setOnClickListener {
            try {
                // File Manager ko server login chahiye. Login nahi hai to seedha Login screen kholo
                // (floating window se Login kholne par window band ho jaati thi aur kuch dikhta nahi tha).
                if (app == FloatApps.FILES) {
                    com.example.simplebrowser.files.Session.load(this)
                    if (!com.example.simplebrowser.files.Session.hasCreds()) {
                        Toast.makeText(this, "Pehle server login karo", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, com.example.simplebrowser.files.LoginActivity::class.java))
                        return@setOnClickListener
                    }
                }
                if (sw.isChecked) runFloating { startFloatingApp(app) }
                else startActivity(Intent(this, normal))
            } catch (e: Exception) {
                Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runFloating(action: () -> Unit) {
        afterPerm = action
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ensureOverlayThenStart()
        }
    }

    private fun ensureOverlayThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            afterPerm?.invoke()
        }
    }

    private fun startFloatingApp(app: String) {
        try {
            FloatAppService.start(this, app)
            finish() // main menu band, floating window dikhe (Main menu wapas floating window ke ⋮ se)
        } catch (e: Exception) {
            Toast.makeText(this, "Service start error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startFloating() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, FloatingService::class.java))
            finish() // main screen band, seedha floating window dikhe
        } catch (e: Exception) {
            Toast.makeText(this, "Service start error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
}
