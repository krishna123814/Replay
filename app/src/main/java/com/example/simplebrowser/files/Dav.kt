package com.example.simplebrowser.files

import android.content.Context
import android.net.Uri
import android.util.Xml
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class DavAuthException : IOException("Username / password galat")

class UriBody(
    private val ctx: Context,
    private val uri: Uri,
    private val len: Long,
    private val mime: String?
) : RequestBody() {
    override fun contentType(): MediaType? = (mime ?: "application/octet-stream").toMediaTypeOrNull()
    override fun contentLength(): Long = len
    override fun writeTo(sink: BufferedSink) {
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("file nahi khuli")
        ins.use { sink.writeAll(it.source()) }
    }
}

object Dav {

    /** Authorization header sirf apne server (Session.baseUrl ke host) par jata hai. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val host = Session.baseUrl.toHttpUrlOrNull()?.host
            if (host != null && req.url.host == host && Session.user.isNotEmpty()) {
                chain.proceed(req.newBuilder().header("Authorization", Session.auth()).build())
            } else {
                chain.proceed(req)
            }
        })
        .build()

    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:getcontentlength/><d:getlastmodified/><d:resourcetype/>" +
            "</d:prop></d:propfind>"

    fun withSlash(u: String): String = if (u.endsWith("/")) u else "$u/"

    fun list(dirUrl: String): List<Entry> {
        val base = withSlash(dirUrl)
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = Request.Builder().url(base).method("PROPFIND", body).header("Depth", "1").build()
        return client.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw DavAuthException()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val stream = resp.body?.byteStream() ?: throw IOException("khaali jawab")
            parse(stream, base)
        }
    }

    private fun parse(input: InputStream, base: String): List<Entry> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)

        val out = ArrayList<Entry>()
        var href = ""
        var size = 0L
        var mod = 0L
        var isDir = false
        var idx = -1

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "response" -> {
                        href = ""
                        size = 0L
                        mod = 0L
                        isDir = false
                        idx++
                    }
                    "href" -> href = p.nextText().trim()
                    "getcontentlength" -> size = p.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> mod = parseDate(p.nextText().trim())
                    "collection" -> isDir = true
                }
            } else if (ev == XmlPullParser.END_TAG) {
                // idx 0 = folder khud (self) -> skip
                if (p.name == "response" && idx > 0 && href.isNotEmpty()) {
                    val rawSeg = href.trimEnd('/').substringAfterLast('/')
                    val name = Uri.decode(rawSeg)
                    if (name.isNotEmpty()) {
                        val url = base + rawSeg + (if (isDir) "/" else "")
                        out.add(Entry(name, url, isDir, size, mod))
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseDate(s: String): Long {
        return try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun simple(method: String, url: String, dest: String? = null) {
        val b = Request.Builder().url(url).method(method, null)
        if (dest != null) {
            b.header("Destination", dest)
            b.header("Overwrite", "F")
        }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 401) throw DavAuthException()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        }
    }

    fun delete(url: String) = simple("DELETE", url)

    fun mkcol(url: String) = simple("MKCOL", withSlash(url))

    fun move(fromUrl: String, toUrl: String) = simple("MOVE", fromUrl, toUrl)

    fun put(url: String, body: RequestBody) {
        val req = Request.Builder().url(url).put(body).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401) throw DavAuthException()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        }
    }
}
