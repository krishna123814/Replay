package com.example.simplebrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

// ═════════════════════════════════════════════════════════════════════════
// Settings (⚙ settings -> ⚡ Download settings se badalti hain)
// ═════════════════════════════════════════════════════════════════════════
object DlSettings {
    const val K_ENGINE = "dl_engine"
    const val K_CONNS = "dl_conns"
    const val K_PARALLEL = "dl_parallel"
    const val K_RETRY_ON = "dl_retry_on"
    const val K_RETRY_MAX = "dl_retry_max"
    const val K_BY_TYPE = "dl_by_type"
    const val K_VID_ICON = "vid_icon_on"

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FloatingService.PREFS, Context.MODE_PRIVATE)

    fun getBool(ctx: Context, key: String, def: Boolean): Boolean = p(ctx).getBoolean(key, def)
    fun getInt(ctx: Context, key: String, def: Int): Int = p(ctx).getInt(key, def)
    fun setBool(ctx: Context, key: String, v: Boolean) { p(ctx).edit().putBoolean(key, v).apply() }
    fun setInt(ctx: Context, key: String, v: Int) { p(ctx).edit().putInt(key, v).apply() }

    fun getStr(ctx: Context, key: String, def: String): String = p(ctx).getString(key, def) ?: def
    fun setStr(ctx: Context, key: String, v: String) { p(ctx).edit().putString(key, v).apply() }

    fun engineOn(ctx: Context): Boolean = getBool(ctx, K_ENGINE, true)
    fun conns(ctx: Context): Int = getInt(ctx, K_CONNS, 4).coerceIn(1, 8)
    fun parallel(ctx: Context): Int = getInt(ctx, K_PARALLEL, 2).coerceIn(1, 4)
    fun retryOn(ctx: Context): Boolean = getBool(ctx, K_RETRY_ON, true)
    fun retryMax(ctx: Context): Int = getInt(ctx, K_RETRY_MAX, 5).coerceIn(1, 10)
    fun byType(ctx: Context): Boolean = getBool(ctx, K_BY_TYPE, true)
    fun vidIcon(ctx: Context): Boolean = getBool(ctx, K_VID_ICON, true)
}

