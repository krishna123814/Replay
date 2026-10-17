package com.example.simplebrowser.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private class JobCancelled : IOException("cancel")

/** Phone se judi ek copy / move job (phone↔phone, phone↔server). Server wali jobs `/xfer` par chalti hain. */
class LocalJob(
    val id: String,
    val items: List<ClipItem>,
    val destStore: Store,
    val destPath: String,
    val move: Boolean,
    val onConflict: String
) {
    @Volatile var state = "queued"
    @Volatile var filesTotal = 0
    @Volatile var filesDone = 0
    @Volatile var filesSkipped = 0
    @Volatile var filesFailed = 0
    @Volatile var bytesTotal = 0L
    @Volatile var bytesDone = 0L
    @Volatile var current = ""
    @Volatile var cancelled = false
    @Volatile var finished = 0L
    val created: Long = System.currentTimeMillis() / 1000
    val errors = CopyOnWriteArrayList<String>()

    val active: Boolean get() = state == "queued" || state == "running"

    fun snap(): XferJob = XferJob(
        id = id,
        state = state,
        mode = if (move) "move" else "copy",
        destStore = destStore,
        destPath = destPath,
        items = items.size,
        filesTotal = filesTotal,
        filesDone = filesDone,
        filesSkipped = filesSkipped,
        filesFailed = filesFailed,
        bytesTotal = bytesTotal,
        bytesDone = bytesDone,
        current = current,
        errors = errors.toList(),
        created = created,
        finished = finished
    )
}

/** Upload body: file ko stream karta hai aur job ka progress badhata hai. */
private class CountingFileBody(private val f: File, private val job: LocalJob) : RequestBody() {
    private val base = job.bytesDone
    override fun contentType(): MediaType? = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = f.length()
    override fun writeTo(sink: BufferedSink) {
        job.bytesDone = base
        f.source().use { src ->
            while (true) {
                if (job.cancelled) throw JobCancelled()
                val n = src.read(sink.buffer, 65536L)
                if (n < 0L) break
                sink.emitCompleteSegments()
                job.bytesDone += n
            }
        }
    }
}

/** Job id "L-..." = phone wali (app ke andar), baaki server ki. */
object Xfers {
    fun isLocalId(id: String) = id.startsWith("L-")
    fun status(id: String): XferJob = if (isLocalId(id)) LocalJobs.status(id) else Xfer.status(id)
    fun cancel(id: String) {
        if (isLocalId(id)) LocalJobs.cancel(id) else Xfer.cancel(id)
    }
}

object LocalJobs {
    private val pool = Executors.newFixedThreadPool(2)
    private val jobs = ConcurrentHashMap<String, LocalJob>()
    private val counter = AtomicInteger(0)

    /** Clipboard / destination me phone shamil ho to ye job app khud chalati hai. */
    fun involvesLocal(items: List<ClipItem>, dest: Store): Boolean =
        dest == Store.LOCAL || items.any { it.store == Store.LOCAL }

    fun status(id: String): XferJob =
        jobs[id]?.snap() ?: throw XferException(404, "Job nahi mili")

    fun cancel(id: String) {
        jobs[id]?.cancelled = true
    }

    fun list(): List<XferJob> = jobs.values.map { it.snap() }.sortedByDescending { it.created }

