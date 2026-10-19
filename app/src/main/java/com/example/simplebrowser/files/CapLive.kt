package com.example.simplebrowser.files

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sab storage ki capacity ek jagah (Home aur File Manager dono me live dikhane ke liye).
 *  - Phone / SD / USB: StatFs (sasta, kabhi bhi bula sakte ho).
 *  - AIC: server ka /xfer/disk.
 *  - HF: pehle server ka WebDAV quota (agar de), warna background me apne aap Analyze (har 15 min me ek baar).
 */
object CapLive {

    class Cap(val used: Long, val total: Long, val note: String = "") {
        val free: Long get() = (total - used).coerceAtLeast(0L)
        val usedFrac: Float get() = if (total > 0) (used.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
        val freePct: Int get() = if (total > 0) Math.round(100.0 * free / total).toInt().coerceIn(0, 100) else 0
        fun head(): String = "$freePct% khaali · ${fmtSize(free)} free"
        fun detail(): String = fmtSize(used) + " use / " + fmtSize(total) + note
        fun oneLine(): String = head() + " (" + fmtSize(total) + ")"
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor { r -> Thread(r, "cap-live").also { it.isDaemon = true } }

    fun local(dir: File): Cap? = try {
        val sf = StatFs(dir.path)
        val t = sf.totalBytes
        val f = sf.availableBytes
        if (t <= 0L) null else Cap((t - f).coerceAtLeast(0L), t)
    } catch (e: Exception) {
        null
    }

    /** Blocking (network): background thread se bulao. */
    fun aic(): Cap? = try {
        val d = Xfer.disk()
        if (d == null) null else Cap((d.second - d.first).coerceAtLeast(0L), d.second)
    } catch (e: Exception) {
        null
    }

    // ───────────────────────── HF ─────────────────────────

    private const val STALE_MS = 15L * 60 * 1000
    private const val QUOTA_EVERY_MS = 60L * 1000

    @Volatile private var hfQuotaUsed: Long? = null
    @Volatile private var hfQuotaAt = 0L
    private val hfBusy = AtomicBoolean(false)
    @Volatile private var scanCancel = AtomicBoolean(false)

    /** Scan / quota check chal raha hai? */
    @Volatile private var scanning = false
    val hfScanning: Boolean get() = scanning

    private fun ago(ms: Long): String {
        val m = ((System.currentTimeMillis() - ms) / 60000L).coerceAtLeast(0L)
        return when {
            m < 1 -> "abhi"
            m < 60 -> "$m min pehle"
            else -> (m / 60).toString() + " ghante pehle"
        }
    }

    /** Network ke bina: server quota ya pichla scan. Dono nahi to null. */
    fun hfCached(ctx: Context): Cap? {
        val q = hfQuotaUsed
        if (q != null) return Cap(q, Analyzer.HF_CAPACITY, " · server se")
        val c = Analyzer.cachedUsed(ctx, "hf") ?: return null
        return Cap(c.first, Analyzer.HF_CAPACITY, " · " + ago(c.second))
    }

    fun cancelScan() {
        scanCancel.set(true)
    }

    private const val QBODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:quota-used-bytes/></d:prop></d:propfind>"

    private fun probeQuota(rootUrl: String): Long? = try {
        val req = Request.Builder().url(Dav.withSlash(rootUrl))
            .method("PROPFIND", QBODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0").build()
        Dav.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                null
            } else {
                val txt = r.body?.string().orEmpty()
                Regex("<[^>]*quota-used-bytes[^>]*>\\s*(\\d+)\\s*<").find(txt)?.groupValues?.get(1)?.toLongOrNull()
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * HF ka use apne aap taaza rakho. Sasta check har baar; asli scan sirf tab jab pichla 15 min se purana ho
     * (ya force). Pichhle scan ke baad `onDone` main thread par.
     */
    fun hfTick(ctx: Context, force: Boolean, onDone: () -> Unit) {
        if (!hfBusy.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        bg.execute {
            try {
                val now = System.currentTimeMillis()
                if (force || now - hfQuotaAt > QUOTA_EVERY_MS) {
                    hfQuotaAt = now
                    hfQuotaUsed = probeQuota(Storages.root(Store.HF))
                }
                if (hfQuotaUsed == null) {
                    val c = Analyzer.cachedUsed(app, "hf")
                    val stale = c == null || now - c.second > STALE_MS
                    if (force || stale) {
                        val cancel = AtomicBoolean(false)
                        scanCancel = cancel
                        scanning = true
                        main.post { onDone() }                  // "scan ho raha hai" dikhane ke liye
                        val r = Analyzer.analyze(Dav.withSlash(Storages.root(Store.HF)), cancel) { }
                        if (!r.cancelled && r.failed == 0) Analyzer.saveUsed(app, "hf", r.total)
                    }
                }
            } catch (e: Exception) {
                // network / login dikkat: pichla cache hi dikhta rahega
            } finally {
                scanning = false
                hfBusy.set(false)
                main.post { onDone() }
            }
        }
    }
}
