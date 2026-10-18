package com.example.simplebrowser

import android.content.Context
import android.net.Uri
import android.util.Xml
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Credentials
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Download hote hi file ko server (AIC Disk /dav/ ya HF Bucket /hf/) ke chune hue folder me bhejna (WebDAV PUT).
 * MyFiles app jaisa hi login: URL + username + password.
 */
object ServerSync {

    const val K_STORE = "ss_store"      // off | aic | hf
    const val K_URL = "ss_url"
    const val K_USER = "ss_user"
    const val K_PASS = "ss_pass"
    const val K_PATH = "ss_path"        // jaise "/video/punjabi"
    const val K_DELETE = "ss_delete"
    const val K_DIRECT = "ss_direct"    // true = link server ko do, server khud download kare (phone me file nahi aati)
    const val DEFAULT_URL = "https://u8cyy.aiccloud.online/dav/"

    private val io = Executors.newFixedThreadPool(1)
    private val net = Executors.newCachedThreadPool()

    fun store(ctx: Context): String = DlSettings.getStr(ctx, K_STORE, "off")
    fun enabled(ctx: Context): Boolean = store(ctx) != "off" && DlSettings.getStr(ctx, K_USER, "").isNotEmpty()
    fun path(ctx: Context): String = DlSettings.getStr(ctx, K_PATH, "/")
    private fun baseUrl(ctx: Context): String = Dav.slash(DlSettings.getStr(ctx, K_URL, DEFAULT_URL).trim())

