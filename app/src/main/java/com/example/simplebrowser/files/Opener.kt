package com.example.simplebrowser.files

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Files kholne ka tarika: app ka apna player/viewer ya phone ka koi dusra app (BLC, VLC, Gallery...). */
object Opener {
    const val MODE_APP = 0
    const val MODE_ASK = 1
    const val MODE_EXTERNAL = 2

    private const val KEY = "openmode"

    val labels = arrayOf(
        "App ka apna player / viewer",
        "Har baar puchho",
        "Hamesha dusra app (chooser)"
    )

    fun mode(ctx: Context): Int = Session.int(ctx, KEY, MODE_APP).coerceIn(0, 2)
    fun setMode(ctx: Context, m: Int) = Session.setInt(ctx, KEY, m)

    fun mimeFor(name: String, kind: Kind): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (kind) {
            Kind.VIDEO -> "video/*"
            Kind.AUDIO -> "audio/*"
            Kind.IMAGE -> "image/*"
            else -> "*/*"
        }
    }

    /** Phone ki file ko FileProvider se dusre app me kholo. */
    fun viewFile(act: Activity, f: File, name: String) {
        try {
            val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeFor(name, Kinds.of(name, false)))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(act, "Is file ko kholne wala koi app nahi mila", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(act, "Khul nahi paya: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Kisi bhi entry (phone ya server) ko dusre app me kholo. */
    fun external(act: Activity, e: Entry) {
        if (LocalFs.isLocal(e.url)) {
            viewFile(act, LocalFs.fileOf(e.url), e.name)
            return
        }
        if (e.kind == Kind.VIDEO || e.kind == Kind.AUDIO) {
            streamExternal(act, e)
            return
        }
        Toast.makeText(act, "Download ho raha hai…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val f = downloadToCache(act, e)
                act.runOnUiThread { if (!act.isFinishing) viewFile(act, f, e.name) }
            } catch (ex: Exception) {
                act.runOnUiThread {
                    Toast.makeText(act, "Download nahi hua: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Server ki video / audio: player seedha URL se stream kare (login URL me, aur MX ke liye header bhi). */
    private fun streamExternal(act: Activity, e: Entry) {
        Thread {
            var url = e.url
            var withAuth = true
            if (Storages.storeOfUrl(e.url).isHf) {
                val cdn = Storages.cdnTarget(e.url)
                if (cdn != null) {
                    url = cdn
                    withAuth = false
                }
            }
            val shown: String = if (withAuth) {
                url.toHttpUrlOrNull()?.newBuilder()
                    ?.username(Session.user)?.password(Session.pass)?.build()?.toString() ?: url
            } else {
                url
            }
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                try {
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse(shown), mimeFor(e.name, e.kind))
                        .putExtra("title", e.name)
                    if (withAuth) i.putExtra("headers", arrayOf("Authorization", Session.auth()))
                    act.startActivity(i)
                    if (withAuth) {
                        Toast.makeText(
                            act,
                            "Login URL ke saath us player ko jata hai jo tum chunoge",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (ex: ActivityNotFoundException) {
                    Toast.makeText(act, "Koi player app nahi mila", Toast.LENGTH_LONG).show()
                } catch (ex: Exception) {
                    Toast.makeText(act, "Khul nahi paya: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Server file ko cache me utaro (share / dusre app ke liye). Blocking: background thread se bulao. */
    fun downloadToCache(
        ctx: Context, e: Entry, subDir: String = "ext",
        onProgress: ((Long) -> Unit)? = null, cancelled: (() -> Boolean)? = null
    ): File {
        val dir = File(ctx.cacheDir, subDir)
        dir.mkdirs()
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val out = File(dir, e.name.replace('/', '_'))
        val req = Request.Builder().url(e.url).build()
        Dav.client.newCall(req).execute().use { r ->
            if (r.code == 401) throw DavAuthException()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("khaali jawab")
            body.byteStream().use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var tot = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        if (cancelled?.invoke() == true) throw IOException("cancel")
                        o.write(buf, 0, n)
                        tot += n
                        onProgress?.invoke(tot)
                    }
                }
            }
        }
        return out
    }
}
