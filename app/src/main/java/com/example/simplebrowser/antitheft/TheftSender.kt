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
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Photo/text alert server ko bhejta hai: POST <server>/api/theft_photo (nginx Basic auth = File Manager wala login).
 * Server use Telegram par aage bhejta hai (bot token APK me nahi hota).
 * Net na ho to file phone me rehti hai aur WorkManager net aate hi dobara koshish karta hai.
 */
object TheftSender {
    private val JPEG = "image/jpeg".toMediaType()
    private val EMPTY = ByteArray(0).toRequestBody(null)

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "theft").apply { mkdirs() }

    fun pendingCount(ctx: Context): Int =
        dir(ctx).listFiles()?.map { it.nameWithoutExtension }?.distinct()?.size ?: 0

    /** Background me bhejne ka kaam lagao (net aate hi chalega). */
    fun kick(ctx: Context) {
        val a = ctx.applicationContext
        val req = OneTimeWorkRequestBuilder<TheftWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(a).enqueueUniqueWork("theft_send", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Blocking. True = sab bhej diya (ya kuch bhejne ko nahi). */
    fun sendAllBlocking(ctx: Context): Boolean {
        val a = ctx.applicationContext
        val files = dir(a).listFiles() ?: return true
        val bases = files.map { it.nameWithoutExtension }.distinct().sorted()
        if (bases.isEmpty()) return true

        if (!Session.hasCreds()) Session.load(a)
        if (!Session.hasCreds()) {
            TheftEngine.setStatus(a, "Server login nahi — File Manager me login karo (${bases.size} alert pending)")
            return false
        }
        val origin = Session.baseUrl.toHttpUrlOrNull()
            ?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()
        if (origin == null) {
            TheftEngine.setStatus(a, "Server URL galat")
            return false
        }
        val url = origin.toString() + "api/theft_photo"

        var allOk = true
        for (b in bases) {
            val jpg = File(dir(a), "$b.jpg")
            val txt = File(dir(a), "$b.txt")
            val caption = if (txt.exists()) txt.readText() else ""
            val hadPhoto = jpg.exists()
            val body = if (jpg.exists()) jpg.readBytes().toRequestBody(JPEG) else EMPTY
            if (!jpg.exists() && caption.isBlank()) { txt.delete(); continue }
            try {
                val req = Request.Builder().url(url)
                    .header("X-Caption", URLEncoder.encode(caption, "UTF-8").replace("+", "%20"))
                    .post(body).build()
                Dav.client.newCall(req).execute().use { r ->
                    if (r.code == 200) {
                        jpg.delete(); txt.delete()
                        TheftEngine.setStatus(a, if (hadPhoto) "Photo Telegram par bhej di ✅" else "Text alert Telegram par gaya ✅")
                    } else {
                        allOk = false
                        TheftEngine.setStatus(a, "Server ne mana kiya (HTTP ${r.code}) — dobara koshish hogi")
                    }
                }
            } catch (e: Throwable) {
                allOk = false
                TheftEngine.setStatus(a, "Net/server dikkat (${e.javaClass.simpleName}) — net aate hi bhejenge")
            }
        }
        return allOk
    }
}

class TheftWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result =
        if (TheftSender.sendAllBlocking(applicationContext)) Result.success()
        else if (runAttemptCount >= 10) Result.failure() else Result.retry()
}
