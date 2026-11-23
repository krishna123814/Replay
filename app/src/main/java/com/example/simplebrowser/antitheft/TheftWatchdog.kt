package com.example.simplebrowser.antitheft

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Har ~15 min: SIM check, pending alerts bhejna, aur Remote guard service band mili to dobara chalane ki koshish. */
object TheftWatchdog {
    private const val NAME = "theft_watchdog"

    fun schedule(ctx: Context) {
        try {
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TheftWatchdogWorker>(15, TimeUnit.MINUTES).build()
            )
        } catch (_: Throwable) { }
    }

    fun cancel(ctx: Context) {
        try { WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(NAME) } catch (_: Throwable) { }
    }
}

class TheftWatchdogWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val c = applicationContext
        if (!TheftEngine.enabled(c)) { TheftWatchdog.cancel(c); return Result.success() }
        try { SimGuard.check(c) } catch (_: Throwable) { }
        try { if (TheftSender.pendingCount(c) > 0) TheftSender.sendAllBlocking(c) } catch (_: Throwable) { }
        try { TheftEngine.startGuardIfOn(c) } catch (_: Throwable) { }
        return Result.success()
    }
}
