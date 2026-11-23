package com.example.simplebrowser.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.simplebrowser.R
import com.example.simplebrowser.files.Dav
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 🛰️ Remote guard: har ~15 sec server se Telegram ka hukm poochta hai (photo / locate / alarm / alarmoff),
 * saath me battery batata hai aur SIM badli to alert deta hai. Alarm bhi yahin bajta hai.
 */
class TheftGuardService : Service() {

    companion object {
        const val ACTION_ALARM_ON = "theft_alarm_on"
        const val ACTION_ALARM_OFF = "theft_alarm_off"
        private const val CH = "theft_remote"
        private const val NOTIF_ID = 8842
        private const val POLL_MS = 15_000L
        private const val ALARM_MAX_MS = 120_000L

        @Volatile var alive = false

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, TheftGuardService::class.java)
            if (action != null) i.action = action
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    @Volatile private var running = false
    private var th: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private var locThread: HandlerThread? = null
    private var player: MediaPlayer? = null
    private val stopAlarmRunnable = Runnable { alarmOff() }

    private val http by lazy {
        Dav.client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            goForeground()
        } catch (e: Throwable) {
            TheftEngine.setStatus(this, "Remote guard start nahi hua (${e.javaClass.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        alive = true
        when (intent?.action) {
            ACTION_ALARM_ON -> alarmOn()
            ACTION_ALARM_OFF -> alarmOff()
        }
        val wantLoop = TheftEngine.enabled(this) && TheftEngine.remote(this)
        if (wantLoop && !running) {
            running = true
            th = Thread { loop() }.also { it.isDaemon = true; it.start() }
        }
        if (!wantLoop && player == null) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    // ───────────── polling ─────────────

    private fun loop() {
        var lastSim = 0L
        while (running) {
            if (!TheftEngine.enabled(this) || !TheftEngine.remote(this)) { running = false; break }
            val now = System.currentTimeMillis()
            if (now - lastSim > 60_000L) {
                lastSim = now
                try { SimGuard.check(this) } catch (_: Throwable) { }
            }
            try { poll() } catch (_: Throwable) { }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
        }
        main.post { if (player == null) stopSelf() }
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return Pair(if (level >= 0 && scale > 0) level * 100 / scale else -1, chg)
    }

    private fun poll() {
        val root = TheftSender.origin(this) ?: return
        val (pct, chg) = battery()
        val url = root + "api/theft_cmd?b=$pct&c=${if (chg) 1 else 0}"
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (r.code != 200) return
            val j = JSONObject(r.body?.string() ?: "{}")
            if (j.isNull("cmd")) return
            val cmd = j.optString("cmd", "")
            if (cmd.isNotBlank()) handle(cmd)
        }
    }

    private fun handle(cmd: String) {
        val app = applicationContext
        when (cmd) {
            "photo" -> TheftEngine.trigger(app, 0, test = false, reason = "remote")
            "locate" -> locate()
            "alarm" -> {
                alarmOn()
                TheftEngine.queueText(app, "🔔 Alarm chalu kar diya (max 2 min). Band karne ko `alarmoff` bhejo.\n📱 ${Build.MANUFACTURER} ${Build.MODEL}")
            }
            "alarmoff" -> {
                alarmOff()
                TheftEngine.queueText(app, "🔕 Alarm band kar diya.")
            }
        }
    }

    private fun locate() {
        val app = applicationContext
        if (locThread == null) locThread = HandlerThread("theft-loc").also { it.start() }
        val (pct, chg) = battery()
        TheftLocation.fetch(app, locThread!!.looper, 20_000L) { l ->
            val head = "📍 Phone ki location" + (if (pct >= 0) " · 🔋 $pct%" + (if (chg) " ⚡" else "") else "")
            TheftEngine.queueText(app, head + "\n" + TheftLocation.line(l, app) + "\n📱 ${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    // ───────────── alarm ─────────────

    private fun alarmOn() {
        main.post {
            try {
                if (player != null) return@post
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                main.postDelayed(stopAlarmRunnable, ALARM_MAX_MS)
            } catch (_: Throwable) { player = null }
        }
    }

    private fun alarmOff() {
        main.post {
            main.removeCallbacks(stopAlarmRunnable)
            try { player?.stop() } catch (_: Throwable) { }
            try { player?.release() } catch (_: Throwable) { }
            player = null
            if (!running) stopSelf()
        }
    }

    // ───────────── lifecycle / notification ─────────────

    override fun onDestroy() {
        running = false
        alive = false
        th?.interrupt()
        try { player?.release() } catch (_: Throwable) { }
        player = null
        locThread?.quitSafely()
        locThread = null
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Remote guard", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            this, 3, Intent(this, TheftActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif_lion)
            .setContentTitle("🛡️ Lion Anti-theft")
            .setContentText("Remote guard chalu hai")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
