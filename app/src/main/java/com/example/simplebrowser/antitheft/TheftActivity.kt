package com.example.simplebrowser.antitheft

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.simplebrowser.files.Session

/** 🛡️ Anti-theft settings: sab permissions ek jagah, test button, status. */
class TheftActivity : AppCompatActivity() {

    private lateinit var tvChecks: TextView
    private lateinit var tvStatus: TextView
    private lateinit var sw: SwitchCompat
    private lateinit var swRemote: SwitchCompat
    private lateinit var swAlarm: SwitchCompat

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val anyResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; fitsSystemWindows = true; addView(root) })

        fun tv(text: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        root.addView(tv("🛡️ Anti-theft", 24f, true))
        root.addView(tv("Lock screen par ${TheftEngine.THRESHOLD} baar galat password/PIN/pattern -> front camera ki photo -> Telegram.", 13f))

        sw = SwitchCompat(this).apply {
            text = "Anti-theft chalu  "
            isChecked = TheftEngine.enabled(this@TheftActivity)
            setOnCheckedChangeListener { _, on ->
                TheftEngine.setEnabled(this@TheftActivity, on)
                if (on && !TheftEngine.isAdmin(this@TheftActivity)) askAdmin()
                refresh()
            }
        }
        root.addView(sw, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })

        swRemote = SwitchCompat(this).apply {
            text = "Remote guard (Telegram se photo/location/alarm)  "
            isChecked = TheftEngine.remote(this@TheftActivity)
            setOnCheckedChangeListener { _, on ->
                TheftEngine.setRemote(this@TheftActivity, on)
                refresh()
            }
        }
        root.addView(swRemote, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        swAlarm = SwitchCompat(this).apply {
            text = "5 galat password par alarm bhi bajao  "
            isChecked = TheftEngine.alarmOnTrigger(this@TheftActivity)
            setOnCheckedChangeListener { _, on -> TheftEngine.setAlarmOnTrigger(this@TheftActivity, on) }
        }
        root.addView(swAlarm, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        tvChecks = tv("", 14f)
        root.addView(tvChecks)

        btn("1️⃣  Camera + Notification permission") {
            val p = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
            permLauncher.launch(p.toTypedArray())
        }
        btn("2️⃣  Device Admin chalu karo (galat password pakadne ke liye)") { askAdmin() }
        btn("3️⃣  Display over other apps (overlay)") {
            anyResult.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("4️⃣  Battery: Lion ko Unrestricted / ignore karo") {
            try {
                anyResult.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
                anyResult.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        btn("5️⃣  Server login: File Manager kholo") {
            startActivity(Intent(this, com.example.simplebrowser.files.HomeActivity::class.java))
        }
        btn("6️⃣  Location + SIM check permission") {
            permLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            ))
        }
        btn("7️⃣  Location: \"Hamesha allow karo\" (app settings)") {
            Toast.makeText(this, "Permissions > Location > \"Allow all the time\"", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        root.addView(tv("Test", 16f, true).apply { setPadding(0, dp(18), 0, 0) })
        btn("📸  Test: abhi photo Telegram par bhejo") {
            if (!TheftEngine.hasCamera(this)) {
                Toast.makeText(this, "Pehle Camera permission do (1)", Toast.LENGTH_LONG).show()
            } else {
                TheftEngine.trigger(this, 0, test = true)
                Toast.makeText(this, "Photo le rahe hain… kuch second me Telegram dekho", Toast.LENGTH_LONG).show()
            }
        }
        btn("🔁  Pending alerts dobara bhejo") {
            TheftSender.kick(this)
            Toast.makeText(this, "Koshish shuru", Toast.LENGTH_SHORT).show()
        }

        btn("🔔  Alarm test bajao") {
            try { TheftGuardService.start(this, TheftGuardService.ACTION_ALARM_ON) } catch (_: Throwable) { }
        }
        btn("🔕  Alarm band karo") {
            try { TheftGuardService.start(this, TheftGuardService.ACTION_ALARM_OFF) } catch (_: Throwable) { }
        }
        root.addView(tv("Telegram commands: photo · locate · alarm · alarmoff · phone (status/battery)", 12f).apply { setPadding(0, dp(14), 0, 0) })

        tvStatus = tv("", 12f).apply { setPadding(0, dp(14), 0, 0); gravity = Gravity.START }
        root.addView(tvStatus)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun askAdmin() {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, TheftEngine.adminComponent(this))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lock screen par galat password pakadne aur chori me photo bhejne ke liye. Isse Lion uninstall karna bhi mushkil hota hai.")
        anyResult.launch(i)
    }

    private fun battOk(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Throwable) { false }

    private fun refresh() {
        fun mark(b: Boolean) = if (b) "✅" else "❌"
        if (!Session.hasCreds()) Session.load(this)
        tvChecks.text = buildString {
            append("${mark(TheftEngine.hasCamera(this@TheftActivity))}  Camera permission\n")
            append("${mark(TheftEngine.isAdmin(this@TheftActivity))}  Device Admin\n")
            append("${mark(Settings.canDrawOverlays(this@TheftActivity))}  Overlay permission\n")
            append("${mark(battOk())}  Battery unrestricted\n")
            append("${mark(Session.hasCreds())}  Server login (File Manager)\n")
            append("${mark(TheftLocation.hasPerm(this@TheftActivity))}  Location permission\n")
            append("${mark(TheftLocation.hasBackground(this@TheftActivity))}  Location \"hamesha\" (background)\n")
            append("${mark(SimGuard.hasPerm(this@TheftActivity))}  SIM check permission\n")
            append("${mark(TheftGuardService.alive)}  Remote guard service chal rahi hai")
        }
        val pend = TheftSender.pendingCount(this)
        tvStatus.text = "Status: " + TheftEngine.status(this).ifBlank { "—" } +
            (if (pend > 0) "\nPending alerts: $pend" else "")
        if (sw.isChecked != TheftEngine.enabled(this)) sw.isChecked = TheftEngine.enabled(this)
        if (swRemote.isChecked != TheftEngine.remote(this)) swRemote.isChecked = TheftEngine.remote(this)
    }
}