    private fun client(ctx: Context): OkHttpClient {
        val host = baseUrl(ctx).toHttpUrlOrNull()?.host
        val auth = Credentials.basic(DlSettings.getStr(ctx, K_USER, ""), DlSettings.getStr(ctx, K_PASS, ""))
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val r = chain.request()
                if (host != null && r.url.host == host) chain.proceed(r.newBuilder().header("Authorization", auth).build())
                else chain.proceed(r)
            })
            .build()
    }

    /** Storage ka root URL: AIC = .../dav/, HF = .../hf/ (login URL se). */
    fun root(ctx: Context, store: String): String {
        val b = baseUrl(ctx)
        val parent = when {
            b.endsWith("/dav/") -> b.dropLast(4)
            b.endsWith("/hf/") -> b.dropLast(3)
            else -> {
                val u = b.toHttpUrlOrNull()
                if (u == null) b else u.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
            }
        }
        return parent + if (store == "hf") "hf/" else "dav/"
    }

    private fun urlOf(ctx: Context, store: String, path: String): String {
        val sb = StringBuilder(root(ctx, store))
        for (seg in path.trim('/').split('/').filter { it.isNotEmpty() }) sb.append(Uri.encode(seg)).append('/')
        return sb.toString()
    }


    // ── DIRECT: server khud download kare (phone sirf link + cookie bhejta hai) ──
    fun direct(ctx: Context): Boolean = enabled(ctx) && DlSettings.getBool(ctx, K_DIRECT, true)

    private fun xferBase(ctx: Context, store: String): String {
        val r = root(ctx, store)
        return r.substring(0, r.length - (if (store == "hf") 3 else 4)) + "xfer/"
    }

    private fun jsonBody(o: JSONObject) = o.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    /**
     * Link server ko bhejo (POST /xfer/dl). Server bhejne me ya download me fail ho to [onFail] chalta hai
     * (purana tareeka: phone par download, phir upload), taaki kuch kho na jaye.
     * kind: "auto" | "file" | "hls" | "ytdlp"
     */
    fun sendDirect(ctx: Context, spec: DlSpec, kind: String, onFail: () -> Unit) {
        val app = ctx.applicationContext
        val store = store(app)
        val path = path(app)
        net.execute {
            var id = ""
            try {
                val body = JSONObject()
                    .put("url", spec.url).put("name", spec.name).put("cookie", spec.cookie)
                    .put("ua", spec.ua).put("referer", spec.referer).put("kind", kind)
                    .put("on_conflict", "rename")
                    .put("dest", JSONObject().put("store", store).put("path", path))
                val req = Request.Builder().url(xferBase(app, store) + "dl").post(jsonBody(body)).build()
                client(app).newCall(req).execute().use { r ->
                    val txt = r.body?.string() ?: ""
                    if (r.code == 401) throw IOException("Username / password galat")
                    if (!r.isSuccessful) {
                        val m = try { JSONObject(txt).optString("error") } catch (e: Exception) { "" }
                        throw IOException(if (m.isNotEmpty()) m else "HTTP ${r.code}")
                    }
                    id = JSONObject(txt).optString("id")
                }
                if (id.isEmpty()) throw IOException("server ne id nahi di")
            } catch (e: Throwable) {
                DlEngine.toast("☁ Server ko link nahi bheja, phone par utar rahe hain: " + (e.message ?: e.javaClass.simpleName))
                onFail()
                return@execute
            }
            DlEngine.toast("☁ Server par download shuru: " + spec.name)
            pollDirect(app, store, id, spec.name, onFail)
        }
    }


    private class HttpErr(val code: Int, msg: String) : IOException(msg)

    private fun postDl(app: Context, store: String, path: String, spec: DlSpec, kind: String): String {
        val body = JSONObject()
            .put("url", spec.url).put("name", spec.name).put("cookie", spec.cookie)
            .put("ua", spec.ua).put("referer", spec.referer).put("kind", kind)
            .put("on_conflict", "rename")
            .put("dest", JSONObject().put("store", store).put("path", path))
        val req = Request.Builder().url(xferBase(app, store) + "dl").post(jsonBody(body)).build()
        client(app).newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            if (r.code == 401) throw HttpErr(401, "Username / password galat")
            if (!r.isSuccessful) {
                val m = try { JSONObject(txt).optString("error") } catch (e: Exception) { "" }
                throw HttpErr(r.code, if (m.isNotEmpty()) m else "HTTP ${r.code}")
            }
            val id = JSONObject(txt).optString("id")
            if (id.isEmpty()) throw IOException("server ne id nahi di")
            return id
        }
    }

    /**
     * Kai video-page links ek saath (yt-dlp mode). Server ki queue bhari ho (HTTP 429) to 10 sec ruk ke dobara.
     * Phone par fallback NAHI (page link phone se download nahi ho sakta). Aakhir me summary toast.
     */
    fun sendBulk(ctx: Context, specs: List<DlSpec>) {
        val app = ctx.applicationContext
        val store = store(app)
        val path = path(app)
        net.execute {
            val ids = ArrayList<String>()
            val nameOf = HashMap<String, String>()
            var rejected = 0
            var lastErr = ""
            DlEngine.toast("☁ " + specs.size + " video server ko bhej rahe hain…")
            for (spec in specs) {
                var tries = 0
                while (true) {
                    try {
                        val id = postDl(app, store, path, spec, "ytdlp")
                        ids.add(id); nameOf[id] = spec.name
                        break
                    } catch (e: HttpErr) {
                        if (e.code == 429 && ++tries <= 360) {
                            try { Thread.sleep(10_000) } catch (x: InterruptedException) { return@execute }
                            continue
                        }
                        rejected++; lastErr = e.message ?: ""
                        if (e.code == 401) { DlEngine.toast("☁ " + lastErr); return@execute }
                        break
                    } catch (e: Throwable) {
                        rejected++; lastErr = e.message ?: e.javaClass.simpleName
                        break
                    }
                }
            }
            if (ids.isEmpty()) {
                DlEngine.toast("☁ Server ne koi video nahi liya: $lastErr")
                return@execute
            }
            DlEngine.toast("☁ " + ids.size + " video server ki queue me" + (if (rejected > 0) " ($rejected nahi gaye)" else "") + ". Ho jane par batayenge.")
            val want = HashSet(ids)
            var reported = 0
            var bad = 0
            val end = System.currentTimeMillis() + 12L * 3600 * 1000
            while (System.currentTimeMillis() < end) {
                try { Thread.sleep(10_000) } catch (e: InterruptedException) { return@execute }
                try {
                    val req = Request.Builder().url(xferBase(app, store) + "list").build()
                    client(app).newCall(req).execute().use { r ->
                        val txt = r.body?.string() ?: ""
                        if (!r.isSuccessful) { bad++; return@use }
                        bad = 0
                        val jobs = JSONObject(txt).optJSONArray("jobs") ?: return@use
                        var done = 0; var failed = 0; var removed = 0; var open = 0
                        var firstFail = ""
                        for (i in 0 until jobs.length()) {
                            val j = jobs.optJSONObject(i) ?: continue
                            val id = j.optString("id")
                            if (id !in want) continue
                            when (j.optString("state")) {
                                "done" -> done++
                                "error", "cancelled" -> {
                                    val errs = j.optJSONArray("errors")
                                    val why = if (errs != null && errs.length() > 0) errs.optString(0) else j.optString("state")
                                    if (why.startsWith("[removed]")) {
                                        removed++          // site se hata di gayi / private / country-lock: skip, agle par
                                    } else {
                                        failed++
                                        if (firstFail.isEmpty()) firstFail = (nameOf[id] ?: "") + ": " + why
                                    }
                                }
                                else -> open++
                            }
                        }
                        val seen = done + failed + removed
                        if (open == 0 && seen >= want.size - 0) {
                            DlEngine.toast("☁ Server par: $done ho gaye" +
                                (if (removed > 0) ", $removed site se hata di gayi (skip)" else "") +
                                (if (failed > 0) ", $failed fail ($firstFail)" else ""))
                            return@execute
                        }
                        if (done >= reported + 5) { reported = done; DlEngine.toast("☁ $done / ${want.size} ho gaye") }
                    }
                } catch (e: Throwable) { bad++ }
                if (bad >= 30) return@execute
            }
        }
    }

    private fun pollDirect(app: Context, store: String, id: String, name: String, onFail: () -> Unit) {
        var bad = 0
        val end = System.currentTimeMillis() + 12L * 3600 * 1000
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(3000) } catch (e: InterruptedException) { return }
            try {
                val req = Request.Builder().url(xferBase(app, store) + "status?id=" + Uri.encode(id)).build()
                client(app).newCall(req).execute().use { r ->
                    val txt = r.body?.string() ?: ""
                    if (r.code == 404) { bad = 99; return@use }        // server restart: job chali gayi
                    if (!r.isSuccessful) { bad++; return@use }
                    bad = 0
                    val j = JSONObject(txt)
                    when (j.optString("state")) {
                        "done" -> {
                            val res = j.optString("result")
                            DlEngine.toast("☁ Server par ho gaya: " + (if (res.isNotEmpty()) res else name))
                            return
                        }
                        "cancelled" -> return
                        "error" -> {
                            val errs = j.optJSONArray("errors")
                            val m = if (errs != null && errs.length() > 0) errs.optString(0) else "error"
                            if (m.startsWith("[removed]")) {
                                DlEngine.toast("☁ Video site se hata di gayi ya available nahi: $name")
                            } else {
                                DlEngine.toast("☁ Server se download nahi hua ($m), phone par try kar rahe hain")
                                onFail()
                            }
                            return
                        }
                    }
                }
            } catch (e: Throwable) { bad++ }
            if (bad >= 20) return   // net gaya / server restart: job server par chalti rahegi, bas ham khabar nahi de paye
        }
    }

    // ── folder list (picker ke liye) ──────────────────────────────────────
    private const val PROPFIND =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"

    /** Sirf subfolders ke naam. Error par IOException. */
    fun listFolders(ctx: Context, store: String, path: String): List<String> {
        val base = urlOf(ctx, store, path)
        val req = Request.Builder().url(base)
            .method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1").build()
        client(ctx).newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("Username / password galat")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val stream = r.body?.byteStream() ?: throw IOException("khaali jawab")
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(stream, null)
            val out = ArrayList<String>()
            var href = ""; var isDir = false; var idx = -1
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "response" -> { href = ""; isDir = false; idx++ }
                        "href" -> href = p.nextText().trim()
                        "collection" -> isDir = true
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "response" && idx > 0 && href.isNotEmpty() && isDir) {
                    val name = Uri.decode(href.trimEnd('/').substringAfterLast('/'))
                    if (name.isNotEmpty()) out.add(name)
                }
                ev = p.next()
            }
            return out.sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    fun mkdir(ctx: Context, store: String, path: String) {
        val req = Request.Builder().url(urlOf(ctx, store, path)).method("MKCOL", null).build()
        client(ctx).newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("Username / password galat")
            if (!r.isSuccessful && r.code != 405) throw IOException("HTTP ${r.code}")   // 405 = pehle se hai
        }
    }

    private fun ensureDirs(ctx: Context, store: String, path: String) {
        var cur = ""
        for (seg in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            cur += "/$seg"
            mkdir(ctx, store, cur)
        }
    }

    // ── upload ────────────────────────────────────────────────────────────
    private class FileBody(
        private val ctx: Context, private val src: String, private val len: Long, private val mime: String
    ) : RequestBody() {
        override fun contentType(): MediaType? = mime.toMediaTypeOrNull()
        override fun contentLength(): Long = len
        override fun writeTo(sink: BufferedSink) {
            val ins = if (src.startsWith("content://")) ctx.contentResolver.openInputStream(Uri.parse(src))
            else File(src).inputStream()
            (ins ?: throw IOException("file nahi khuli")).use { sink.writeAll(it.source()) }
        }
    }

    private fun sizeOf(ctx: Context, src: String): Long {
        return try {
            if (src.startsWith("content://"))
                ctx.contentResolver.openFileDescriptor(Uri.parse(src), "r")?.use { it.statSize } ?: -1L
            else File(src).length()
        } catch (e: Exception) { -1L }
    }

    /** DlEngine ne download poora hone par bulaya. Server band ho to kuch nahi karta. */
    fun onSaved(ctx: Context, t: DlTask) {
        if (!enabled(ctx) || t.savedUri.isEmpty()) return
        val app = ctx.applicationContext
        val src = t.savedUri
        val name = t.name
        val mime = t.mime.ifEmpty { "application/octet-stream" }
        val store = store(app)
        val path = path(app)
        io.execute {
            try {
                DlEngine.toast("☁ Server par bhej rahe hain: $name")
                ensureDirs(app, store, path)
                val len = sizeOf(app, src)
                val url = urlOf(app, store, path) + Uri.encode(name)
                val req = Request.Builder().url(url).put(FileBody(app, src, len, mime)).build()
                client(app).newCall(req).execute().use { r ->
                    if (r.code == 401) throw IOException("Username / password galat")
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                }
                val label = (if (store == "hf") "HF" else "AIC") + ":" + (if (path.startsWith("/")) path else "/$path")
                if (DlSettings.getBool(app, K_DELETE, true)) {
                    try {
                        if (src.startsWith("content://")) app.contentResolver.delete(Uri.parse(src), null, null)
                        else File(src).delete()
                    } catch (e: Exception) { }
                    t.savedUri = ""
                }
                t.savedPath = "☁ $label/$name"
                DlEngine.toast("☁ Server par ho gaya: $label/$name")
            } catch (e: Throwable) {
                DlEngine.toast("☁ Server upload fail (file phone par safe hai): " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }
}

object Dav {
    fun slash(u: String): String = if (u.endsWith("/")) u else "$u/"
}