// ═════════════════════════════════════════════════════════════════════════
// Formatting helpers
// ═════════════════════════════════════════════════════════════════════════
object DlFmt {
    fun size(b: Long): String {
        if (b < 0) return "?"
        val kb = b / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$b B"
        }
    }

    fun speed(bps: Double): String = size(bps.toLong()) + "/s"

    fun eta(sec: Long): String {
        if (sec < 0) return "—"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Storage: Downloads/Lion/<Type>/file  (Android 10+: MediaStore, purane: app ka Downloads folder)
// ═════════════════════════════════════════════════════════════════════════
object DlStorage {

    class Out(
        val stream: OutputStream,
        val uri: String,
        val display: String,
        val finish: () -> Unit,
        val abort: () -> Unit
    )

    private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "3gp", "m4v", "ts", "wmv", "mpg", "mpeg")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr")
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "ico")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso")
    private val DOC = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "epub",
        "odt", "ods", "json", "xml", "html", "htm", "md"
    )

    fun categoryOf(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "apk" || ext == "xapk" || ext == "apks" -> "APKs"
            ext in VIDEO || mime.startsWith("video/") -> "Videos"
            ext in AUDIO || mime.startsWith("audio/") -> "Audio"
            ext in IMAGE || mime.startsWith("image/") -> "Images"
            ext in ARCHIVE -> "Archives"
            ext in DOC -> "Documents"
            else -> "Others"
        }
    }

    fun mimeFor(name: String, mime: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val g: String? =
            if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
        val m = mime?.substringBefore(';')?.trim().orEmpty()
        return when {
            g != null && (m.isEmpty() || m == "application/octet-stream") -> g
            m.isNotEmpty() -> m
            g != null -> g
            else -> "application/octet-stream"
        }
    }

    /** Naam me extension nahi hai to mime se laga do (blob/data ke liye zaroori). */
    fun ensureExt(name: String, mime: String?): String {
        if (name.contains('.')) return name
        val m = mime?.substringBefore(';')?.trim().orEmpty()
        val ext = if (m.isNotEmpty()) MimeTypeMap.getSingleton().getExtensionFromMimeType(m) else null
        return if (ext != null) "$name.$ext" else name
    }

    fun open(ctx: Context, name: String, mime: String, category: String?): Out {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rel = Environment.DIRECTORY_DOWNLOADS + "/Lion" +
                (if (category != null) "/$category" else "")
            val cv = ContentValues()
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val res = ctx.contentResolver
            val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IOException("File bana nahi payi")
            val os = res.openOutputStream(uri)
            if (os == null) {
                try { res.delete(uri, null, null) } catch (e: Exception) { }
                throw IOException("File open nahi hui")
            }
            var shown = name
            try {
                val c = res.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                if (c != null) {
                    if (c.moveToFirst()) shown = c.getString(0) ?: name
                    c.close()
                }
            } catch (e: Exception) { }
            val display = "$rel/$shown"
            return Out(
                os, uri.toString(), display,
                finish = {
                    try { os.close() } catch (e: Exception) { }
                    try {
                        val v = ContentValues()
                        v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        res.update(uri, v, null, null)
                    } catch (e: Exception) { }
                },
                abort = {
                    try { os.close() } catch (e: Exception) { }
                    try { res.delete(uri, null, null) } catch (e: Exception) { }
                }
            )
        }
        // Android 9 ya kam
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        var dir = File(base, "Lion")
        if (category != null) dir = File(dir, category)
        dir.mkdirs()
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            f = File(dir, "$stem ($n)$ext")
            n++
        }
        val os = FileOutputStream(f)
        val file = f
        return Out(
            os, file.absolutePath, file.absolutePath,
            finish = { try { os.close() } catch (e: Exception) { } },
            abort = {
                try { os.close() } catch (e: Exception) { }
                file.delete()
            }
        )
    }

    /** Saved file kholne ka intent (na ban sake to null). */
    fun viewIntent(ctx: Context, uriStr: String, mime: String): Intent? {
        if (uriStr.isEmpty()) return null
        return try {
            val uri: Uri = if (uriStr.startsWith("content://")) Uri.parse(uriStr)
            else FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(uriStr))
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Exception) {
            null
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Task model
// ═════════════════════════════════════════════════════════════════════════
enum class DlStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

class DlTask(val id: Int, val spec: DlSpec, var wifiOnly: Boolean) {
    @Volatile var status: DlStatus = DlStatus.QUEUED
    @Volatile var name: String = spec.name
    @Volatile var mime: String = spec.mime
    @Volatile var total: Long = -1L
    @Volatile var conns: Int = 0
    @Volatile var rangeOk: Boolean = false
    @Volatile var error: String = ""
    @Volatile var retries: Int = 0
    @Volatile var savedUri: String = ""
    @Volatile var savedPath: String = ""
    var createdAt: Long = System.currentTimeMillis()
    @Volatile var finishedAt: Long = 0L

    // runtime (persist nahi hota)
    val bytes = AtomicLong(0L)
    @Volatile var url: String = spec.url        // redirect ke baad ka final URL
    @Volatile var stop: Int = 0                 // 0 chal raha, 1 pause, 2 cancel, 3 error-abort
    @Volatile var workerActive: Boolean = false
    @Volatile var note: String = ""
    @Volatile var speed: Double = 0.0
    @Volatile var lastBytes: Long = 0L
    @Volatile var lastTime: Long = 0L
    @Volatile var firstError: Throwable? = null
    @Volatile var mergeDone: Long = -1L
    val openConns = CopyOnWriteArrayList<HttpURLConnection>()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", spec.url).put("cookie", spec.cookie).put("ua", spec.ua)
        .put("referer", spec.referer).put("sname", spec.name).put("smime", spec.mime)
        .put("wifi", wifiOnly).put("status", status.name).put("name", name).put("mime", mime)
        .put("total", total).put("conns", conns).put("range", rangeOk).put("done", bytes.get())
        .put("error", error).put("retries", retries).put("suri", savedUri).put("spath", savedPath)
        .put("created", createdAt).put("finished", finishedAt)

    companion object {
        fun fromJson(o: JSONObject): DlTask? {
            return try {
                val spec = DlSpec(
                    o.getString("url"), o.optString("cookie"), o.optString("ua"),
                    o.optString("referer"), o.optString("sname", "download"), o.optString("smime")
                )
                val t = DlTask(o.getInt("id"), spec, o.optBoolean("wifi", false))
                t.status = try { DlStatus.valueOf(o.optString("status", "PAUSED")) } catch (e: Exception) { DlStatus.PAUSED }
                t.name = o.optString("name", spec.name)
                t.mime = o.optString("mime", spec.mime)
                t.total = o.optLong("total", -1L)
                t.conns = o.optInt("conns", 0)
                t.rangeOk = o.optBoolean("range", false)
                t.bytes.set(o.optLong("done", 0L))
                t.error = o.optString("error")
                t.retries = o.optInt("retries", 0)
                t.savedUri = o.optString("suri")
                t.savedPath = o.optString("spath")
                t.createdAt = o.optLong("created", System.currentTimeMillis())
                t.finishedAt = o.optLong("finished", 0L)
                t
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** UI ke liye safe snapshot. */
class DlRow(
    val id: Int,
    val name: String,
    val status: DlStatus,
    val done: Long,
    val total: Long,
    val speed: Double,
    val etaSec: Long,
    val conns: Int,
    val note: String,
    val error: String,
    val path: String,
    val mime: String,
    val savedUri: String,
    val retries: Int
)

// ═════════════════════════════════════════════════════════════════════════
// Engine
// ═════════════════════════════════════════════════════════════════════════
object DlEngine {

    const val CH_ID = "lion_downloads"
    const val CH_DONE = "lion_downloads_done"
    const val NOTIF_ID = 1201
    const val ACTION_PAUSE_ALL = "com.example.simplebrowser.DL_PAUSE_ALL"

    private const val PREF_TASKS = "dl_tasks"
    private const val PREF_NEXT_ID = "dl_next_id"
    private const val MIN_SEG = 2L * 1024L * 1024L
    private const val MAX_DONE_KEEP = 60

    private class StopEx : RuntimeException()
    private class FatalDl(msg: String) : IOException(msg)
    private class HttpFail(code: Int) : IOException("HTTP $code")
    private class Seg(val idx: Int, val start: Long, val end: Long, val file: File)
    private class Probe(val url: String, val total: Long, val rangeOk: Boolean, val mime: String?, val cd: String?)
    private class Resp(val conn: HttpURLConnection, val url: String)

    private val lock = Any()
    private val tasks = ArrayList<DlTask>()
    private var loaded = false
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private var tickerOn = false
    private var serviceUp = false
    private var wake: PowerManager.WakeLock? = null

    private val app: Context get() = appCtx!!

    // ── init / persistence ────────────────────────────────────────────────
    fun init(ctx: Context) {
        synchronized(lock) {
            if (loaded) return
            appCtx = ctx.applicationContext
            loaded = true
            load()
        }
    }

    private fun prefs(): SharedPreferences =
        app.getSharedPreferences(FloatingService.PREFS, Context.MODE_PRIVATE)

    private fun load() {
        val raw = prefs().getString(PREF_TASKS, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val t = DlTask.fromJson(arr.getJSONObject(i)) ?: continue
                if (t.status == DlStatus.RUNNING || t.status == DlStatus.QUEUED) {
                    t.status = DlStatus.PAUSED
                    t.note = "App band hui thi – Resume karo"
                }
                tasks.add(t)
            }
        } catch (e: Exception) {
        }
    }

    private fun save() {
        synchronized(lock) {
            var doneCount = tasks.count { it.status == DlStatus.DONE }
            val iter = tasks.iterator()
            while (doneCount > MAX_DONE_KEEP && iter.hasNext()) {
                val t = iter.next()
                if (t.status == DlStatus.DONE) {
                    iter.remove()
                    doneCount--
                }
            }
            val arr = JSONArray()
            for (t in tasks) arr.put(t.toJson())
            prefs().edit().putString(PREF_TASKS, arr.toString()).apply()
        }
    }

    private fun nextId(): Int {
        val n = prefs().getInt(PREF_NEXT_ID, 1)
        prefs().edit().putInt(PREF_NEXT_ID, n + 1).apply()
        return n
    }

    private fun find(id: Int): DlTask? = tasks.firstOrNull { it.id == id }

    fun toast(msg: String) {
        main.post {
            try { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() } catch (e: Exception) { }
        }
    }

    // ── public API ────────────────────────────────────────────────────────
    /** Queue me daalo. false = URL valid nahi. */
    fun add(ctx: Context, spec: DlSpec, wifiOnly: Boolean): Boolean {
        init(ctx)
        val scheme = try { Uri.parse(spec.url).scheme?.lowercase() } catch (e: Exception) { null }
        if (scheme != "http" && scheme != "https") return false
        // Server folder set hai to link seedha server ko: file phone par aati hi nahi. Fail ho to phone par.
        if (ServerSync.direct(ctx)) {
            ServerSync.sendDirect(ctx, spec, "auto") { addLocal(ctx, spec, wifiOnly) }
            return true
        }
        return addLocal(ctx, spec, wifiOnly)
    }

    private fun addLocal(ctx: Context, spec: DlSpec, wifiOnly: Boolean): Boolean {
        init(ctx)
        synchronized(lock) {
            val t = DlTask(nextId(), spec, wifiOnly)
            t.name = Downloader.cleanName(spec.name)
            t.status = DlStatus.QUEUED
            tasks.add(t)
            save()
        }
        pump()
        return true
    }

    fun pokeQueue(ctx: Context) {
        init(ctx)
        pump()
    }

    fun pause(id: Int) {
        var toClose: DlTask? = null
        synchronized(lock) {
            val t = find(id) ?: return
            if (t.status == DlStatus.RUNNING) {
                t.stop = 1
                t.status = DlStatus.PAUSED
                t.note = ""
                toClose = t
            } else if (t.status == DlStatus.QUEUED) {
                t.status = DlStatus.PAUSED
            }
        }
        toClose?.let { closeConns(it) }
        save()
        pump()
    }

    fun resume(id: Int) {
        synchronized(lock) {
            val t = find(id) ?: return
            if (t.status == DlStatus.PAUSED || t.status == DlStatus.FAILED) {
                t.status = DlStatus.QUEUED
                t.error = ""
                t.note = ""
            }
        }
        save()
        pump()
    }

    /** Cancel / list se hatao. Adhuri files delete, poori ho chuki file Downloads me rehti hai. */
    fun remove(id: Int) {
        var removed: DlTask? = null
        synchronized(lock) {
            val t = find(id)
            if (t != null) {
                t.stop = 2
                tasks.remove(t)
                removed = t
            }
        }
        val x = removed ?: return
        closeConns(x)
        if (x.status != DlStatus.DONE) deleteParts(x)
        save()
        pump()
    }

    /** List se hatao aur Downloads me saved file bhi delete karo. */
    fun removeWithFile(id: Int) {
        var found: DlTask? = null
        synchronized(lock) { found = find(id) }
        val x = found
        if (x != null && x.status == DlStatus.DONE && x.savedUri.isNotEmpty()) {
            try {
                if (x.savedUri.startsWith("content://")) {
                    app.contentResolver.delete(Uri.parse(x.savedUri), null, null)
                } else {
                    File(x.savedUri).delete()
                }
            } catch (e: Exception) {
            }
        }
        remove(id)
    }

    fun pauseAll() {
        val ids = synchronized(lock) {
            tasks.filter { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }.map { it.id }
        }
        for (i in ids) pause(i)
    }

    fun resumeAll() {
        val ids = synchronized(lock) {
            tasks.filter { it.status == DlStatus.PAUSED || it.status == DlStatus.FAILED }.map { it.id }
        }
        for (i in ids) resume(i)
    }

    fun clearDone() {
        synchronized(lock) { tasks.removeAll { it.status == DlStatus.DONE } }
        save()
    }

    fun hasActive(): Boolean = synchronized(lock) {
        tasks.any { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }
    }

    fun snapshot(): List<DlRow> {
        val list = ArrayList<DlRow>()
        synchronized(lock) {
            for (t in tasks) {
                val done = t.bytes.get()
                val eta = if (t.status == DlStatus.RUNNING && t.total > 0 && t.speed > 1.0)
                    ((t.total - done) / t.speed).toLong() else -1L
                var note = t.note
                if (t.mergeDone >= 0 && t.status == DlStatus.RUNNING) {
                    note = "💾 File save ho rahi hai… " + DlFmt.size(t.mergeDone)
                }
                list.add(
                    DlRow(
                        t.id, t.name, t.status, done, t.total, t.speed, eta, t.conns, note,
                        t.error, t.savedPath, t.mime, t.savedUri, t.retries
                    )
                )
            }
        }
        fun rank(s: DlStatus) = when (s) {
            DlStatus.RUNNING -> 0
            DlStatus.QUEUED -> 1
            DlStatus.PAUSED -> 2
            DlStatus.FAILED -> 3
            DlStatus.DONE -> 4
        }
        return list.sortedWith(compareBy<DlRow> { rank(it.status) }.thenByDescending { it.id })
    }

    /** blob:/data: se aayi file (pehle se device par temp me) ko Downloads/Lion me save karo. */
    fun saveLocalFile(ctx: Context, src: File, rawName: String, mime0: String?): Boolean {
        init(ctx)
        try {
            val name = DlStorage.ensureExt(Downloader.cleanName(rawName), mime0)
            val mime = DlStorage.mimeFor(name, mime0)
            val cat = if (DlSettings.byType(app)) DlStorage.categoryOf(name, mime) else null
            val out = DlStorage.open(app, name, mime, cat)
            try {
                FileInputStream(src).use { ins -> ins.copyTo(out.stream, 128 * 1024) }
                out.finish()
            } catch (e: Throwable) {
                out.abort()
                throw e
            }
            val size = src.length()
            synchronized(lock) {
                val t = DlTask(nextId(), DlSpec("local", "", "", "", name, mime), false)
                t.name = name
                t.mime = mime
                t.total = size
                t.bytes.set(size)
                t.status = DlStatus.DONE
                t.finishedAt = System.currentTimeMillis()
                t.savedUri = out.uri
                t.savedPath = out.display
                tasks.add(t)
                save()
                notifyDone(t)
                ServerSync.onSaved(app, t)
            }
            toast("Save ho gaya: " + out.display)
            return true
        } catch (e: Throwable) {
            toast("Save nahi ho paya: " + (e.message ?: e.javaClass.simpleName))
            return false
        } finally {
            try { src.delete() } catch (e: Exception) { }
        }
    }

    // ── scheduler ─────────────────────────────────────────────────────────
    private fun pump() {
        val start = ArrayList<DlTask>()
        synchronized(lock) {
            var running = tasks.count { it.status == DlStatus.RUNNING }
            val max = DlSettings.parallel(app)
            for (t in tasks) {
                if (running >= max) break
                if (t.status == DlStatus.QUEUED && !t.workerActive) {
                    t.status = DlStatus.RUNNING
                    t.stop = 0
                    t.firstError = null
                    t.workerActive = true
                    t.error = ""
                    t.note = ""
                    t.mergeDone = -1L
                    running++
                    start.add(t)
                }
            }
        }
        for (t in start) pool.execute { runTask(t) }
        main.post { infra() }
    }

    private fun runTask(t: DlTask) {
        try {
            doRun(t)
        } catch (e: StopEx) {
            // pause / cancel: status pehle hi set ho chuka hai
        } catch (e: Throwable) {
            failTask(t, e)
        } finally {
            synchronized(lock) { t.workerActive = false }
            if (t.stop == 2) deleteParts(t)
            if (t.stop == 1) save()
            pump()
        }
    }

    private fun shortErr(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return if (m.length > 90) m.take(90) + "…" else m
    }

    private fun failTask(t: DlTask, e: Throwable) {
        var changed = false
        synchronized(lock) {
            if (t.status == DlStatus.RUNNING && t.stop != 1 && t.stop != 2) {
                t.status = DlStatus.FAILED
                t.error = shortErr(e)
                t.note = ""
                t.mergeDone = -1L
                changed = true
            }
        }
        if (changed) {
            save()
            notifyFail(t)
        }
    }

    // ── main download flow ────────────────────────────────────────────────
    private fun doRun(t: DlTask) {
        val maxRetry = if (DlSettings.retryOn(app)) DlSettings.retryMax(app) else 0
        t.note = "Connect ho raha hai…"
        val p = withRetry(t, maxRetry) { probe(t) }
        t.url = p.url

        // manual URL se aayi download ka naam server ke hisaab se sudhaaro
        if (t.spec.mime.isEmpty()) {
            val hasCd = p.cd?.contains("filename", ignoreCase = true) == true
            if (hasCd || !t.name.contains('.')) {
                t.name = Downloader.cleanName(URLUtil.guessFileName(p.url, p.cd, p.mime))
            }
            if (!p.mime.isNullOrEmpty()) t.mime = p.mime
        }
        t.name = DlStorage.ensureExt(t.name, t.mime)

        var hadParts = hasParts(t)
        if (hadParts && (t.total != p.total || !p.rangeOk || t.conns <= 0)) {
            deleteParts(t)
            t.bytes.set(0L)
            hadParts = false
        }
        t.total = p.total
        t.rangeOk = p.rangeOk
        if (!hadParts) {
            t.conns = if (p.rangeOk && p.total > 0) {
                minOf(DlSettings.conns(app), maxOf(1L, p.total / MIN_SEG).toInt())
            } else 1
        }

        val segs = layout(t)
        var have = 0L
        for (s in segs) have += s.file.length()
        t.bytes.set(have)

        // jagah check (merge ke liye ek part jitni extra)
        if (p.total > 0) {
            val remaining = p.total - have
            val extra = p.total / maxOf(1, t.conns)
            if (remaining > 0) {
                val avail = try {
                    StatFs(partsDir().path).availableBytes
                } catch (e: Exception) {
                    Long.MAX_VALUE
                }
                if (avail < remaining + extra + 64L * 1024L * 1024L) throw FatalDl("Storage kam hai")
            }
        }
        synchronized(lock) { save() }

        t.lastBytes = have
        t.lastTime = System.currentTimeMillis()
        t.speed = 0.0
        t.note = ""

        val futures = ArrayList<Future<*>>()
        for (s in segs) {
            futures.add(pool.submit(Runnable {
                try {
                    downloadSeg(t, s, maxRetry)
                } catch (e: StopEx) {
                    // pause / cancel / dusre segment ki error
                } catch (e: Throwable) {
                    abortTask(t, e)
                }
            }))
        }
        for (f in futures) {
            try { f.get() } catch (e: Exception) { }
        }

        when (t.stop) {
            1, 2 -> throw StopEx()
            3 -> throw (t.firstError ?: IOException("Download fail ho gaya"))
        }

        var got = 0L
        for (s in segs) got += s.file.length()
        if (t.total > 0 && got != t.total) {
            if (got > t.total) deleteParts(t)
            throw IOException("File adhuri (${DlFmt.size(got)} / ${DlFmt.size(t.total)}) – Retry karo")
        }

        // merge + final save
        t.note = ""
        t.mergeDone = 0L
        val mime = DlStorage.mimeFor(t.name, t.mime)
        val cat = if (DlSettings.byType(app)) DlStorage.categoryOf(t.name, mime) else null
        val out = DlStorage.open(app, t.name, mime, cat)
        try {
            val buf = ByteArray(256 * 1024)
            var copied = 0L
            for (s in segs) {
                if (s.file.exists()) {
                    FileInputStream(s.file).use { ins ->
                        while (true) {
                            if (t.stop == 2) throw StopEx()
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.stream.write(buf, 0, n)
                            copied += n
                            t.mergeDone = copied
                        }
                    }
                    s.file.delete()
                }
            }
            out.finish()
        } catch (e: Throwable) {
            out.abort()
            t.mergeDone = -1L
            throw e
        }

        synchronized(lock) {
            t.status = DlStatus.DONE
            t.finishedAt = System.currentTimeMillis()
            t.savedUri = out.uri
            t.savedPath = out.display
            t.mime = mime
            t.note = ""
            t.mergeDone = -1L
            t.speed = 0.0
        }
        save()
        notifyDone(t)
        ServerSync.onSaved(app, t)
    }

    private fun abortTask(t: DlTask, e: Throwable) {
        synchronized(lock) {
            if (t.stop == 0) {
                t.stop = 3
                t.firstError = e
            }
        }
        closeConns(t)
    }

    private fun closeConns(t: DlTask) {
        val list = ArrayList<HttpURLConnection>(t.openConns)
        pool.execute {
            for (c in list) {
                try { c.disconnect() } catch (e: Exception) { }
            }
        }
    }

    // ── parts ─────────────────────────────────────────────────────────────
    private fun partsDir(): File {
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        val d = File(base, "dlparts")
        d.mkdirs()
        return d
    }

    private fun hasParts(t: DlTask): Boolean {
        val l = partsDir().listFiles { f -> f.name.startsWith("t${t.id}_") }
        return l != null && l.isNotEmpty()
    }

    private fun deleteParts(t: DlTask) {
        try {
            val l = partsDir().listFiles { f -> f.name.startsWith("t${t.id}_") }
            if (l != null) for (f in l) f.delete()
        } catch (e: Exception) {
        }
    }

    private fun layout(t: DlTask): List<Seg> {
        val dir = partsDir()
        if (!t.rangeOk || t.total <= 0 || t.conns <= 1) {
            val end = if (t.total > 0) t.total - 1 else -1L
            return listOf(Seg(0, 0L, end, File(dir, "t${t.id}_0.part")))
        }
        val n = t.conns
        val size = t.total / n
        val list = ArrayList<Seg>()
        for (i in 0 until n) {
            val s = i * size
            val e = if (i == n - 1) t.total - 1 else s + size - 1
            list.add(Seg(i, s, e, File(dir, "t${t.id}_$i.part")))
        }
        return list
    }

    // ── http ──────────────────────────────────────────────────────────────
    private fun failFor(code: Int): IOException =
        if (code == 408 || code == 429 || code >= 500) HttpFail(code) else FatalDl("HTTP $code")

    private fun openFollow(t: DlTask, startUrl: String, range: String?): Resp {
        var cur = startUrl
        var hops = 0
        while (true) {
            val u = URL(cur)
            val proto = u.protocol.lowercase()
            if (proto != "http" && proto != "https") throw FatalDl("Sirf http/https link chalte hain")
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (t.spec.ua.isNotEmpty()) c.setRequestProperty("User-Agent", t.spec.ua)
            if (t.spec.referer.isNotEmpty()) c.setRequestProperty("Referer", t.spec.referer)
            var cookie: String? = null
            try { cookie = CookieManager.getInstance().getCookie(cur) } catch (e: Exception) { }
            if (cookie.isNullOrEmpty() && hops == 0) cookie = t.spec.cookie
            if (!cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", cookie)
            if (range != null) c.setRequestProperty("Range", range)
            t.openConns.add(c)
            try {
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val loc = c.getHeaderField("Location")
                    t.openConns.remove(c)
                    c.disconnect()
                    hops++
                    if (loc == null || hops > 8) throw FatalDl("Redirect me gadbad")
                    cur = URL(u, loc).toString()
                    continue
                }
                return Resp(c, cur)
            } catch (e: Exception) {
                t.openConns.remove(c)
                try { c.disconnect() } catch (x: Exception) { }
                throw e
            }
        }
    }

    private fun probe(t: DlTask): Probe {
        val r = openFollow(t, t.spec.url, "bytes=0-0")
        try {
            val c = r.conn
            val code = c.responseCode
            val mime = c.contentType?.substringBefore(';')?.trim()
            val cd = c.getHeaderField("Content-Disposition")
            return when (code) {
                206 -> {
                    val cr = c.getHeaderField("Content-Range")
                    val total = cr?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                    Probe(r.url, total, total > 0, mime, cd)
                }
                200 -> Probe(r.url, c.contentLengthLong, false, mime, cd)
                else -> throw failFor(code)
            }
        } finally {
            t.openConns.remove(r.conn)
            try { r.conn.disconnect() } catch (e: Exception) { }
        }
    }

    private fun <T> withRetry(t: DlTask, maxRetry: Int, block: () -> T): T {
        var attempt = 0
        while (true) {
            if (t.stop != 0) throw StopEx()
            waitNet(t)
            try {
                return block()
            } catch (e: StopEx) {
                throw e
            } catch (e: FatalDl) {
                throw e
            } catch (e: IOException) {
                if (t.stop != 0) throw StopEx()
                attempt++
                if (attempt > maxRetry) throw e
                t.retries++
                backoff(t, attempt, maxRetry, e)
            }
        }
    }

    private fun downloadSeg(t: DlTask, seg: Seg, maxRetry: Int) {
        var attempt = 0
        while (true) {
            if (t.stop != 0) throw StopEx()
            var have = seg.file.length()
            if (seg.end >= 0 && seg.start + have >= seg.end + 1) return   // segment poora
            if (!t.rangeOk && have > 0) {                                 // resume nahi ho sakta
                t.bytes.addAndGet(-have)
                seg.file.delete()
                have = 0L
            }
            waitNet(t)
            var wrote = 0L
            var conn: HttpURLConnection? = null
            try {
                val from = seg.start + have
                val range: String? = if (t.rangeOk)
                    "bytes=$from-" + (if (seg.end >= 0) seg.end.toString() else "")
                else null
                val r = openFollow(t, t.url, range)
                conn = r.conn
                val code = conn.responseCode
                if (range != null) {
                    if (code != 206 && !(code == 200 && from == 0L)) {
                        if (code == 200 || code == 416) throw FatalDl("Server range support nahi de raha")
                        throw failFor(code)
                    }
                } else if (code != 200) {
                    throw failFor(code)
                }
                val ins = conn.inputStream
                FileOutputStream(seg.file, true).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var pos = from
                    while (true) {
                        if (t.stop != 0) throw StopEx()
                        val n = ins.read(buf)
                        if (n < 0) break
                        var len = n
                        var last = false
                        if (seg.end >= 0 && pos + len > seg.end + 1) {
                            len = (seg.end + 1 - pos).toInt()
                            last = true
                        }
                        if (len > 0) {
                            out.write(buf, 0, len)
                            pos += len
                            wrote += len
                            t.bytes.addAndGet(len.toLong())
                        }
                        if (last || (seg.end >= 0 && pos >= seg.end + 1)) break
                    }
                }
                val nowHave = seg.file.length()
                val expected = if (seg.end >= 0) seg.end + 1 - seg.start else t.total
                if (expected > 0 && nowHave < expected) throw IOException("Connection beech me toota")
                return
            } catch (e: StopEx) {
                throw e
            } catch (e: FatalDl) {
                throw e
            } catch (e: IOException) {
                if (t.stop != 0) throw StopEx()
                if (wrote > 0) attempt = 0
                attempt++
                if (attempt > maxRetry) throw e
                t.retries++
                backoff(t, attempt, maxRetry, e)
            } finally {
                if (conn != null) {
                    t.openConns.remove(conn)
                    try { conn.disconnect() } catch (e: Exception) { }
                }
            }
        }
    }

    private fun backoff(t: DlTask, attempt: Int, max: Int, e: Exception) {
        val waitMs = minOf(30_000L, 2_000L * (1L shl minOf(attempt - 1, 4)))
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            if (t.stop != 0) throw StopEx()
            val left = until - System.currentTimeMillis()
            if (left <= 0) break
            t.note = "🔁 Retry $attempt/$max · ${(left + 999) / 1000}s baad · ${shortErr(e)}"
            try {
                Thread.sleep(minOf(left, 250L))
            } catch (ie: InterruptedException) {
                throw StopEx()
            }
        }
        t.note = ""
    }

    private fun isOnline(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val c = if (n != null) cm.getNetworkCapabilities(n) else null
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true
    }

    private fun isMetered(): Boolean = try {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (e: Exception) {
        false
    }

    /** Internet / Wi-Fi aane tak ruko (retry count nahi ghatta). */
    private fun waitNet(t: DlTask) {
        var waited = false
        while (true) {
            if (t.stop != 0) throw StopEx()
            val online = isOnline()
            val wifiBlock = t.wifiOnly && online && isMetered()
            if (online && !wifiBlock) break
            waited = true
            t.note = if (!online) "📶 Internet ka intezaar…" else "📶 Wi-Fi ka intezaar…"
            try {
                Thread.sleep(1000)
            } catch (ie: InterruptedException) {
                throw StopEx()
            }
        }
        if (waited) t.note = ""
    }

    // ── service / ticker / wakelock ───────────────────────────────────────
    private fun infra() {
        if (hasActive()) {
            if (!serviceUp) {
                serviceUp = true
                try {
                    ContextCompat.startForegroundService(app, Intent(app, DlService::class.java))
                } catch (e: Exception) {
                    // background se start na ho paye to bhi download threads chalte rahenge
                }
            }
            acquireWake()
            if (!tickerOn) {
                tickerOn = true
                main.postDelayed(tick, 1000)
            }
        } else {
            releaseWake()
            if (serviceUp) {
                serviceUp = false
                try { app.stopService(Intent(app, DlService::class.java)) } catch (e: Exception) { }
            }
            try { nm().cancel(NOTIF_ID) } catch (e: Exception) { }
        }
    }

    fun onServiceGone() {
        serviceUp = false
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                for (t in tasks) {
                    if (t.status == DlStatus.RUNNING) {
                        val b = t.bytes.get()
                        val dt = maxOf(1L, now - t.lastTime)
                        val inst = (b - t.lastBytes) * 1000.0 / dt
                        t.speed = if (t.speed <= 0.0) inst else t.speed * 0.7 + inst * 0.3
                        t.lastBytes = b
                        t.lastTime = now
                    }
                }
            }
            try { nm().notify(NOTIF_ID, buildSummary(app)) } catch (e: Exception) { }
            if (hasActive()) main.postDelayed(this, 1000) else tickerOn = false
        }
    }

    private fun acquireWake() {
        try {
            if (wake == null) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lion:downloads")
            }
            val w = wake
            if (w != null && !w.isHeld) w.acquire(3L * 60L * 60L * 1000L)
        } catch (e: Exception) {
        }
    }

    private fun releaseWake() {
        try {
            val w = wake
            if (w != null && w.isHeld) w.release()
        } catch (e: Exception) {
        }
    }

    // ── notifications ─────────────────────────────────────────────────────
    private fun nm(): NotificationManager =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = ctx.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(
                NotificationChannel(CH_ID, "Lion downloads (progress)", NotificationManager.IMPORTANCE_LOW)
            )
            m.createNotificationChannel(
                NotificationChannel(CH_DONE, "Lion downloads (done)", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun buildSummary(ctx: Context): Notification {
        ensureChannels(ctx)
        val running = ArrayList<DlRow>()
        var queued = 0
        for (r in snapshot()) {
            if (r.status == DlStatus.RUNNING) running.add(r)
            else if (r.status == DlStatus.QUEUED) queued++
        }
        var totalSpeed = 0.0
        var sumDone = 0L
        var sumTotal = 0L
        for (r in running) {
            totalSpeed += r.speed
            if (r.total > 0) {
                sumDone += r.done
                sumTotal += r.total
            }
        }
        val title: String
        val text: String
        if (running.size == 1) {
            val r = running[0]
            val pct = if (r.total > 0) " · ${(r.done * 100 / r.total)}%" else ""
            title = r.name
            text = DlFmt.size(r.done) + pct + " · " + DlFmt.speed(r.speed) +
                (if (r.etaSec >= 0) " · ETA " + DlFmt.eta(r.etaSec) else "") +
                (if (queued > 0) " · +$queued queue me" else "")
        } else if (running.size > 1) {
            title = "${running.size} downloads chal rahe hain"
            text = DlFmt.speed(totalSpeed) + (if (queued > 0) " · +$queued queue me" else "")
        } else {
            title = "Lion downloads"
            text = if (queued > 0) "$queued queue me" else "Taiyaar"
        }
        val openPi = PendingIntent.getActivity(
            ctx, 11, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = Intent(ctx, DlService::class.java).setAction(ACTION_PAUSE_ALL)
        val pausePi = PendingIntent.getService(
            ctx, 12, pauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Sab roko", pausePi)
        if (sumTotal > 0) b.setProgress(1000, (sumDone * 1000 / sumTotal).toInt(), false)
        else b.setProgress(0, 0, running.isNotEmpty())
        return b.build()
    }

    private fun notifyDone(t: DlTask) {
        try {
            ensureChannels(app)
            val b = NotificationCompat.Builder(app, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download ho gaya")
                .setContentText(t.name)
                .setAutoCancel(true)
            val vi = DlStorage.viewIntent(app, t.savedUri, t.mime)
            if (vi != null) {
                b.setContentIntent(
                    PendingIntent.getActivity(
                        app, 3000 + t.id, vi,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
            nm().notify(3000 + t.id, b.build())
        } catch (e: Exception) {
        }
    }

    private fun notifyFail(t: DlTask) {
        try {
            ensureChannels(app)
            val b = NotificationCompat.Builder(app, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download fail: ${t.name}")
                .setContentText(t.error + " (Lion → Settings → Download queue me Retry)")
                .setAutoCancel(true)
            nm().notify(3000 + t.id, b.build())
        } catch (e: Exception) {
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Foreground service: downloads ke dauran process zinda rakhta hai
// ═════════════════════════════════════════════════════════════════════════
class DlService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DlEngine.init(this)
        if (intent?.action == DlEngine.ACTION_PAUSE_ALL) DlEngine.pauseAll()
        val n = DlEngine.buildSummary(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(DlEngine.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(DlEngine.NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!DlEngine.hasActive()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DlEngine.onServiceGone()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        super.onDestroy()
    }
}
