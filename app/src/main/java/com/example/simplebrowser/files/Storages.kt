package com.example.simplebrowser.files

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Do storage: VPS ki disk (WebDAV /dav/) aur HF Bucket (WebDAV /hf/). Dono ek hi login se. */
enum class Store(val id: String, val label: String, val short: String, val icon: String) {
    AIC("aic", "AIC Disk", "AIC", "💽"),
    HF("hf", "HF Bucket", "HF", "☁️"),
    LOCAL("local", "Phone Storage", "PHONE", "📱");

    companion object {
        fun from(id: String?): Store = values().firstOrNull { it.id == id } ?: AIC
    }
}

/** Copy / Move clipboard ka ek item. `path` = storage ke root se, jaise "/music/a.mp3" (server /xfer yahi leta hai). */
data class ClipItem(val store: Store, val path: String, val name: String, val isDir: Boolean, val size: Long = 0L)

object Storages {

    private fun base(): String = Dav.withSlash(Session.baseUrl)

    /** Login URL se ek level upar (".../dav/" -> "https://host/"), hamesha "/" par khatam. */
    private fun parent(): String {
        val b = base()
        for (s in arrayOf("dav/", "hf/")) {
            if (b.endsWith("/$s")) return b.dropLast(s.length)
        }
        val u = b.toHttpUrlOrNull() ?: return b
        return u.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }

    /**
     * Storage ka root URL. Login URL `/dav/` ho to AIC wahi, HF = `/hf/`.
     * (Agar kisi ne login `/hf/` se kiya ho to ulta.) Purana saved login (sirf `/dav/`) bina badlav chalta hai.
     */
    fun root(store: Store): String {
        val b = base()
        return when (store) {
            Store.LOCAL -> LocalFs.rootUrl()
            Store.AIC -> if (b.endsWith("/hf/")) parent() + "dav/" else b
            Store.HF -> if (b.endsWith("/hf/")) b else parent() + "hf/"
        }
    }

    /** Storage path -> URL. Phone ke liye path absolute hota hai (\"/storage/emulated/0/DCIM\"). */
    fun urlOf(store: Store, path: String, isDir: Boolean): String {
        if (store == Store.LOCAL) return LocalFs.urlOf(java.io.File(path), isDir)
        val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
        val sb = StringBuilder(Dav.withSlash(root(store)))
        for (s in segs) sb.append(Uri.encode(s)).append('/')
        if (!isDir && segs.isNotEmpty()) sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /** `/xfer` API ka base (bina trailing slash). */
    fun xferBase(): String = parent() + "xfer"

    /** Entry URL -> storage ke andar ka path ("/music/a b.mp3"; folder ho to trailing "/" rehta hai). */
    fun pathOf(store: Store, url: String): String {
        if (store == Store.LOCAL) return LocalFs.fileOf(url).absolutePath
        val rootPath = root(store).toHttpUrl().encodedPath
        val p = url.toHttpUrl().encodedPath
        val rel = if (p.startsWith(rootPath)) p.substring(rootPath.length) else p.trimStart('/')
        return "/" + Uri.decode(rel)
    }

    /**
     * HF band hai to uska sabab (sirf server ke 503 / 404 par). Network error ya baaki kuch ho to null
     * (card khula rahe; browser khud saaf error dikhayega). Depth 0 hai, isliye HF ki listing nahi laata.
     */
    fun hfDisabledReason(): String? {
        return try {
            val req = Request.Builder().url(root(Store.HF))
                .method("PROPFIND", "".toRequestBody(null))
                .header("Depth", "0")
                .build()
            Dav.client.newCall(req).execute().use { r ->
                when (r.code) {
                    503 -> "HF Bucket server par configured nahi hai (HF_TOKEN / MEDIA_BUCKET)"
                    404 -> "Server par /hf/ nahi mila (server update / nginx check karo)"
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * HF file ka GET server redirect (302) se HF CDN par bhejta hai. DownloadManager ko apna Authorization
     * header CDN par nahi bhejna chahiye, isliye pehle CDN link nikaal lete hain. Redirect na ho (proxy mode) to null.
     */
    fun cdnTarget(url: String): String? {
        return try {
            val c = Dav.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val req = Request.Builder().url(url).header("Range", "bytes=0-0").build()
            c.newCall(req).execute().use { r ->
                if (r.isRedirect) {
                    val loc = r.header("Location")
                    if (loc != null) r.request.url.resolve(loc)?.toString() else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
