package com.example.simplebrowser.antitheft

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.simplebrowser.files.Dav
import com.example.simplebrowser.files.Session
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Photos/text alert server ko bhejta hai: POST <server>/api/theft_photo (nginx Basic auth = File Manager wala login).
 * Server use Telegram par aage bhejta hai (bot token APK me nahi hota).
 * Files: theft_<ts>.txt (caption) + theft_<ts>_1.jpg, _2.jpg, _3.jpg. Net na ho to phone me rehti hain, WorkManager net aate hi bhejta hai.
 */
object TheftSender {
    private val JPEG = "image/jpeg".toMediaType()
    private val EMPTY: RequestBody = ByteArray(0).toRequestBody(null)

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "theft").apply { mkdirs() }

    /** Server ka root (https://host/) — login Session se. */
    fun origin(ctx: Context): String? {
        if (!Session.hasCreds()) Session.load(ctx.applicationContext)
        if (!Session.hasCreds()) return null
        return Session.baseUrl.toHttpUrlOrNull()
            ?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()
    }

    // theft_<ts>.txt = caption ; theft_<ts>_<n>.jpg = photo n. (Text-only alerts: theft_<ts>_<rand>.txt — ye bhi ek hi "group" hai)
    private fun groups(ctx: Context): Map<String, List<File>> {
        val all = dir(ctx).listFiles() ?: return emptyMap()
        val m = LinkedHashMap<String, MutableList<File>>()
        for (f in all.sortedBy { it.name }) {
            val key = if (f.name.endsWith(".jpg")) f.name.removeSuffix(".jpg").substringBeforeLast("_")
            else f.name.removeSuffix(".txt")
            m.getOrPut(key) { ArrayList() }.add(f)
        }
        return m
    }

    fun pendingCount(ctx: Context): Int = groups(ctx).size

    /** Background me bhejne ka kaam lagao (net aate hi chalega). */
    fun kick(ctx: Context) {
        val a = ctx.applicationContext
        val req = OneTimeWorkRequestBuilder<TheftWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(a).enqueueUniqueWork("theft_send", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    private val lock = Any()

    /** Blocking. True = sab bhej diya (ya kuch bhejne ko nahi). */
    fun sendAllBlocking(ctx: Context): Boolean = synchronized(lock) { doSend(ctx) }

    private fun doSend(ctx: Context): Boolean {
        val a = ctx.applicationContext
        val gs = groups(a)
        if (gs.isEmpty()) return true

        val root = origin(a)
        if (root == null) {
            TheftEngine.setStatus(a, "Server login nahi — File Manager me login karo (${gs.size} alert pending)")
            return false
        }
        val url = root + "api/theft_photo"

        var allOk = true
        for ((_, files) in gs) {
            val txt = files.firstOrNull { it.name.endsWith(".txt") }
            val photos = files.filter { it.name.endsWith(".jpg") }.sortedBy { it.name }
            val caption = txt?.takeIf { it.exists() }?.readText() ?: ""

            if (photos.isEmpty()) {
                if (caption.isBlank()) { txt?.delete(); continue }
                if (post(a, url, caption, EMPTY)) { txt?.delete(); TheftEngine.setStatus(a, "Text alert Telegram par gaya ✅") } else allOk = false
                continue
            }
            for ((i, jpg) in photos.withIndex()) {
                val cap = if (i == 0 && caption.isNotBlank()) caption else "📸 ${i + 1}/${photos.size}"
                if (post(a, url, cap, jpg.readBytes().toRequestBody(JPEG))) {
                    jpg.delete()
                    if (i == 0) txt?.delete()          // caption pehli photo ke saath ja chuka
                    TheftEngine.setStatus(a, "Photo Telegram par bhej di ✅")
                } else { allOk = false; break }
            }
        }
        return allOk
    }

    private fun post(a: Context, url: String, caption: String, body: RequestBody): Boolean = try {
        val req = Request.Builder().url(url)
            .header("X-Caption", URLEncoder.encode(caption, "UTF-8").replace("+", "%20"))
            .post(body).build()
        Dav.client.newCall(req).execute().use { r ->
            if (r.code == 200) true
            else { TheftEngine.setStatus(a, "Server ne mana kiya (HTTP ${r.code}) — dobara koshish hogi"); false }
        }
    } catch (e: Throwable) {
        TheftEngine.setStatus(a, "Net/server dikkat (${e.javaClass.simpleName}) — net aate hi bhejenge")
        false
    }
}

class TheftWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result =
        if (TheftSender.sendAllBlocking(applicationContext)) Result.success()
        else if (runAttemptCount >= 10) Result.failure() else Result.retry()
}
