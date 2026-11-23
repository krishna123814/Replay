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
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import com.example.simplebrowser.deploy.AutoDeploy
import com.example.simplebrowser.deploy.GitDeploy
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
        showLastCrash()

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
        setupApp(
            R.id.btnIncome, R.id.swIncome, FloatApps.INCOME,
            com.example.simplebrowser.income.IncomeActivity::class.java
        )
        findViewById<Button>(R.id.btnFamily).setOnClickListener {
            startActivity(Intent(this, com.example.simplebrowser.family.FamilyActivity::class.java))
        }
        findViewById<Button>(R.id.btnTheft).setOnClickListener {
            startActivity(Intent(this, com.example.simplebrowser.antitheft.TheftActivity::class.java))
        }
        setupDeploy()
        setupGit()
        findViewById<Button>(R.id.btnFloating).setOnClickListener { runFloating { startFloating() } }
        findViewById<Button>(R.id.btnStopFloating).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Floating band", Toast.LENGTH_SHORT).show()
        }
    }

    // 📦 Auto Deploy: Download me server*.zip aate hi server ke drop folder me apne aap
    private val deployH = Handler(Looper.getMainLooper())
    private val deployTick = object : Runnable {
        override fun run() {
            refreshDeployStatus()
            refreshGitStatus()
            deployH.postDelayed(this, 2000)
        }
    }

    private fun refreshDeployStatus() {
        val tv = findViewById<TextView>(R.id.tvDeployStatus) ?: return
        tv.text = if (AutoDeploy.on(this)) AutoDeploy.last(this).ifBlank { "Chalu hai — Download me " + AutoDeploy.pattern(this) + " ka intezaar" }
        else "Band hai"
    }

    // Battery optimization Lion ko background me maar deta hai -> Auto Deploy tabhi chalta tha jab app khola jaye
    private fun askBatteryOnce() {
        if (com.example.simplebrowser.deploy.DeployWatchdog.ignoringBattery(this)) return
        Toast.makeText(this, "Battery me Lion ko \"Allow / Unrestricted\" karo, warna background me band ho jata hai", Toast.LENGTH_LONG).show()
        try { startActivity(com.example.simplebrowser.deploy.DeployWatchdog.batteryIntent(this)) } catch (_: Throwable) { }
    }

    private fun setupDeploy() {
        val sw = findViewById<SwitchCompat>(R.id.swDeploy)
        sw.isChecked = AutoDeploy.on(this)
        if (sw.isChecked) AutoDeploy.start(this)
        sw.setOnCheckedChangeListener { _, on ->
            if (on && !AutoDeploy.hasAllFiles()) {
                Toast.makeText(this, "\"All files access\" allow karo", Toast.LENGTH_LONG).show()
                AutoDeploy.askAllFiles(this)
            }
            AutoDeploy.setOn(this, on)
            if (on) askBatteryOnce()
            // OFF par service khud band ho jati hai (agar GitHub wala bhi OFF ho)
            if (on) AutoDeploy.start(this)
            refreshDeployStatus()
        }
        findViewById<Button>(R.id.btnDeploy).setOnClickListener {
            AutoDeploy.showSettings(this) { refreshDeployStatus() }
        }
        refreshDeployStatus()
    }

    // 🐙 Auto Deploy (GitHub): Download me SimpleBrowser*.zip aate hi GitHub repo me
    private fun refreshGitStatus() {
        val tv = findViewById<TextView>(R.id.tvGitStatus) ?: return
        tv.text = if (GitDeploy.on(this)) GitDeploy.last(this).ifBlank { "Chalu hai — Download me " + GitDeploy.pattern(this) + " ka intezaar" }
        else "Band hai"
    }

    private fun setupGit() {
        val sw = findViewById<SwitchCompat>(R.id.swGit)
        sw.isChecked = GitDeploy.on(this)
        if (sw.isChecked) AutoDeploy.start(this)
        sw.setOnCheckedChangeListener { _, on ->
            if (on && !AutoDeploy.hasAllFiles()) {
                Toast.makeText(this, "\"All files access\" allow karo", Toast.LENGTH_LONG).show()
                AutoDeploy.askAllFiles(this)
            }
            GitDeploy.setOn(this, on)
            if (on) askBatteryOnce()
            if (on) AutoDeploy.start(this)
            refreshGitStatus()
        }
        findViewById<Button>(R.id.btnGit).setOnClickListener {
            GitDeploy.showSettings(this) { refreshGitStatus() }
        }
        refreshGitStatus()
    }

    override fun onResume() {
        super.onResume()
        deployH.removeCallbacks(deployTick)
        deployH.post(deployTick)
    }

    override fun onPause() {
        deployH.removeCallbacks(deployTick)
        super.onPause()
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

    /** Pichli baar crash hua tha to uska error dikhao (copy karke bhej sakte ho). */
    private fun showLastCrash() {
        try {
            val f = java.io.File(filesDir, "last_crash.txt")
            if (!f.exists()) return
            val txt = f.readText()
            f.delete()
            val tv = android.widget.TextView(this)
            tv.text = txt
            tv.textSize = 10f
            tv.setTextIsSelectable(true)
            tv.setPadding(32, 16, 32, 16)
            val sv = android.widget.ScrollView(this)
            sv.addView(tv)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Pichla crash")
                .setView(sv)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", txt))
                    Toast.makeText(this, "Copy ho gaya", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Band", null)
                .show()
        } catch (_: Throwable) { }
    }
}
