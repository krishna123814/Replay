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

/** Download folder ko har ~3 sec dekhta hai (Auto Deploy server / GitHub me se koi ON ho tab). Notification ke saath zinda rehta hai. */
class AutoDeployService : Service() {

    companion object {
        /** Service ka loop abhi zinda hai? (DeployWatchdog isse dekhta hai; process mar jaye to apne aap false) */
        @Volatile var alive = false
    }

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
            .setContentText("Download me " + waitingFor() + " ka intezaar")
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
            alive = true
            th = Thread { loop() }.also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_STICKY
    }

    private fun waitingFor(): String {
        val l = ArrayList<String>()
        if (AutoDeploy.on(this)) l.add(AutoDeploy.pattern(this))
        if (GitDeploy.on(this)) l.add(GitDeploy.pattern(this))
        val w = if (GitDeploy.watching(this)) " (build ka intezaar)" else ""
        return (if (l.isEmpty()) AutoDeploy.pattern(this) else l.joinToString(" / ")) + w
    }

    private fun loop() {
        val seenSrv = HashMap<String, LongArray>()
        val seenGit = HashMap<String, LongArray>()
        var waitSrv = 0L
        var waitGit = 0L
        while (running) {
            val srvOn = AutoDeploy.on(this)
            val gitOn = GitDeploy.on(this)
            val watching = GitDeploy.watching(this)
            if (!srvOn && !gitOn && !watching) {   // dono switch OFF aur build ka intezaar bhi nahi
                stopSelf()
                break
            }
            if (srvOn) {
                try {
                    if (System.currentTimeMillis() >= waitSrv) {
                        if (!AutoDeploy.tick(this, seenSrv)) waitSrv = System.currentTimeMillis() + 30_000L
                    }
                } catch (t: Throwable) {
                    AutoDeploy.setLast(this, "❌ Error: " + (t.message ?: t.javaClass.simpleName))
                    waitSrv = System.currentTimeMillis() + 30_000L
                }
            }
            if (gitOn) {
                try {
                    if (System.currentTimeMillis() >= waitGit) {
                        if (!GitDeploy.tick(this, seenGit)) waitGit = System.currentTimeMillis() + 30_000L
                    }
                } catch (t: Throwable) {
                    GitDeploy.setLast(this, "❌ Error: " + (t.message ?: t.javaClass.simpleName))
                    waitGit = System.currentTimeMillis() + 30_000L
                }
            }
            if (gitOn || watching) {
                try {
                    GitDeploy.watchTick(this)
                } catch (t: Throwable) {
                    GitDeploy.setLast(this, "❌ Build watch error: " + (t.message ?: t.javaClass.simpleName))
                }
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
        alive = false
        th?.interrupt()
        super.onDestroy()
    }
}
