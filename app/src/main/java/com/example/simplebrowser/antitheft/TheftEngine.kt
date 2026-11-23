package com.example.simplebrowser.antitheft

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 🛡️ Anti-theft ka dimaag: settings, trigger, caption, status. */
object TheftEngine {
    /** Itni galat attempts par photo (5, 10, 15 ... har 5vi baar). */
    const val THRESHOLD = 5

    private const val PREFS = "theft"
    private const val K_ON = "on"
    private const val K_REMOTE = "remote"
    private const val K_ALARM_TRIG = "alarm_on_trigger"
    private const val K_STATUS = "status"
    private const val K_LAST_TRIGGER = "last_trigger"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_ON, false)
    fun setEnabled(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean(K_ON, on).apply(); applyEnabled(ctx) }

    /** Remote guard: Telegram se photo / locate / alarm hukm (phone har ~15 sec server se poochta hai). */
    fun remote(ctx: Context): Boolean = sp(ctx).getBoolean(K_REMOTE, false)
    fun setRemote(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(K_REMOTE, on).apply()
        if (on && enabled(ctx)) try { TheftGuardService.start(ctx.applicationContext) } catch (_: Throwable) { }
    }

    /** 5 galat password par alarm bhi bajao (Remote guard ON ho tabhi chalta hai). */
    fun alarmOnTrigger(ctx: Context): Boolean = sp(ctx).getBoolean(K_ALARM_TRIG, false)
    fun setAlarmOnTrigger(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean(K_ALARM_TRIG, on).apply() }

    fun status(ctx: Context): String = sp(ctx).getString(K_STATUS, "") ?: ""
    fun setStatus(ctx: Context, msg: String) {
        val t = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
        sp(ctx).edit().putString(K_STATUS, "$t — $msg").apply()
    }

    fun adminComponent(ctx: Context) = ComponentName(ctx.applicationContext, TheftAdmin::class.java)

    fun isAdmin(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminComponent(ctx))
    } catch (_: Throwable) { false }

    fun hasCamera(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // ───────────── on/off, boot ─────────────

    fun applyEnabled(ctx: Context) {
        val a = ctx.applicationContext
        if (enabled(a)) {
            TheftWatchdog.schedule(a)
            startGuardIfOn(a)
        } else {
            TheftWatchdog.cancel(a)
            try { a.stopService(Intent(a, TheftGuardService::class.java)) } catch (_: Throwable) { }
        }
    }

    fun startGuardIfOn(ctx: Context) {
        val a = ctx.applicationContext
        if (enabled(a) && remote(a) && !TheftGuardService.alive) {
            try { TheftGuardService.start(a) } catch (_: Throwable) { }
        }
    }

    /** App process bani: sirf watchdog lagao (foreground service background se nahi chalate). */
    fun scheduleIfOn(ctx: Context) { if (enabled(ctx)) TheftWatchdog.schedule(ctx) }

    /** BOOT_COMPLETED / app update ke baad. */
    fun onBoot(ctx: Context) {
        val a = ctx.applicationContext
        if (!enabled(a)) return
        TheftWatchdog.schedule(a)
        startGuardIfOn(a)
        try { SimGuard.check(a) } catch (_: Throwable) { }
    }

    // ───────────── lock screen events ─────────────

    fun onPasswordFailed(ctx: Context) {
        if (!enabled(ctx)) return
        val n = try {
            (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).currentFailedPasswordAttempts
        } catch (_: Throwable) { 0 }
        if (n <= 0 || n % THRESHOLD != 0) return
        // same attempt par do baar callback aaye to dobara nahi
        val now = System.currentTimeMillis()
        if (now - sp(ctx).getLong(K_LAST_TRIGGER, 0L) < 15_000L) return
        sp(ctx).edit().putLong(K_LAST_TRIGGER, now).apply()
        trigger(ctx, n, test = false)
        if (alarmOnTrigger(ctx) && remote(ctx)) {
            try { TheftGuardService.start(ctx.applicationContext, TheftGuardService.ACTION_ALARM_ON) } catch (_: Throwable) { }
        }
    }

    fun onPasswordSucceeded(ctx: Context) { /* count Android khud reset karta hai */ }

    // ───────────── trigger ─────────────

    /** reason: "" = galat password / test, "remote" = Telegram `photo` command. */
    fun trigger(ctx: Context, fails: Int, test: Boolean, reason: String = "") {
        val a = ctx.applicationContext
        setStatus(a, if (test) "Test: photo le rahe hain…" else if (reason == "remote") "Telegram hukm: photo le rahe hain…" else "$fails galat attempt — photo le rahe hain…")
        val h = Handler(Looper.getMainLooper())
        h.post {
            // 1x1 almost-transparent overlay: Android 10+ background se camera/foreground service ke liye "visible window" ka fayda
            TheftAnchor.show(a)
            try {
                ContextCompat.startForegroundService(
                    a,
                    Intent(a, TheftCamService::class.java)
                        .putExtra(TheftCamService.EXTRA_FAILS, fails)
                        .putExtra(TheftCamService.EXTRA_TEST, test)
                        .putExtra(TheftCamService.EXTRA_REASON, reason)
                )
            } catch (e: Throwable) {
                // Service start hi nahi hui (OS ne roka) -> kam se kam text alert
                TheftAnchor.hide(a)
                setStatus(a, "Camera service start nahi hui (${e.javaClass.simpleName}) — text alert bheja")
                queueText(a, buildCaption(a, fails, test, reason) + "\n⚠️ Photo nahi li ja saki (OS ne camera service roki).")
            }
        }
    }

    fun buildCaption(ctx: Context, fails: Int, test: Boolean, reason: String = ""): String {
        val t = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date())
        val head = when {
            test -> "TEST (asli chori nahi)"
            reason == "remote" -> "Telegram se mangi gayi photo"
            else -> "$fails baar galat password"
        }
        return "$head\n📱 ${Build.MANUFACTURER} ${Build.MODEL}\n🕒 $t"
    }

    /** Sirf text alert queue me daalo (photo nahi) aur bhejne ki koshish karo. */
    fun queueText(ctx: Context, text: String) {
        val a = ctx.applicationContext
        try {
            val base = "theft_" + System.currentTimeMillis() + "_" + (Math.random() * 1000).toInt()
            File(TheftSender.dir(a), "$base.txt").writeText(text)
        } catch (_: Throwable) { }
        // Turant bhejne ki koshish (background thread), na ho to WorkManager net aate hi
        Thread {
            try { if (!TheftSender.sendAllBlocking(a)) TheftSender.kick(a) } catch (_: Throwable) { TheftSender.kick(a) }
        }.start()
    }

    /** Capture ho gayi photos + caption disk par rakho; bhejna TheftSender ka kaam. */
    fun saveCapture(ctx: Context, photos: List<ByteArray>, caption: String) {
        val a = ctx.applicationContext
        val base = "theft_" + System.currentTimeMillis()
        try {
            val d = TheftSender.dir(a)
            photos.forEachIndexed { i, b -> File(d, "${base}_${i + 1}.jpg").writeBytes(b) }
            File(d, "$base.txt").writeText(
                if (photos.isNotEmpty()) caption else "$caption\n⚠️ Camera se photo nahi aa saki."
            )
        } catch (_: Throwable) { }
    }
}

/** 1x1, lagbhag transparent overlay window (SYSTEM_ALERT_WINDOW permission ho tabhi). */
object TheftAnchor {
    private var view: View? = null

    fun show(ctx: Context) {
        try {
            if (view != null || !Settings.canDrawOverlays(ctx)) return
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(ctx)
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.alpha = 0.01f
            wm.addView(v, lp)
            view = v
        } catch (_: Throwable) { view = null }
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        view = null
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) { }
    }
}
