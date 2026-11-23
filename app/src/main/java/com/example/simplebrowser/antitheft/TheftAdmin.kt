package com.example.simplebrowser.antitheft

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * 🛡️ Anti-theft: lock screen par galat password/PIN/pattern aane par Android isi receiver ko bulata hai
 * (Device Admin + <watch-login/> policy chahiye — res/xml/theft_admin.xml).
 */
class TheftAdmin : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent) {
        TheftEngine.onPasswordFailed(context.applicationContext)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        TheftEngine.onPasswordSucceeded(context.applicationContext)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        TheftEngine.setStatus(context.applicationContext, "Device Admin chalu ho gaya")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Anti-theft band ho jayega. Phone chori hone par photo Telegram par nahi aayegi."

    override fun onDisabled(context: Context, intent: Intent) {
        val a = context.applicationContext
        TheftEngine.setStatus(a, "Device Admin band kiya gaya")
        // Thief admin hataye to bhi ek text alert (net ho to turant, warna net aate hi)
        if (TheftEngine.enabled(a)) TheftEngine.queueText(a, "⚠️ Lion ka Device Admin band kiya gaya (Anti-theft ruk gaya).")
    }
}
