package com.example.simplebrowser.deploy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.simplebrowser.MainActivity
import com.example.simplebrowser.R

/** Download folder ko har ~3 sec dekhta hai (Auto Deploy ON ho tab). Notification ke saath zinda rehta hai. */
class AutoDeployService : Service() {

    @Volatile private var running = false
    private var th: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("deploy", "Auto Deploy", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, "deploy")
            .setSmallIcon(R.drawable.ic_notif_lion)
            .setContentTitle("📦 Auto Deploy ON")
            .setContentText("Download me " + AutoDeploy.pattern(this) + " ka intezaar")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(79, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(79, n)
        }
        if (!running) {
            running = true
            th = Thread { loop() }.also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_STICKY
    }

    private fun loop() {
        val seen = HashMap<String, LongArray>()
        var waitUntil = 0L
        while (running) {
            if (!AutoDeploy.on(this)) {            // switch OFF ho gaya
                stopSelf()
                break
            }
            try {
                if (System.currentTimeMillis() >= waitUntil) {
                    if (!AutoDeploy.tick(this, seen)) waitUntil = System.currentTimeMillis() + 30_000L
                }
            } catch (t: Throwable) {
                AutoDeploy.setLast(this, "❌ Error: " + (t.message ?: t.javaClass.simpleName))
                waitUntil = System.currentTimeMillis() + 30_000L
            }
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    override fun onDestroy() {
        running = false
        th?.interrupt()
        super.onDestroy()
    }
}
