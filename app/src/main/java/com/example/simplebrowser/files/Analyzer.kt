package com.example.simplebrowser.files

import android.content.Context
import android.os.StatFs
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Ek folder ke andar ek item (folder ya file) ne kitni jagah li. */
data class AnRow(val name: String, val url: String, val isDir: Boolean, val bytes: Long, val files: Int)

/** Scan ka nateeja. `failed` = jo sub-folder padh nahi paye (to total thoda kam ho sakta hai). */
data class AnResult(val rows: List<AnRow>, val total: Long, val files: Int, val failed: Int, val cancelled: Boolean)

/** Capacity ki ek line (+ use ka hissa 0..1, agar pata ho). */
class CapInfo(val text: String, val frac: Float?)

/**
 * Storage analyzer: kisi bhi folder (AIC / HF / Phone / SD / USB) ke andar har item ka size nikalta hai.
 * Sab storage par ek hi code chalta hai (Fs.list), sirf phone par seedha File API use hota hai (tez).
 */
object Analyzer {

    /** Hugging Face bucket ki capacity (bytes). Badalni ho to sirf ye ek line badlo. */
    const val HF_CAPACITY_GB = 100L
    val HF_CAPACITY: Long = HF_CAPACITY_GB * 1024L * 1024L * 1024L

    private const val PREFS = "analyze_cache"
    private const val THREADS = 4

    // ───────────────────────── scan ─────────────────────────

    fun analyze(dirUrl: String, cancel: AtomicBoolean, progress: (Int) -> Unit): AnResult {
        val kids = Fs.list(dirUrl)
        val scanned = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val futures = kids.map { e ->
                pool.submit(Callable {
                    if (!e.isDir) {
                        AnRow(e.name, e.url, false, e.size, 1)
                    } else {
                        val r = sizeOfDir(e.url, cancel, scanned, failed, progress)
                        AnRow(e.name, e.url, true, r.first, r.second)
                    }
                })
            }
            val rows = ArrayList<AnRow>(kids.size)
            for (f in futures) {
                try {
                    rows.add(f.get())
                } catch (ex: java.util.concurrent.ExecutionException) {
                    val c = ex.cause
                    if (c is DavAuthException) throw c
                    failed.incrementAndGet()
                }
            }
            rows.sortByDescending { it.bytes }
            var total = 0L
            var files = 0
            for (r in rows) {
                total += r.bytes
                files += r.files
            }
            return AnResult(rows, total, files, failed.get(), cancel.get())
        } finally {
            pool.shutdownNow()
        }
    }

    /** Ek folder ka (bytes, files). Server par depth-1 PROPFIND se neeche utarta hai. */
    private fun sizeOfDir(
        url: String,
        cancel: AtomicBoolean,
        scanned: AtomicInteger,
        failed: AtomicInteger,
        progress: (Int) -> Unit
    ): Pair<Long, Int> {
        if (LocalFs.isLocal(url)) {
            val t = LocalFs.countTree(LocalFs.fileOf(url))
            progress(scanned.incrementAndGet())
            return Pair(t.second, t.first)
        }
        var bytes = 0L
        var files = 0
        val stack = java.util.ArrayDeque<String>()
        stack.push(url)
        while (!stack.isEmpty()) {
            if (cancel.get()) break
            val d = stack.pop()
            val list = try {
                Dav.list(d)
            } catch (e: DavAuthException) {
                throw e
            } catch (e: Exception) {
                failed.incrementAndGet()
                continue
            }
            for (e in list) {
                if (e.isDir) {
                    stack.push(e.url)
                } else {
                    bytes += e.size
                    files++
                }
            }
            progress(scanned.incrementAndGet())
        }
        return Pair(bytes, files)
    }

    // ───────────────────────── capacity ─────────────────────────

    /** Storage ki capacity ki line. Network use kar sakta hai (AIC), isliye background thread se bulao. */
    fun capacity(ctx: Context, store: Store, url: String, rootUrl: String, total: Long, complete: Boolean): CapInfo? {
        return when (store) {
            Store.HF -> {
                val used: Long? = if (url == rootUrl) (if (complete) total else null)
                else cachedUsed(ctx, "hf")?.first
                val cap = HF_CAPACITY
                if (used == null) {
                    CapInfo("☁️ HF capacity: " + fmtSize(cap), null)
                } else {
                    val left = cap - used
                    val pct = used.toDouble() * 100 / cap
                    val tail = if (left >= 0) "khaali " + fmtSize(left) else "⚠ limit se " + fmtSize(-left) + " zyada"
                    CapInfo(
                        "☁️ HF: " + fmtSize(used) + " / " + fmtSize(cap) + String.format(Locale.US, " (%.1f%%) · ", pct) + tail,
                        (used.toDouble() / cap).toFloat().coerceIn(0f, 1f)
                    )
                }
            }
            Store.AIC -> {
                val d = try {
                    Xfer.disk()
                } catch (e: Exception) {
                    null
                }
                if (d == null) {
                    null
                } else {
                    val used = d.second - d.first
                    CapInfo(
                        "💽 VPS disk: " + fmtSize(used) + " / " + fmtSize(d.second) + " · khaali " + fmtSize(d.first),
                        (used.toDouble() / d.second).toFloat().coerceIn(0f, 1f)
                    )
                }
            }
            Store.LOCAL -> {
                try {
                    val sf = StatFs(LocalFs.fileOf(url).path)
                    val t = sf.totalBytes
                    val f = sf.availableBytes
                    if (t <= 0) null else CapInfo(
                        "📱 Storage: " + fmtSize(t - f) + " / " + fmtSize(t) + " · khaali " + fmtSize(f),
                        ((t - f).toDouble() / t).toFloat().coerceIn(0f, 1f)
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    // ───────────────────────── cache (Home ke HF card ke liye) ─────────────────────────

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun keyFor(store: Store, rootUrl: String): String = if (store == Store.LOCAL) "local:$rootUrl" else store.id

    fun saveUsed(ctx: Context, key: String, used: Long) {
        sp(ctx).edit().putLong("used_$key", used).putLong("time_$key", System.currentTimeMillis()).apply()
    }

    /** (used bytes, scan ka time ms) ya null agar kabhi analyze nahi hua. */
    fun cachedUsed(ctx: Context, key: String): Pair<Long, Long>? {
        val p = sp(ctx)
        if (!p.contains("used_$key")) return null
        return Pair(p.getLong("used_$key", 0L), p.getLong("time_$key", 0L))
    }
}
