package com.example.simplebrowser.antitheft

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 🔌 Phone switch-off/reboot shuru hote hi (ACTION_SHUTDOWN) turant 4-5 photo (front+back) + location.
 * Note: OS yahan bahut kam samay deta hai (kabhi kabhi 1-2 second) — best-effort hai, 100% guarantee nahi.
 */
class TheftShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN && intent.action != Intent.ACTION_REBOOT) return
        try { TheftEngine.onShutdown(context.applicationContext) } catch (_: Throwable) { }
    }
}
