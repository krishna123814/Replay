package com.example.simplebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Chart screenshot (SV1 / SV2 📷) ka tez rasta.
 * html2canvas poora DOM dobara draw karta hai (vertical stack me 12s+ lagta tha → "Screenshot atak gaya").
 * Yahan WebView jo abhi screen par dikh raha hai wahi seedha bitmap me copy hota hai (<1 sec),
 * phir Downloads/Lion me wahi save hota hai jaha baaki downloads jaate hain.
 * JS side: window.LionShot.capture(fileName)  →  result me window._lionShotDone(ok, msg) call hota hai.
 */
object ShotBridge {

    const val NAME = "LionShot"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    class Bridge(private val ctx: Context, private val web: WebView) {

        @JavascriptInterface
        fun capture(fileName: String?) {
            val name = if (fileName.isNullOrBlank()) "chart.png" else fileName
            main.post {
                var bmp: Bitmap? = null
                try {
                    val w = web.width
                    val h = web.height
                    if (w <= 0 || h <= 0) { done(false, "WebView size 0"); return@post }
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    web.draw(Canvas(bmp))
                } catch (e: Throwable) {
                    try { bmp?.recycle() } catch (x: Throwable) { }
                    done(false, e.message ?: e.javaClass.simpleName)
                    return@post
                }
                val b = bmp
                io.execute {
                    try {
                        val app = ctx.applicationContext
                        val dir = File(app.cacheDir, "blobs").apply { mkdirs() }
                        val tmp = File(dir, UUID.randomUUID().toString() + ".png")
                        FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        b.recycle()
                        val ok = DlEngine.saveLocalFile(app, tmp, name, "image/png")
                        try { tmp.delete() } catch (x: Throwable) { }
                        done(ok, if (ok) "" else "save fail")
                    } catch (e: Throwable) {
                        try { b.recycle() } catch (x: Throwable) { }
                        done(false, e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }

        private fun done(ok: Boolean, msg: String) {
            main.post {
                try {
                    web.evaluateJavascript(
                        "window._lionShotDone&&window._lionShotDone($ok,${JSONObject.quote(msg)})", null
                    )
                } catch (e: Throwable) { }
            }
        }
    }
}
