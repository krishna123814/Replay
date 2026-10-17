package com.example.simplebrowser.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Phone ↔ server transfer chalte waqt app ko zinda rakhta hai (notification ke saath). */
class TransferService : Service() {

    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("xfer", "Transfers", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, TransfersActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, "xfer")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MyFiles transfer chal raha hai")
            .setContentText("Phone ↔ server copy / move")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(77, n)
        }
        if (wl == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myfiles:xfer").also {
                it.acquire(6L * 60 * 60 * 1000)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            wl?.let { if (it.isHeld) it.release() }
        } catch (e: Throwable) {
        }
        wl = null
        super.onDestroy()
    }
}
