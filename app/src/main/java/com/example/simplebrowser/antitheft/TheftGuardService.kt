package com.example.simplebrowser.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.RingtoneManager
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 🛰️ Remote guard: har ~15 sec server se Telegram ka hukm poochta hai
 * (photo / locate / alarm / alarmoff / lock / findme / audio / siminfo / track / trackoff),
 * saath me battery batata hai aur SIM badli to alert deta hai. Alarm bhi yahin bajta hai.
 * Live tracking ON ho to har ~5 min location bhi bhejta hai.
 */
class TheftGuardService : Service() {

    companion object {
        const val ACTION_ALARM_ON = "theft_alarm_on"
        const val ACTION_ALARM_OFF = "theft_alarm_off"
        private const val CH = "theft_remote"
        private const val NOTIF_ID = 8842
        private const val POLL_MS = 15_000L
        private const val ALARM_MAX_MS = 120_000L
        private const val TRACK_EVERY_MS = 5 * 60_000L   // live tracking interval: 5 min
        private const val AUDIO_SECONDS = 10

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
    private var recorder: MediaRecorder? = null
    private val stopAlarmRunnable = Runnable { alarmOff() }
    private var lastTrackSend = 0L

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
            if (TheftEngine.trackOn(this) && now - lastTrackSend >= TRACK_EVERY_MS) {
                lastTrackSend = now
                try { trackTick() } catch (_: Throwable) { }
            }
            try { poll() } catch (_: Throwable) { }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
        }
        main.post { if (player == null) stopSelf() }
    }

    private fun poll() {
        val root = TheftSender.origin(this) ?: return
        val (pct, chg) = TheftEngine.battery(this)
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
            "lock" -> lockNow()
            "findme" -> TheftEngine.findMe(app)
            "audio" -> recordAndSendAudio()
            "siminfo" -> TheftEngine.queueText(app, SimGuard.currentDesc(app) + "\n📱 ${Build.MANUFACTURER} ${Build.MODEL}")
            "track" -> {
                TheftEngine.setTrackOn(app, true)
                lastTrackSend = 0L   // turant ek location bhej do
                TheftEngine.queueText(app, "📍 Live tracking chalu — har ~5 min location aayegi. Band karne ko `trackoff` bhejo.")
            }
            "trackoff" -> {
                TheftEngine.setTrackOn(app, false)
                TheftEngine.queueText(app, "📍 Live tracking band kar di.")
            }
        }
    }

    private fun locate() {
        val app = applicationContext
        if (locThread == null) locThread = HandlerThread("theft-loc").also { it.start() }
        val (pct, chg) = TheftEngine.battery(app)
        TheftLocation.fetch(app, locThread!!.looper, 20_000L) { l ->
            val head = "📍 Phone ki location" + (if (pct >= 0) " · 🔋 $pct%" + (if (chg) " ⚡" else "") else "")
            TheftEngine.queueText(app, head + "\n" + TheftLocation.line(l, app) + "\n" + TheftCell.line(app) + "\n📱 ${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    /** Live tracking tick: har ~5 min, `locate` jaisa hi par alag label. */
    private fun trackTick() {
        val app = applicationContext
        if (locThread == null) locThread = HandlerThread("theft-loc").also { it.start() }
        val (pct, chg) = TheftEngine.battery(app)
        TheftLocation.fetch(app, locThread!!.looper, 20_000L) { l ->
            val head = "📍 Live tracking" + (if (pct >= 0) " · 🔋 $pct%" + (if (chg) " ⚡" else "") else "")
            TheftEngine.queueText(app, head + "\n" + TheftLocation.line(l, app))
        }
    }

    // ───────────── lock / findme ─────────────

    private fun lockNow() {
        val app = applicationContext
        try {
            (app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                .lockNow()
            TheftEngine.queueText(app, "🔒 Phone turant lock kar diya gaya.")
        } catch (e: Throwable) {
            TheftEngine.queueText(app, "⚠️ Lock nahi ho saka (${e.javaClass.simpleName}) — Device Admin chalu check karo.")
        }
    }

    // ───────────── mic recording ─────────────

    private fun recordAndSendAudio() {
        val app = applicationContext
        if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            TheftEngine.queueText(app, "⚠️ Audio record ke liye Microphone permission nahi di gayi hai.")
            return
        }
        Thread {
            val f = File(cacheDir, "theft_audio_${System.currentTimeMillis()}.m4a")
            var ok = false
            try {
                val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setOutputFile(f.absolutePath)
                rec.prepare()
                rec.start()
                recorder = rec
                Thread.sleep(AUDIO_SECONDS * 1000L)
                try { rec.stop() } catch (_: Throwable) { }
                rec.release()
                recorder = null
                ok = f.exists() && f.length() > 0
            } catch (_: Throwable) {
                try { recorder?.release() } catch (_: Throwable) { }
                recorder = null
            }
            val caption = "🎙️ ${AUDIO_SECONDS}s audio (remote command)\n📱 ${Build.MANUFACTURER} ${Build.MODEL}"
            if (ok) {
                if (!TheftSender.sendAudioBlocking(app, f, caption)) {
                    TheftEngine.queueText(app, "⚠️ Audio record ho gayi par abhi bhej nahi saka — baad me try karo.")
                }
            } else {
                TheftEngine.queueText(app, "⚠️ Audio record nahi ho saki.")
            }
            try { f.delete() } catch (_: Throwable) { }
        }.start()
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
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null
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
            // specialUse + microphone dono — `audio` remote command isi service se chalta hai
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
