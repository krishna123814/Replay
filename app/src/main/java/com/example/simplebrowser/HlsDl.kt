package com.example.simplebrowser

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS (.m3u8) download, bina FFmpeg: saare segments ek ke baad ek jod kar ek file banti hai
 * (.ts, ya .mp4 agar stream fMP4 ho). AES-128 encrypted stream bhi chalti hai.
 * Live stream, DRM (SAMPLE-AES) aur alag audio track wali streams abhi support nahi.
 */
object HlsDl {

    private val io = Executors.newFixedThreadPool(1)

    private class Seg(val url: String, val seq: Long, val key: KeyInfo?)
    private class KeyInfo(val url: String, val iv: ByteArray?)

    fun start(ctx: Context, playlistUrl: String, baseName: String, cookie: String, ua: String, referer: String) {
        if (ServerSync.direct(ctx)) {
            // server ffmpeg se khud utarega; fail ho to neeche wala phone-par tareeka
            ServerSync.sendDirect(ctx, DlSpec(playlistUrl, cookie, ua, referer, baseName, ""), "hls") {
                startLocal(ctx, playlistUrl, baseName, cookie, ua, referer)
            }
            return
        }
        startLocal(ctx, playlistUrl, baseName, cookie, ua, referer)
    }

    private fun startLocal(ctx: Context, playlistUrl: String, baseName: String, cookie: String, ua: String, referer: String) {
        val app = ctx.applicationContext
        DlEngine.toast("HLS download shuru: $baseName")
        io.execute {
            val tmp = File(app.cacheDir, "hls_" + System.currentTimeMillis() + ".tmp")
            try {
                var url = playlistUrl
                var text = get(url, cookie, ua, referer)
                if (text.contains("#EXT-X-STREAM-INF")) {          // master: sabse achhi quality chuno
                    url = bestVariant(text, url)
                    text = get(url, cookie, ua, referer)
                }
                if (!text.contains("#EXT-X-ENDLIST")) throw Exception("live stream hai, support nahi")
                if (text.contains("METHOD=SAMPLE-AES") || text.contains("com.apple.fps")) throw Exception("DRM stream hai")

                val segs = ArrayList<Seg>()
                var key: KeyInfo? = null
                var seq = 0L
                var mapUrl: String? = null
                for (raw in text.lines()) {
                    val l = raw.trim()
                    when {
                        l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                        l.startsWith("#EXT-X-MAP:") -> mapUrl = abs(url, attr(l, "URI") ?: "")
                        l.startsWith("#EXT-X-KEY:") -> {
                            val m = attr(l, "METHOD")
                            key = if (m == "AES-128") {
                                val iv = attr(l, "IV")?.removePrefix("0x")?.removePrefix("0X")?.let { hex(it) }
                                KeyInfo(abs(url, attr(l, "URI") ?: ""), iv)
                            } else null
                        }
                        l.isNotEmpty() && !l.startsWith("#") -> { segs.add(Seg(abs(url, l), seq, key)); seq++ }
                    }
                }
                if (segs.isEmpty()) throw Exception("segments nahi mile")

                val keyCache = HashMap<String, ByteArray>()
                FileOutputStream(tmp).use { out ->
                    if (mapUrl != null) out.write(getBytes(mapUrl, cookie, ua, referer))
                    var lastPct = 0
                    for ((i, s) in segs.withIndex()) {
                        var data = getBytes(s.url, cookie, ua, referer)
                        val k = s.key
                        if (k != null) {
                            val kb = keyCache.getOrPut(k.url) { getBytes(k.url, cookie, ua, referer) }
                            val iv = k.iv ?: ByteArray(16).also { b ->
                                var v = s.seq
                                for (j in 15 downTo 8) { b[j] = (v and 0xff).toByte(); v = v shr 8 }
                            }
                            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kb, "AES"), IvParameterSpec(iv))
                            data = c.doFinal(data)
                        }
                        out.write(data)
                        val pct = (i + 1) * 100 / segs.size
                        if (pct >= lastPct + 25 && pct < 100) { lastPct = pct; DlEngine.toast("HLS $pct% …") }
                    }
                }
                val fmp4 = mapUrl != null
                val name = baseName + if (fmp4) ".mp4" else ".ts"
                DlEngine.saveLocalFile(app, tmp, name, if (fmp4) "video/mp4" else "video/mp2t")
            } catch (e: Throwable) {
                DlEngine.toast("HLS download fail: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                try { tmp.delete() } catch (e: Exception) { }
            }
        }
    }

    /** Master playlist se (label, url) list: sabse badi quality pehle. Network error par khali list. */
    fun variantList(url: String, cookie: String, ua: String, referer: String): List<Pair<String, String>> {
        return try {
            val t = get(url, cookie, ua, referer)
            val lines = t.lines()
            val out = ArrayList<Triple<Int, Long, Pair<String, String>>>()
            for (i in lines.indices) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val bw = attr(lines[i], "BANDWIDTH")?.toLongOrNull() ?: 0L
                val h = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j >= lines.size) continue
                val label = "TS/MP4 • " + (if (h > 0) "${h}p" else "${bw / 1000} kbps") + " (HLS)"
                out.add(Triple(h, bw, label to abs(url, lines[j].trim())))
            }
            if (out.isEmpty()) listOf("HLS • best" to url)
            else out.sortedWith(compareByDescending<Triple<Int, Long, Pair<String, String>>> { it.first }.thenByDescending { it.second })
                .map { it.third }.distinctBy { it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun bestVariant(master: String, base: String): String {
        val lines = master.lines()
        var bestBw = -1L
        var best: String? = null
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                val bw = attr(lines[i], "BANDWIDTH")?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size && bw > bestBw) { bestBw = bw; best = lines[j].trim() }
            }
        }
        return abs(base, best ?: throw Exception("variant nahi mili"))
    }

    private fun attr(line: String, name: String): String? {
        val m = Regex("(?:^|[,:])$name=(\"[^\"]*\"|[^,]*)").find(line) ?: return null
        return m.groupValues[1].trim('"')
    }

    private fun hex(s: String): ByteArray {
        val h = s.padStart(32, '0')
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun abs(base: String, rel: String): String = URL(URL(base), rel).toString()

    private fun open(url: String, cookie: String, ua: String, referer: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        if (ua.isNotEmpty()) c.setRequestProperty("User-Agent", ua)
        if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
        if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
        if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
        return c
    }

    private fun get(url: String, cookie: String, ua: String, referer: String): String =
        String(getBytes(url, cookie, ua, referer), Charsets.UTF_8)

    private fun getBytes(url: String, cookie: String, ua: String, referer: String): ByteArray {
        var last: Exception? = null
        repeat(3) {
            try {
                val c = open(url, cookie, ua, referer)
                return c.inputStream.use { it.readBytes() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("download fail")
    }
}
