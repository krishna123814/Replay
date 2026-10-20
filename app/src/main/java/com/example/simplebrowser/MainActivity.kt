package com.example.simplebrowser

import android.Manifest
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
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                startFloating()
            } else {
                Toast.makeText(this, "Permission nahi mili, floating nahi chalega", Toast.LENGTH_LONG).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Notification allow ho ya na ho, floating chalane ki koshish karo
            ensureOverlayThenStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnBrowser).setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<Button>(R.id.btnFiles).setOnClickListener {
            startActivity(Intent(this, com.example.simplebrowser.files.HomeActivity::class.java))
        }
        findViewById<Button>(R.id.btnMoney).setOnClickListener {
            startActivity(Intent(this, com.example.simplebrowser.money.MoneyActivity::class.java))
        }
        findViewById<Button>(R.id.btnRoutine).setOnClickListener {
            startActivity(Intent(this, com.example.simplebrowser.routine.RoutineActivity::class.java))
        }
        findViewById<Button>(R.id.btnFloating).setOnClickListener { onFloatingClicked() }
        findViewById<Button>(R.id.btnStopFloating).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Floating band", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onFloatingClicked() {
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
            startFloating()
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
