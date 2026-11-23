package com.example.simplebrowser.deploy

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 🐕 Auto Deploy ka watchdog. AutoDeployService (Download folder ki 3-sec nigrani) ko Android/OEM battery manager
 * kill kar deta hai to file tabhi jati thi jab Lion khola jaye. Ab har ~15 min WorkManager (kill hone par bhi chalta hai):
 *  1) service band ho to dobara chalane ki koshish,
 *  2) na chale to khud hi Download folder scan karke server/GitHub par bhej deta hai.
 */
object DeployWatchdog {
    private const val NAME = "deploy_watchdog"

    fun schedule(ctx: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<DeployWatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (_: Throwable) { }
    }

    fun cancel(ctx: Context) {
        try { WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(NAME) } catch (_: Throwable) { }
    }

    fun ignoringBattery(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Throwable) { true }

    /** Battery optimization se Lion ko bahar rakhne ki screen (Auto Deploy ON karte waqt). */
    fun batteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
}

class DeployWatchdogWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val c = applicationContext
        val srvOn = AutoDeploy.on(c)
        val gitOn = GitDeploy.on(c)
        val watching = GitDeploy.watching(c)
        if (!srvOn && !gitOn && !watching) {          // dono OFF -> watchdog ki zaroorat nahi
            DeployWatchdog.cancel(c)
            return Result.success()
        }
        if (AutoDeployService.alive) return Result.success()   // service theek chal rahi hai

        // 1) service wapas chalao (background se OS rok bhi sakta hai — tab neeche wala raasta)
        AutoDeploy.start(c)
        try { Thread.sleep(2500) } catch (_: InterruptedException) { }
        if (AutoDeployService.alive) return Result.success()

        // 2) service nahi chali: yahin se scan + upload (file 2 round same dikhni chahiye, isliye do tick)
        if (srvOn) {
            val seen = HashMap<String, LongArray>()
            try {
                for (i in 0 until 3) {
                    AutoDeploy.tick(c, seen)
                    if (i < 2) Thread.sleep(3500)
                }
            } catch (t: Throwable) {
                AutoDeploy.setLast(c, "❌ Watchdog error: " + (t.message ?: t.javaClass.simpleName))
            }
        }
        if (gitOn) {
            val seen = HashMap<String, LongArray>()
            try {
                for (i in 0 until 3) {
                    GitDeploy.tick(c, seen)
                    if (i < 2) Thread.sleep(3500)
                }
            } catch (t: Throwable) {
                GitDeploy.setLast(c, "❌ Watchdog error: " + (t.message ?: t.javaClass.simpleName))
            }
        }
        if (gitOn || watching) {
            try { GitDeploy.watchTick(c) } catch (_: Throwable) { }
        }
        return Result.success()
    }
}
