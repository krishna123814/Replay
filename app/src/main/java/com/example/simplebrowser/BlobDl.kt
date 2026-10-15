package com.example.simplebrowser

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * blob: aur data: downloads.
 * - data:  seedha decode karke save.
 * - blob:  page ke andar JS se blob padh kar chhote base64 tukdon me LionBlob bridge ko bhejte hain,
 *          Kotlin side temp file me likhta hai, phir Downloads/Lion me save.
 */
object BlobDl {

    const val BRIDGE_NAME = "LionBlob"

    private class Sess(
        val id: String,
        val nameHint: String,
        val mimeHint: String,
        val hasCdName: Boolean,
        val tmp: File,
        val created: Long
    ) {
        var out: FileOutputStream? = null
        var mime: String = mimeHint
        var failed = false
    }

    private val sessions = ConcurrentHashMap<String, Sess>()
    private val io = Executors.newSingleThreadExecutor()

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun tmpDir(ctx: Context): File {
        val d = File(ctx.cacheDir, "blobs")
        d.mkdirs()
        return d
    }

    private fun cleanOld() {
        val now = System.currentTimeMillis()
        for ((k, s) in sessions) {
            if (now - s.created > 15 * 60_000L) {
                sessions.remove(k)
                try { s.out?.close() } catch (e: Exception) { }
                try { s.tmp.delete() } catch (e: Exception) { }
            }
        }
    }

    // ── blob: ─────────────────────────────────────────────────────────────
    fun startBlob(ctx: Context, web: WebView, url: String, cd: String?, mime: String?) {
        cleanOld()
        val app = ctx.applicationContext
        val id = UUID.randomUUID().toString().replace("-", "")
        val hasCdName = cd?.contains("filename", ignoreCase = true) == true
        val hint = if (hasCdName) URLUtil.guessFileName(url, cd, mime) else "Lion_" + stamp()
        val s = Sess(id, hint, mime ?: "", hasCdName, File(tmpDir(app), "$id.tmp"), System.currentTimeMillis())
        sessions[id] = s
        Toast.makeText(app, "Blob save ho raha hai…", Toast.LENGTH_SHORT).show()

        val js = """
            (function () {
              var id = ${JSONObject.quote(id)};
              var url = ${JSONObject.quote(url)};
              try {
                var x = new XMLHttpRequest();
                x.open('GET', url, true);
                x.responseType = 'blob';
                x.onload = function () {
                  if (x.status !== 200 && x.status !== 0) { LionBlob.fail(id, 'HTTP ' + x.status); return; }
                  var b = x.response;
                  if (!b) { LionBlob.fail(id, 'blob khali hai'); return; }
                  LionBlob.begin(id, b.type || '', b.size);
                  var CH = 3 * 128 * 1024;
                  var off = 0;
                  function next() {
                    if (off >= b.size) { LionBlob.end(id); return; }
                    var r = new FileReader();
                    r.onload = function () {
                      var s = String(r.result);
                      var i = s.indexOf(',');
                      LionBlob.chunk(id, s.substring(i + 1));
                      off += CH;
                      setTimeout(next, 0);
                    };
                    r.onerror = function () { LionBlob.fail(id, 'blob read error'); };
                    r.readAsDataURL(b.slice(off, off + CH));
                  }
                  next();
                };
                x.onerror = function () { LionBlob.fail(id, 'blob link expire ho gayi'); };
                x.send();
              } catch (e) { LionBlob.fail(id, String(e)); }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    /** JS -> Kotlin bridge. Sirf hamare banaye session id par kaam karta hai. */
    class Bridge(private val appCtx: Context) {

        @JavascriptInterface
        fun begin(id: String?, mime: String?, size: Double) {
            val s = sessions[id ?: return] ?: return
            try {
                s.out = FileOutputStream(s.tmp)
                if (!mime.isNullOrEmpty()) s.mime = mime
            } catch (e: Exception) {
                s.failed = true
            }
        }

        @JavascriptInterface
        fun chunk(id: String?, b64: String?) {
            val s = sessions[id ?: return] ?: return
            val o = s.out ?: return
            if (s.failed || b64 == null) return
            try {
                o.write(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Exception) {
                s.failed = true
            }
        }

        @JavascriptInterface
        fun end(id: String?) {
            val s = sessions.remove(id ?: return) ?: return
            try { s.out?.close() } catch (e: Exception) { }
            if (s.failed || s.out == null) {
                try { s.tmp.delete() } catch (e: Exception) { }
                DlEngine.toast("Blob save nahi ho paya")
                return
            }
            var name = s.nameHint
            if (!s.hasCdName || !name.contains('.')) name = DlStorage.ensureExt(name, s.mime)
            io.execute { DlEngine.saveLocalFile(appCtx, s.tmp, name, s.mime) }
        }

        @JavascriptInterface
        fun fail(id: String?, msg: String?) {
            val s = sessions.remove(id ?: return) ?: return
            try { s.out?.close() } catch (e: Exception) { }
            try { s.tmp.delete() } catch (e: Exception) { }
            DlEngine.toast("Blob download fail: " + (msg ?: "pata nahi"))
        }
    }

    // ── data: ─────────────────────────────────────────────────────────────
    fun saveDataUrl(ctx: Context, url: String, cd: String?, mime0: String?) {
        val app = ctx.applicationContext
        Toast.makeText(app, "Data file save ho rahi hai…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val comma = url.indexOf(',')
                if (comma < 0) throw IllegalArgumentException("data URL galat hai")
                val header = url.substring(5, comma)
                var payload = url.substring(comma + 1)
                val isB64 = header.contains(";base64", ignoreCase = true)
                val mime = header.substringBefore(';').ifEmpty { mime0 ?: "application/octet-stream" }
                val bytes: ByteArray = if (isB64) {
                    if (payload.contains('%')) payload = Uri.decode(payload)
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    Uri.decode(payload).toByteArray(Charsets.UTF_8)
                }
                val tmp = File(tmpDir(app), UUID.randomUUID().toString() + ".tmp")
                FileOutputStream(tmp).use { it.write(bytes) }
                val hasCdName = cd?.contains("filename", ignoreCase = true) == true
                var name = if (hasCdName) URLUtil.guessFileName(url, cd, mime) else "Lion_" + stamp()
                name = DlStorage.ensureExt(name, mime)
                DlEngine.saveLocalFile(app, tmp, name, mime)
            } catch (e: Exception) {
                DlEngine.toast("Data download fail: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