    fun start(ctx: Context, items: List<ClipItem>, dest: Store, destPath: String, move: Boolean, onConflict: String): String {
        val app = ctx.applicationContext
        val id = "L-" + System.currentTimeMillis() + "-" + counter.incrementAndGet()
        val job = LocalJob(id, items, dest, destPath, move, onConflict)
        // purani khatam jobs hata do (last 20 rakho)
        val old = jobs.values.filter { !it.active }.sortedByDescending { it.created }.drop(20)
        for (o in old) jobs.remove(o.id)
        jobs[id] = job
        try {
            ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java))
        } catch (e: Throwable) {
        }
        pool.execute {
            try {
                run(job)
            } finally {
                if (jobs.values.none { it.active }) {
                    try {
                        app.stopService(Intent(app, TransferService::class.java))
                    } catch (e: Throwable) {
                    }
                }
            }
        }
        return id
    }

    private fun run(job: LocalJob) {
        job.state = "running"
        try {
            for (ci in job.items) {
                if (job.cancelled) break
                plan(job, Storages.urlOf(ci.store, ci.path, ci.isDir), ci.isDir, ci.size)
            }
            val destDir = Storages.urlOf(job.destStore, job.destPath, true)
            val taken = existingNames(destDir)
            for (ci in job.items) {
                if (job.cancelled) break
                val srcUrl = Storages.urlOf(ci.store, ci.path, ci.isDir)
                try {
                    transfer(job, ci.store, srcUrl, ci.name, ci.isDir, ci.size, destDir, taken)
                } catch (e: JobCancelled) {
                    break
                } catch (e: DavAuthException) {
                    job.filesFailed++
                    job.errors.add("Login galat / expire")
                    break
                } catch (e: Exception) {
                    job.filesFailed++
                    job.errors.add(ci.name + ": " + (e.message ?: "error"))
                }
            }
        } catch (e: JobCancelled) {
        } catch (e: Throwable) {
            job.errors.add(e.message ?: "error")
        }
        job.current = ""
        job.state = when {
            job.cancelled -> "cancelled"
            job.filesFailed > 0 || job.errors.isNotEmpty() -> "error"
            else -> "done"
        }
        job.finished = System.currentTimeMillis() / 1000
    }

    /** Pehle total files / bytes gino taaki progress % dikhe. */
    private fun plan(job: LocalJob, url: String, isDir: Boolean, size: Long) {
        if (job.cancelled) return
        if (!isDir) {
            job.filesTotal++
            job.bytesTotal += size
            return
        }
        val kids = try {
            Fs.list(url)
        } catch (e: DavAuthException) {
            throw e
        } catch (e: Exception) {
            job.errors.add("List nahi hui: " + LocalFs.nameOf(url))
            return
        }
        for (k in kids) plan(job, k.url, k.isDir, k.size)
    }

    private fun existingNames(dirUrl: String): MutableSet<String> =
        try {
            Fs.list(dirUrl).mapTo(HashSet()) { it.name }
        } catch (e: Exception) {
            HashSet()
        }

    private fun uniqueName(name: String, isDir: Boolean, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (("$base ($n)$ext") in taken) n++
        return "$base ($n)$ext"
    }

    /** true = poora ho gaya (skip / fail nahi). Move me sirf tab source hatta hai. */
    private fun transfer(
        job: LocalJob,
        srcStore: Store,
        srcUrl: String,
        name: String,
        isDir: Boolean,
        size: Long,
        destDirUrl: String,
        taken: MutableSet<String>
    ): Boolean {
        if (job.cancelled) throw JobCancelled()
        val destLocal = job.destStore == Store.LOCAL
        var target = name
        val exists = name in taken
        if (exists) {
            if (isDir) {
                if (job.onConflict == "rename") target = uniqueName(name, true, taken)
            } else {
                when (job.onConflict) {
                    "skip" -> {
                        job.filesSkipped++
                        job.bytesDone += size
                        return false
                    }
                    "rename" -> target = uniqueName(name, false, taken)
                    else -> {
                    }
                }
            }
        }
        val merging = exists && target == name

        // phone -> phone move: seedha rename (instant), agar same storage ho
        if (job.move && srcStore == Store.LOCAL && destLocal && !merging) {
            val src = LocalFs.fileOf(srcUrl)
            val dst = File(LocalFs.fileOf(destDirUrl), target)
            if (!dst.exists()) {
                val (n, b) = LocalFs.countTree(src)
                if (src.renameTo(dst)) {
                    job.filesDone += n
                    job.bytesDone += b
                    taken.add(target)
                    return true
                }
            }
        }

        if (isDir) {
            val newDirUrl: String
            if (destLocal) {
                val d = File(LocalFs.fileOf(destDirUrl), target)
                if (!merging) {
                    if (!d.mkdirs()) throw IOException("Folder nahi bana: $target")
                } else if (!d.isDirectory) {
                    throw IOException("'$target' file hai, folder nahi")
                }
                newDirUrl = LocalFs.urlOf(d, true)
            } else {
                val u = Dav.withSlash(destDirUrl) + Uri.encode(target) + "/"
                if (!merging) Dav.mkcol(u)
                newDirUrl = u
            }
            val kids = Fs.list(srcUrl)
            val childTaken: MutableSet<String> = if (merging) existingNames(newDirUrl) else HashSet()
            var ok = true
            for (k in kids) {
                if (job.cancelled) throw JobCancelled()
                try {
                    if (!transfer(job, srcStore, k.url, k.name, k.isDir, k.size, newDirUrl, childTaken)) ok = false
                } catch (e: JobCancelled) {
                    throw e
                } catch (e: DavAuthException) {
                    throw e
                } catch (e: Exception) {
                    job.filesFailed++
                    job.errors.add(k.name + ": " + (e.message ?: "error"))
                    ok = false
                }
            }
            taken.add(target)
            if (ok && job.move) {
                try {
                    Fs.delete(srcUrl)
                } catch (e: Exception) {
                    job.errors.add("Source folder nahi hata: $name")
                }
            }
            return ok
        }

        job.current = name
        copyFile(job, srcStore, srcUrl, destDirUrl, target, merging)
        job.filesDone++
        taken.add(target)
        if (job.move) {
            try {
                Fs.delete(srcUrl)
            } catch (e: Exception) {
                job.errors.add("Copy ho gaya, source nahi hata: $name")
            }
        }
        return true
    }

    private fun copyFile(job: LocalJob, srcStore: Store, srcUrl: String, destDirUrl: String, destName: String, overwrite: Boolean) {
        val srcLocal = srcStore == Store.LOCAL
        if (job.destStore == Store.LOCAL) {
            val dest = File(LocalFs.fileOf(destDirUrl), destName)
            val tmp = File(dest.parentFile, "$destName.part")
            try {
                if (srcLocal) {
                    FileInputStream(LocalFs.fileOf(srcUrl)).use { ins ->
                        FileOutputStream(tmp).use { out -> pump(job, ins, out) }
                    }
                } else {
                    val req = Request.Builder().url(srcUrl).build()
                    Dav.client.newCall(req).execute().use { r ->
                        if (r.code == 401) throw DavAuthException()
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val body = r.body ?: throw IOException("khaali jawab")
                        body.byteStream().use { ins ->
                            FileOutputStream(tmp).use { out -> pump(job, ins, out) }
                        }
                    }
                }
                if (overwrite && dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("File save nahi hui")
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        } else {
            // phone -> server (AIC / HF)
            val f = LocalFs.fileOf(srcUrl)
            Dav.put(Dav.withSlash(destDirUrl) + Uri.encode(destName), CountingFileBody(f, job))
        }
    }

    private fun pump(job: LocalJob, ins: InputStream, out: OutputStream) {
        val buf = ByteArray(128 * 1024)
        while (true) {
            if (job.cancelled) throw JobCancelled()
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            job.bytesDone += n
        }
    }
}
