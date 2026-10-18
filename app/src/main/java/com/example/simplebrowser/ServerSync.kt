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
import org.json.JSONArray
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
            histAdd(app, id, spec.name, spec.url, store, path)
            DlEngine.toast("☁ Server par download shuru: " + spec.name)
            pollDirect(app, store, id, spec.name, onFail)
        }
    }



    fun qualityName(height: Int): String = when {
        height < 0 -> "sirf audio mp3"
        height == 0 -> "best quality"
        else -> height.toString() + "p tak"
    }

    /** Server se ek video ke quality options. */
    class FormatOpt(val h: Int, val ext: String, val fps: Int, val count: Int, val sampled: Int)

    /**
     * Chune hue videos me se pehle <=3 ke formats server (yt-dlp) se padho; heights ka union do.
     * [cb] background thread par chalta hai. Khaali list = padh nahi payi (caller standard ladder dikhaye).
     */
    fun fetchFormats(ctx: Context, specs: List<DlSpec>, cb: (List<FormatOpt>, Boolean) -> Unit) {
        val app = ctx.applicationContext
        val store = store(app)
        net.execute {
            val sample = specs.take(3)
            val futures = sample.map { spec ->
                net.submit(java.util.concurrent.Callable<JSONObject?> {
                    try {
                        val body = JSONObject().put("url", spec.url).put("cookie", spec.cookie)
                            .put("ua", spec.ua).put("referer", spec.referer)
                        val req = Request.Builder().url(xferBase(app, store) + "formats").post(jsonBody(body)).build()
                        client(app).newCall(req).execute().use { r ->
                            val txt = r.body?.string() ?: ""
                            if (r.isSuccessful) JSONObject(txt) else null
                        }
                    } catch (e: Throwable) { null }
                })
            }
            val got = futures.mapNotNull {
                try { it.get(60, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Throwable) { null }
            }
            val byH = java.util.TreeMap<Int, IntArray>()      // height -> [count, fps]
            val extOf = HashMap<Int, String>()
            var audio = false
            for (j in got) {
                if (j.optBoolean("audio")) audio = true
                val arr = j.optJSONArray("formats") ?: continue
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    val h = f.optInt("h")
                    if (h <= 0) continue
                    val cur = byH.getOrPut(h) { intArrayOf(0, 0) }
                    cur[0]++
                    cur[1] = maxOf(cur[1], f.optInt("fps"))
                    if (!extOf.containsKey(h)) extOf[h] = f.optString("ext")
                }
            }
            val list = byH.descendingMap().map { (h, v) -> FormatOpt(h, extOf[h] ?: "", v[1], v[0], got.size) }
            cb(list, audio || got.isEmpty())
        }
    }


    // ── Server downloads ka local record: jo link server ko bheje, unki state yaad rakhte hain ──
    // (server restart ho jaye ya purani job server se hat jaye tab bhi "ab tak ki downloads" me dikhti rehti hain)
    class SrvRow(
        val id: String, val name: String, val url: String, val store: String, val path: String,
        val t: Long, val state: String, val done: Long, val total: Long, val result: String, val error: String,
        val h: Int = 0, val tries: Int = 0
    ) {
        val active: Boolean get() = state == "queued" || state == "running"
        val removed: Boolean get() = error.startsWith("[removed]")
    }

    private const val K_HIST = "srv_hist"
    private val histLock = Any()

    private fun histLoad(app: Context): JSONArray =
        try { JSONArray(DlSettings.getStr(app, K_HIST, "[]")) } catch (e: Exception) { JSONArray() }

    private fun histSave(app: Context, a: JSONArray) {
        var arr = a
        if (arr.length() > 500) {            // sabse purani khatam hui entries hatao
            val keep = JSONArray()
            var drop = arr.length() - 500
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val st = o.optString("state")
                if (drop > 0 && st != "queued" && st != "running") { drop--; continue }
                keep.put(o)
            }
            arr = keep
        }
        DlSettings.setStr(app, K_HIST, arr.toString())
    }

    fun histAdd(app: Context, id: String, name: String, url: String, store: String, path: String, height: Int = 0, tries: Int = 0) {
        synchronized(histLock) {
            val a = histLoad(app)
            a.put(
                JSONObject().put("id", id).put("name", name.ifEmpty { url }).put("url", url)
                    .put("store", store).put("path", path).put("t", System.currentTimeMillis())
                    .put("state", "queued").put("done", 0).put("total", 0).put("result", "").put("err", "")
                    .put("h", height).put("tries", tries)
            )
            histSave(app, a)
        }
    }

    /** Dobara bhejne ki koshish fail hui: koshish ginti +1 aur samay abhi ka (agli koshish thodi der baad). */
    private fun histBump(app: Context, id: String) {
        synchronized(histLock) {
            val a = histLoad(app)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("id") == id) {
                    o.put("tries", o.optInt("tries") + 1).put("t", System.currentTimeMillis())
                    break
                }
            }
            histSave(app, a)
        }
    }

    /** Server ki job-list (ya ek job) se record ki states taaza karo. markLost: poori list mili thi, jo job usme nahi wo "lost". */
    fun mergeJobs(app: Context, jobs: JSONArray?, markLost: Boolean) {
        synchronized(histLock) {
            val a = histLoad(app)
            val byId = HashMap<String, JSONObject>()
            if (jobs != null) for (i in 0 until jobs.length()) {
                val j = jobs.optJSONObject(i) ?: continue
                byId[j.optString("id")] = j
            }
            var changed = false
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val st = o.optString("state")
                if (st == "done" || st == "error" || st == "cancelled" || st == "lost") continue
                val j = byId[o.optString("id")]
                if (j != null) {
                    o.put("state", j.optString("state", st))
                    o.put("done", j.optLong("bytes_done", 0)).put("total", j.optLong("bytes_total", 0))
                    val res = j.optString("result")
                    if (res.isNotEmpty()) o.put("result", res)
                    val errs = j.optJSONArray("errors")
                    if (errs != null && errs.length() > 0) o.put("err", errs.optString(0))
                    changed = true
                } else if (markLost) {
                    o.put("state", "lost")
                    changed = true
                }
            }
            if (changed) histSave(app, a)
        }
    }

    /** Naya pehle; chalti hui (queued/running) sabse upar. */
    fun history(app: Context): List<SrvRow> {
        val a = synchronized(histLock) { histLoad(app) }
        val out = ArrayList<SrvRow>()
        for (i in a.length() - 1 downTo 0) {
            val o = a.optJSONObject(i) ?: continue
            out.add(
                SrvRow(o.optString("id"), o.optString("name"), o.optString("url"), o.optString("store"), o.optString("path"),
                    o.optLong("t"), o.optString("state"), o.optLong("done"), o.optLong("total"), o.optString("result"), o.optString("err"),
                    o.optInt("h"), o.optInt("tries"))
            )
        }
        return out.sortedBy { if (it.active) 0 else 1 }
    }

    fun histClearFinished(app: Context) {
        synchronized(histLock) {
            val a = histLoad(app)
            val keep = JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val st = o.optString("state")
                if (st == "queued" || st == "running") keep.put(o)
            }
            histSave(app, keep)
        }
    }

    fun histRemove(app: Context, id: String) {
        synchronized(histLock) {
            val a = histLoad(app)
            val keep = JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("id") != id) keep.put(o)
            }
            histSave(app, keep)
        }
    }

    /** Server ke /xfer/list ke saath aaya disk ka haal (AIC disk). null = abhi pata nahi. */
    @Volatile var disk: JSONObject? = null

    /** Disk kam ho to warning text, warna null. level: ok / low / stop (server tay karta hai). */
    fun diskWarning(): String? {
        val d = disk ?: return null
        val level = d.optString("level", "ok")
        if (level == "ok") return null
        val free = d.optLong("free", 0L) / 1073741824.0
        val total = d.optLong("total", 0L) / 1073741824.0
        val s = String.format(java.util.Locale.US, "%.1f GB khaali / %.0f GB", free, total)
        return if (level == "stop") "🚨 Server disk bhar gaya: $s. Naye downloads fail honge — pehle jagah banao."
        else "⚠️ Server disk me jagah kam hai: $s"
    }

    /** Server se poori job-list lao aur record taaza karo. [cb] background thread par; null = theek, warna error text. */
    fun refreshServer(ctx: Context, cb: (String?) -> Unit) {
        val app = ctx.applicationContext
        net.execute {
            var err: String? = null
            try {
                val req = Request.Builder().url(xferBase(app, store(app)) + "list").build()
                client(app).newCall(req).execute().use { r ->
                    val txt = r.body?.string() ?: ""
                    if (r.code == 401) err = "Username / password galat"
                    else if (!r.isSuccessful) err = "Server: HTTP " + r.code
                    else {
                        val j = JSONObject(txt)
                        disk = j.optJSONObject("disk")
                        mergeJobs(app, j.optJSONArray("jobs"), true)
                    }
                }
            } catch (e: Throwable) {
                err = "Server se judne me dikkat: " + (e.message ?: e.javaClass.simpleName)
            }
            cb(err)
        }
    }

    fun cancelServer(ctx: Context, id: String, cb: (String) -> Unit) {
        val app = ctx.applicationContext
        net.execute {
            val msg = try {
                val req = Request.Builder().url(xferBase(app, store(app)) + "cancel?id=" + Uri.encode(id))
                    .post(ByteArray(0).toRequestBody(null)).build()
                client(app).newCall(req).execute().use { r ->
                    if (r.isSuccessful) "Server par cancel bhej diya" else "Cancel nahi hua (HTTP " + r.code + ")"
                }
            } catch (e: Throwable) { "Cancel nahi hua: " + (e.message ?: "") }
            cb(msg)
        }
    }

    private const val PROPFIND_SIZE =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/></d:prop></d:propfind>"

    /** Server ki WebDAV se dekho ki file sach me wahan hai (aur kitni badi). [cb] background thread par. */
    fun verifyServerFile(ctx: Context, row: SrvRow, cb: (String) -> Unit) {
        val app = ctx.applicationContext
        net.execute {
            val msg = try {
                val res = row.result
                val st = res.substringBefore(":/")
                val p = res.substringAfter(":/", "")
                if (st != "aic" && st != "hf" || p.isEmpty()) {
                    "Server ne file ka path nahi diya"
                } else {
                    val sb = StringBuilder(root(app, st))
                    for (seg in p.split('/').filter { it.isNotEmpty() }) sb.append(Uri.encode(seg)).append('/')
                    val url = sb.toString().trimEnd('/')
                    val req = Request.Builder().url(url)
                        .method("PROPFIND", PROPFIND_SIZE.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                        .header("Depth", "0").build()
                    client(app).newCall(req).execute().use { r ->
                        val txt = r.body?.string() ?: ""
                        when {
                            r.code == 404 -> "❌ Server par ye file nahi mili"
                            r.code == 401 -> "Username / password galat"
                            !r.isSuccessful -> "Server: HTTP " + r.code
                            else -> {
                                val m = Regex("getcontentlength[^>]*>\\s*(\\d+)").find(txt)
                                "✅ Server par file hai" + (if (m != null) " · " + DlFmt.size(m.groupValues[1].toLong()) else "")
                            }
                        }
                    }
                }
            } catch (e: Throwable) { "Check nahi ho paya: " + (e.message ?: "") }
            cb(msg)
        }
    }

    private class HttpErr(val code: Int, msg: String) : IOException(msg)

    private fun postDl(app: Context, store: String, path: String, spec: DlSpec, kind: String, height: Int = 0, onConflict: String = "rename"): String {
        val body = JSONObject()
            .put("url", spec.url).put("name", spec.name).put("cookie", spec.cookie)
            .put("ua", spec.ua).put("referer", spec.referer).put("kind", kind)
            .put("on_conflict", onConflict)
            .put("dest", JSONObject().put("store", store).put("path", path))
        if (height < 0) body.put("audio", true) else if (height > 0) body.put("height", height)
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

    // ── Batch: single download jaisa tareeka ──
    // Single (⬇ icon) video-page ka HTML padh kar usme se m3u8 stream link nikalta hai aur server ko "hls" mode me deta hai.
    // Batch pehle page link seedha yt-dlp ko deta tha (xHamster par KeyError('title')). Ab batch bhi har page se
    // m3u8 nikalta hai (VideoDetect.collectCard jaisi logic) aur "hls" me bhejta hai. Link na mile to purana yt-dlp raasta.
    private val resolveCache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Int, String>>>()
    private val JUNK_RE = Regex("thumb|preview|sprite|heatmap|poster|trailer|teaser|storyboard|/ads?/", RegexOption.IGNORE_CASE)
    private val URL_RE = Regex("https?://[^\"'\\s<>\\\\]+", RegexOption.IGNORE_CASE)
    private val MEDIA_EXT_RE = Regex("\\.(m3u8|mp4|webm|mpd)(\\?|#|$|\\.)", RegexOption.IGNORE_CASE)
    private val M3U8_RE = Regex("\\.m3u8(\\?|#|$)", RegexOption.IGNORE_CASE)

    private fun cookieFor(url: String, fallback: String): String {
        val c = try { android.webkit.CookieManager.getInstance().getCookie(url) ?: "" } catch (e: Exception) { "" }
        return c.ifEmpty { fallback }
    }

    /** Video page ka HTML padh kar (height, m3u8 link) list: sabse badi quality pehle. Kuch na mile / error => khaali list. */
    fun resolveHls(spec: DlSpec): List<Pair<Int, String>> {
        resolveCache[spec.url]?.let { return it }
        val out = try { resolveHlsUncached(spec) } catch (e: Throwable) { emptyList() }
        if (out.isNotEmpty()) {
            if (resolveCache.size > 400) resolveCache.clear()
            resolveCache[spec.url] = out
        }
        return out
    }

    private fun resolveHlsUncached(spec: DlSpec): List<Pair<Int, String>> {
        val html = HlsDl.get(spec.url, spec.cookie, spec.ua, spec.referer)
        val t = html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val urls = ArrayList<String>()
        for (m in URL_RE.findAll(t)) {
            if (urls.size >= 60) break
            val u = m.value
            if (MEDIA_EXT_RE.containsMatchIn(u) && u !in urls) urls.add(u)
        }
        val variants = ArrayList<Pair<Int, String>>()
        val manifests = ArrayList<String>()
        for (u in urls) {
            if (u.contains("_TPL_")) {               // template link: multi=WxH:144p,... se har quality ka link
                val mm = Regex("multi=([^/]+)").find(u) ?: continue
                for (part in mm.groupValues[1].split(",")) {
                    val f = part.split(":")
                    val lab = f.getOrNull(1)?.takeIf { it.isNotEmpty() }
                        ?: Regex("x(\\d+)").find(f[0])?.groupValues?.get(1)?.let { it + "p" }
                        ?: continue
                    variants.add((lab.filter { it.isDigit() }.toIntOrNull() ?: 0) to u.replace("_TPL_", lab))
                }
                continue
            }
            if (M3U8_RE.containsMatchIn(u) && !JUNK_RE.containsMatchIn(u) && u !in manifests) manifests.add(u)
        }
        for (m in manifests.take(4)) {
            val got = HlsDl.variantList(m, cookieFor(m, spec.cookie), spec.ua, spec.url)
            for ((label, link) in got) {
                val h = Regex("(\\d{3,4})p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                variants.add(h to link)
            }
            if (got.isNotEmpty()) break
        }
        return variants.distinctBy { it.second }.sortedByDescending { it.first }
    }

    /** height: 0 = sabse badi; >0 = us tak ki sabse achhi, na ho to sabse nazdeek (sabse chhoti). */
    private fun pickVariant(v: List<Pair<Int, String>>, height: Int): Pair<Int, String> {
        if (height <= 0) return v.first()
        return v.firstOrNull { it.first in 1..height }
            ?: v.filter { it.first > 0 }.minByOrNull { it.first }
            ?: v.first()
    }

    /** Batch ki quality list: pehle <=3 video ke pages se m3u8 qualities. Kuch na mile to purana yt-dlp tareeka. */
    fun fetchHlsHeights(ctx: Context, specs: List<DlSpec>, cb: (List<FormatOpt>, Boolean) -> Unit) {
        net.execute {
            val futs = specs.take(3).map { s -> net.submit(java.util.concurrent.Callable<List<Pair<Int, String>>> { resolveHls(s) }) }
            val got = futs.map {
                try { it.get(40, TimeUnit.SECONDS) } catch (e: Throwable) { emptyList<Pair<Int, String>>() }
            }.filter { it.isNotEmpty() }
            if (got.isEmpty()) { fetchFormats(ctx, specs, cb); return@execute }
            val count = java.util.TreeMap<Int, Int>()
            for (v in got) for (h in v.map { it.first }.filter { it > 0 }.toSet()) count[h] = (count[h] ?: 0) + 1
            val list = count.descendingMap().map { (h, c) -> FormatOpt(h, "", 0, c, got.size) }
            cb(list, false)
        }
    }

    /** Ek video server ko bhejo: pehle hls (single jaisa), na ho paye to yt-dlp. Sirf-audio (-1) hamesha yt-dlp. */
    private fun postOne(app: Context, store: String, path: String, spec: DlSpec, height: Int, onConflict: String = "rename"): String {
        // link khud stream / file ka ho (single download ki purani entry) to seedha usi tarah bhejo
        val upath = try { (java.net.URI(spec.url).path ?: "").lowercase() } catch (e: Exception) { "" }
        if (upath.endsWith(".m3u8")) return postDl(app, store, path, spec, "hls", 0, onConflict)
        if (Regex("\\.(mp4|webm|mkv|mov|m4v)$").containsMatchIn(upath)) return postDl(app, store, path, spec, "file", 0, onConflict)
        if (height >= 0) {
            val v = resolveHls(spec)
            if (v.isNotEmpty()) {
                val (h, link) = pickVariant(v, height)
                val nm = if (h > 0) spec.name + "_" + h + "p" else spec.name
                return postDl(app, store, path, DlSpec(link, cookieFor(link, spec.cookie), spec.ua, spec.url, nm, ""), "hls", 0, onConflict)
            }
        }
        return postDl(app, store, path, spec, "ytdlp", height, onConflict)
    }

    // ── Fail / lost / adhoori jobs apne aap dobara ──
    // * fail (site se hata di gayi ko chhod ke) aur lost (server restart) jobs: 3 baar tak apne aap, 3 / 15 / 60 minute ke gap par.
    //   Dobara bhejte waqt on_conflict=skip, taaki jo file pehle ban chuki thi uski duplicate na bane.
    // * bulk bhejte waqt app band ho gayi to bacha hua hissa (pending) phone me yaad rehta hai aur app dobara chalte hi bhej diya jata hai.
    private const val K_PEND = "srv_pend"
    private val pendLock = Any()
    private val bulkBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val retryBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val autoStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val RETRY_MAX = 3
    private val RETRY_WAIT_MIN = longArrayOf(3, 15, 60)

    private fun pendLoad(app: Context): JSONArray =
        try { JSONArray(DlSettings.getStr(app, K_PEND, "[]")) } catch (e: Exception) { JSONArray() }

    private fun pendAdd(app: Context, specs: List<DlSpec>, height: Int, store: String, path: String) {
        synchronized(pendLock) {
            val a = pendLoad(app)
            val have = HashSet<String>()
            for (i in 0 until a.length()) have.add(a.getJSONObject(i).optString("u"))
            for (sp in specs) {
                if (!have.add(sp.url)) continue
                a.put(JSONObject().put("u", sp.url).put("n", sp.name).put("h", height).put("s", store).put("p", path))
            }
            DlSettings.setStr(app, K_PEND, a.toString())
        }
    }

    private fun pendRemove(app: Context, url: String) {
        synchronized(pendLock) {
            val a = pendLoad(app)
            val keep = JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("u") != url) keep.put(o)
            }
            DlSettings.setStr(app, K_PEND, keep.toString())
        }
    }

    /** Dobara bheji ja sakti hai? (fail, par "site se hata di gayi" nahi; ya lost). */
    fun canRetry(r: SrvRow): Boolean =
        ((r.state == "error" && !r.removed) || r.state == "lost") && r.url.isNotBlank()

    private fun retryDue(r: SrvRow, now: Long): Boolean =
        canRetry(r) && r.tries < RETRY_MAX &&
            now - r.t >= RETRY_WAIT_MIN[r.tries.coerceIn(0, RETRY_WAIT_MIN.size - 1)] * 60_000L

    /**
     * manual=true: gap aur 3-koshish ki seema nahi dekhta. only != null: sirf wahi ek row.
     * [cb](bheji, fail) background thread par; bheji = -1 matlab pehle se ek retry chal raha hai.
     */
    fun retryFailed(ctx: Context, manual: Boolean, only: SrvRow? = null, cb: ((Int, Int) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!enabled(app)) { cb?.invoke(0, 0); return }
        if (!retryBusy.compareAndSet(false, true)) { cb?.invoke(-1, 0); return }
        net.execute {
            var sent = 0
            var failed = 0
            try {
                val now = System.currentTimeMillis()
                val rows = if (only != null) listOf(only)
                else history(app).filter { if (manual) canRetry(it) else retryDue(it, now) }
                rows@ for (r in rows) {
                    val st = r.store.ifEmpty { store(app) }
                    val pa = r.path.ifEmpty { path(app) }
                    val spec = DlSpec(r.url, cookieFor(r.url, ""), PopupLogin.cleanUa(app), r.url, r.name, "")
                    var waits = 0
                    while (true) {
                        try {
                            val id = postOne(app, st, pa, spec, r.h, "skip")
                            histAdd(app, id, r.name, r.url, st, pa, r.h, r.tries + 1)
                            histRemove(app, r.id)
                            sent++
                            break
                        } catch (e: HttpErr) {
                            if (e.code == 429 && ++waits <= 60) {
                                try { Thread.sleep(10_000) } catch (x: InterruptedException) { break@rows }
                                continue
                            }
                            if (e.code == 401) break@rows
                            histBump(app, r.id); failed++
                            break
                        } catch (e: Throwable) {
                            histBump(app, r.id); failed++
                            break
                        }
                    }
                }
            } finally {
                retryBusy.set(false)
            }
            cb?.invoke(sent, failed)
        }
    }

    /** Pichli bulk ka bacha (bheja nahi gaya) hissa server ko bhejo. */
    fun drainPending(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        if (synchronized(pendLock) { pendLoad(app).length() } == 0) return
        if (!bulkBusy.compareAndSet(false, true)) return
        net.execute {
            try {
                val a = synchronized(pendLock) { pendLoad(app) }
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val url = o.optString("u")
                    if (url.isEmpty()) continue
                    val st = o.optString("s").ifEmpty { store(app) }
                    val pa = o.optString("p").ifEmpty { path(app) }
                    val h = o.optInt("h")
                    val name = o.optString("n")
                    val spec = DlSpec(url, cookieFor(url, ""), PopupLogin.cleanUa(app), url, name, "")
                    var waits = 0
                    while (true) {
                        try {
                            val id = postOne(app, st, pa, spec, h)
                            histAdd(app, id, name, url, st, pa, h)
                            pendRemove(app, url)
                            break
                        } catch (e: HttpErr) {
                            if (e.code == 429 && ++waits <= 360) {
                                try { Thread.sleep(10_000) } catch (x: InterruptedException) { return@execute }
                                continue
                            }
                            if (e.code == 401) return@execute
                            pendRemove(app, url)          // server ne is link ko hi mana kar diya: dobara bhejna bekaar
                            break
                        } catch (e: Throwable) {
                            break                          // net / server band: pending me rehne do, agli baar bhejenge
                        }
                    }
                }
            } finally {
                bulkBusy.set(false)
            }
        }
    }

    fun nudge(ctx: Context) {
        drainPending(ctx)
        retryFailed(ctx, false)
    }

    /** App chalte hi ek halka background thread: har 2 minute me server ki state taaza karke adhoori / fail jobs dobara bhejta hai. */
    fun startAuto(ctx: Context) {
        if (!autoStarted.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val t = Thread {
            try { Thread.sleep(20_000) } catch (e: InterruptedException) { return@Thread }
            while (true) {
                try {
                    if (enabled(app)) {
                        val latch = java.util.concurrent.CountDownLatch(1)
                        refreshServer(app) { latch.countDown() }
                        latch.await(30, TimeUnit.SECONDS)
                        nudge(app)
                    }
                } catch (e: Throwable) { }
                try { Thread.sleep(120_000) } catch (e: InterruptedException) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "srv-auto-retry"
        t.start()
    }

    /**
     * Kai video-page links ek saath. Har page se m3u8 nikal kar "hls" me (single jaisa); link na mile to yt-dlp.
     * Server ki queue bhari ho (HTTP 429) to 10 sec ruk ke dobara.
     * Phone par fallback NAHI (page link phone se download nahi ho sakta). Aakhir me summary toast.
     */
    fun sendBulk(ctx: Context, specs: List<DlSpec>, height: Int = 0) {
        val app = ctx.applicationContext
        val store = store(app)
        val path = path(app)
        net.execute {
            while (!bulkBusy.compareAndSet(false, true)) {          // pichli pending bulk / dusri bulk khatam hone do
                try { Thread.sleep(2000) } catch (x: InterruptedException) { return@execute }
            }
            val ids = ArrayList<String>()
            val nameOf = HashMap<String, String>()
            var rejected = 0
            var lastErr = ""
            pendAdd(app, specs, height, store, path)               // app beech me band ho jaye to bacha hissa yaad rahe
            DlEngine.toast("☁ " + specs.size + " video server ko bhej rahe hain (" + qualityName(height) + ")…")
            try {
            for (spec in specs) {
                var tries = 0
                while (true) {
                    try {
                        val id = postOne(app, store, path, spec, height)
                        ids.add(id); nameOf[id] = spec.name
                        histAdd(app, id, spec.name, spec.url, store, path, height)
                        pendRemove(app, spec.url)
                        break
                    } catch (e: HttpErr) {
                        if (e.code == 429 && ++tries <= 360) {
                            try { Thread.sleep(10_000) } catch (x: InterruptedException) { return@execute }
                            continue
                        }
                        rejected++; lastErr = e.message ?: ""
                        if (e.code != 401) pendRemove(app, spec.url)
                        if (e.code == 401) { DlEngine.toast("☁ " + lastErr); return@execute }
                        break
                    } catch (e: Throwable) {
                        rejected++; lastErr = e.message ?: e.javaClass.simpleName
                        break
                    }
                }
            }
            } finally {
                bulkBusy.set(false)
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
                        mergeJobs(app, jobs, false)
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
                    mergeJobs(app, JSONArray().put(j), false)
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
