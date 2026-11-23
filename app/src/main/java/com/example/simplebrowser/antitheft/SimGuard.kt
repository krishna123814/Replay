package com.example.simplebrowser.antitheft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 📶 SIM badalne / nikalne par Telegram alert (pehli baar sirf "baseline" yaad rakhta hai). */
object SimGuard {
    private const val K_FP = "sim_fp"
    private const val K_DESC = "sim_desc"
    private const val K_EMPTY = "sim_empty_n"

    fun hasPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Telegram `siminfo` command ke liye: abhi kaun si SIM(s) lagi hain, ek line me. */
    @Suppress("MissingPermission")
    fun currentDesc(ctx: Context): String {
        if (!hasPerm(ctx)) return "SIM check permission nahi di gayi."
        val raw = try {
            (ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager).activeSubscriptionInfoList
        } catch (_: Throwable) { null }
        val list = raw ?: emptyList()
        return if (list.isEmpty()) "📶 Koi SIM nahi lagi hai."
        else "📶 " + list.joinToString(" + ") {
            "${it.carrierName ?: "?"} (slot ${it.simSlotIndex + 1}, number: ${it.number?.takeIf { n -> n.isNotBlank() } ?: "pata nahi"})"
        }
    }

    @Suppress("MissingPermission")
    fun check(ctx: Context) {
        val a = ctx.applicationContext
        if (!TheftEngine.enabled(a) || !hasPerm(a)) return
        val raw = try {
            (a.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager).activeSubscriptionInfoList
        } catch (_: Throwable) { return }
        val list = raw ?: emptyList()

        val fp = list.map { "${it.subscriptionId}|${(it.iccId ?: "").takeLast(6)}" }.sorted().joinToString(",")
        val desc = if (list.isEmpty()) "koi SIM nahi"
        else list.joinToString(" + ") { "${it.carrierName ?: "?"} (slot ${it.simSlotIndex + 1})" }

        val sp = a.getSharedPreferences("theft", Context.MODE_PRIVATE)
        val old = sp.getString(K_FP, null)
        if (old == null) { sp.edit().putString(K_FP, fp).putString(K_DESC, desc).putInt(K_EMPTY, 0).apply(); return }
        if (old == fp) { sp.edit().putInt(K_EMPTY, 0).apply(); return }

        // Boot ke waqt SIM list kuch der khali aati hai: "SIM nikali" tabhi maano jab 3 baar lagatar khali dikhe
        if (list.isEmpty() && old.isNotEmpty()) {
            val n = sp.getInt(K_EMPTY, 0) + 1
            sp.edit().putInt(K_EMPTY, n).apply()
            if (n < 3) return
        }
        val oldDesc = sp.getString(K_DESC, "?") ?: "?"
        sp.edit().putString(K_FP, fp).putString(K_DESC, desc).putInt(K_EMPTY, 0).apply()
        val t = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date())
        TheftEngine.queueText(a, "📶 SIM badli gayi!\nPehle: $oldDesc\nAb: $desc\n📱 ${Build.MANUFACTURER} ${Build.MODEL}\n🕒 $t")
    }
}
