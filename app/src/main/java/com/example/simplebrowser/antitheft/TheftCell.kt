package com.example.simplebrowser.antitheft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** 📡 Current cell tower (jis tower se phone jud raha hai) — caption me daalne ke liye ek line. */
object TheftCell {

    fun hasPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** "🗼 Airtel · LTE · Cell 123456 · TAC 4321" jaisi ek line, ya na mile to khaali "". */
    @Suppress("MissingPermission")
    fun line(ctx: Context): String {
        if (!hasPerm(ctx)) return ""
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return ""
            val carrier = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "?"
            val list: List<CellInfo> = try { tm.allCellInfo ?: emptyList() } catch (_: Throwable) { emptyList() }
            val reg = list.firstOrNull { it.isRegistered } ?: list.firstOrNull() ?: return "🗼 $carrier (cell info nahi mili)"
            val (kind, cellId, area) = when {
                reg is CellInfoLte -> Triple("LTE", reg.cellIdentity.ci.takeIf { it != Int.MAX_VALUE }, reg.cellIdentity.tac.takeIf { it != Int.MAX_VALUE })
                Build.VERSION.SDK_INT >= 29 && reg is CellInfoNr -> Triple("5G", null, null)
                reg is CellInfoGsm -> Triple("GSM", reg.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }, reg.cellIdentity.lac.takeIf { it != Int.MAX_VALUE })
                reg is CellInfoWcdma -> Triple("3G", reg.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }, reg.cellIdentity.lac.takeIf { it != Int.MAX_VALUE })
                reg is CellInfoCdma -> Triple("CDMA", reg.cellIdentity.basestationId.takeIf { it != Int.MAX_VALUE }, null)
                else -> Triple("?", null, null)
            }
            val extra = buildString {
                if (cellId != null) append(" · Cell $cellId")
                if (area != null) append(" · TAC/LAC $area")
            }
            return "🗼 $carrier · $kind$extra"
        } catch (_: Throwable) { return "" }
    }
}
