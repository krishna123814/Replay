package com.example.simplebrowser.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.max

/** Thumbnail + full-image loader: memory cache + disk cache (cacheDir/thumbs). */
object Thumbs {
    private var dir: File? = null
    private val main = Handler(Looper.getMainLooper())
    private val imgPool = Executors.newFixedThreadPool(3)
    private val vidPool = Executors.newFixedThreadPool(2)
    private val fullPool = Executors.newFixedThreadPool(2)

    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun init(ctx: Context) {
        val d = File(ctx.cacheDir, "thumbs")
        d.mkdirs()
        dir = d
        Thread {
            try {
                val files = d.listFiles() ?: return@Thread
                var total = files.sumOf { it.length() }
                if (total > 300L * 1024 * 1024) {
                    for (f in files.sortedBy { it.lastModified() }) {
                        if (total <= 200L * 1024 * 1024) break
                        total -= f.length()
                        f.delete()
                    }
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    fun clear() {
        mem.evictAll()
        dir?.listFiles()?.forEach { it.delete() }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** List/grid thumbnail. View ka tag url se match na kare to result ignore (recycling safe). */
    fun load(view: ImageView, url: String, kind: Kind, sizePx: Int, allowVideo: Boolean) {
        view.tag = url
        view.setImageDrawable(null)
        val key = "$url@$sizePx"
        val hit = mem.get(key)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        if (kind == Kind.VIDEO && !allowVideo) return
        val pool = if (kind == Kind.VIDEO) vidPool else imgPool
        pool.execute {
            if (view.tag != url) return@execute
            val bmp = try {
                fromDisk(url, sizePx) ?: create(url, kind, sizePx)
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) {
                mem.put(key, bmp)
                main.post { if (view.tag == url) view.setImageBitmap(bmp) }
            }
        }
    }

    /** Viewer ke liye badi image (max side ~target px). cb main thread par aata hai. */
    fun loadFull(url: String, target: Int, cb: (Bitmap?) -> Unit) {
        val key = "$url@full"
        val hit = mem.get(key)
        if (hit != null) {
            cb(hit)
            return
        }
        fullPool.execute {
            val bmp = try {
                if (LocalFs.isLocal(url)) {
                    decode(LocalFs.fileOf(url), target)
                } else {
                    val f = downloadTemp(url)
                    try {
                        decode(f, target)
                    } finally {
                        f.delete()
                    }
                }
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) mem.put(key, bmp)
            main.post { cb(bmp) }
        }
    }

    private fun diskFile(url: String, size: Int): File? {
        val d = dir ?: return null
        return File(d, md5("$url@$size") + ".jpg")
    }

    private fun fromDisk(url: String, size: Int): Bitmap? {
        val f = diskFile(url, size) ?: return null
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)
    }

    private fun toDisk(url: String, size: Int, bmp: Bitmap) {
        val f = diskFile(url, size) ?: return
        try {
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (e: Exception) {
            f.delete()
        }
    }

    private fun create(url: String, kind: Kind, size: Int): Bitmap? {
        val bmp: Bitmap? = if (kind == Kind.VIDEO) {
            videoFrame(url, size)
        } else {
            if (LocalFs.isLocal(url)) {
                decode(LocalFs.fileOf(url), size)
            } else {
                val f = downloadTemp(url)
                try {
                    decode(f, size)
                } finally {
                    f.delete()
                }
            }
        }
        if (bmp != null) toDisk(url, size, bmp)
        return bmp
    }

    private fun downloadTemp(url: String): File {
        val d = dir ?: throw IOException("cache dir nahi")
        val tmp = File.createTempFile("dl", ".tmp", d)
        try {
            Dav.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("khaali")
                if (body.contentLength() > 80_000_000L) throw IOException("bahut badi image")
                body.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        return tmp
    }

    private fun decode(file: File, target: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= target) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        var bmp = BitmapFactory.decodeFile(file.path, o2) ?: return null
        val rot = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (rot != 0f) {
            val m = Matrix()
            m.postRotate(rot)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    private fun videoFrame(url: String, target: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        try {
            if (LocalFs.isLocal(url)) {
                mmr.setDataSource(LocalFs.fileOf(url).path)
            } else {
                mmr.setDataSource(url, hashMapOf("Authorization" to Session.auth()))
            }
            val f = mmr.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(0L)
                ?: return null
            val sc = target.toFloat() / max(f.width, f.height)
            return if (sc < 1f) {
                Bitmap.createScaledBitmap(
                    f,
                    (f.width * sc).toInt().coerceAtLeast(1),
                    (f.height * sc).toInt().coerceAtLeast(1),
                    true
                )
            } else f
        } finally {
            try {
                mmr.release()
            } catch (e: Throwable) {
            }
        }
    }
}
