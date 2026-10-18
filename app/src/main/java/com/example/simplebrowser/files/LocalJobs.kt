package com.example.simplebrowser.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
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

/** Copy ke beech SD / USB drive nikal gayi: baaki files try karne ka koi fayda nahi, poori job roko. */
private class DriveGone(msg: String) : IOException(msg)

/** Copy ke dauran file is naam se banti hai; poori hone par asli naam me badalti hai (adhoori file asli naam par kabhi nahi). */
private const val PART_EXT = ".lionpart"

/** Phone se judi ek copy / move job (phone↔phone, phone↔server). Server wali jobs `/xfer` par chalti hain. */
class LocalJob(
    val id: String,
    val items: List<ClipItem>,
    val destStore: Store,
    val destPath: String,
    val move: Boolean,
    val onConflict: String,
    /** Paste se pehle compare ke baad har file ka faisla: relative path ("Folder/a.mp4") -> skip | overwrite | rename.
     *  Jo path isme nahi, uske liye [onConflict] chalta hai. */
    val decisions: Map<String, String>? = null
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
    /** Destination agar SD / USB drive hai (FAT32 limit, likhne ki permission, fsync ke liye). */
    @Volatile var destVol: LocalFs.Vol? = null
    @Volatile var app: Context? = null
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

    /** Is drive ([root] = "/storage/XXXX-XXXX") par koi copy / move abhi chal raha hai? */
    fun busyOn(root: String): Boolean {
        val r = root.trimEnd('/')
        fun under(p: String) = p == r || p.startsWith("$r/")
        return jobs.values.any { j ->
            j.active && ((j.destStore == Store.LOCAL && under(j.destPath)) ||
                j.items.any { it.store == Store.LOCAL && under(it.path) })
        }
    }

    fun list(): List<XferJob> = jobs.values.map { it.snap() }.sortedByDescending { it.created }

    fun start(
        ctx: Context, items: List<ClipItem>, dest: Store, destPath: String, move: Boolean, onConflict: String,
        decisions: Map<String, String>? = null
    ): String {
        val app = ctx.applicationContext
        val id = "L-" + System.currentTimeMillis() + "-" + counter.incrementAndGet()
        val job = LocalJob(id, items, dest, destPath, move, onConflict, decisions)
        job.app = app
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
            if (job.destStore == Store.LOCAL) preflight(job, destDir, taken)
            for (ci in job.items) {
                if (job.cancelled) break
                val srcUrl = Storages.urlOf(ci.store, ci.path, ci.isDir)
                try {
                    transfer(job, ci.store, srcUrl, ci.name, ci.isDir, ci.size, destDir, taken, ci.name)
                } catch (e: JobCancelled) {
                    break
                } catch (e: DavAuthException) {
                    job.filesFailed++
                    job.errors.add("Login galat / expire")
                    break
                } catch (e: DriveGone) {
                    job.filesFailed++
                    job.errors.add(e.message ?: "Drive nikal gayi")
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
            val out = HashSet<String>()
            for (e in Fs.list(dirUrl)) {
                if (!e.isDir && e.name.endsWith(PART_EXT)) {
                    // purani adhoori copy (app band hui / drive nikli): 2 min se purani ho to hata do
                    if (LocalFs.isLocal(e.url)) {
                        try {
                            val f = LocalFs.fileOf(e.url)
                            if (System.currentTimeMillis() - f.lastModified() > 120_000L) f.delete()
                        } catch (x: Exception) { }
                    }
                    continue
                }
                out.add(e.name)
            }
            out
        } catch (e: Exception) {
            HashSet()
        }

    /** Copy shuru karne se pehle destination drive ki jaanch: likh sakte hain? jagah hai? Fail ho to job turant ruk jati hai. */
    private fun preflight(job: LocalJob, destDirUrl: String, taken: Set<String>) {
        val a = job.app
        val d = LocalFs.fileOf(destDirUrl)
        if (a != null && LocalFs.underMissingVolume(a, d)) throw DriveGone("Destination drive nikal gayi / mounted nahi hai")
        if (!d.isDirectory) throw IOException("Destination folder nahi mila (drive nikal gayi?)")
        val vol = if (a != null) LocalFs.volumeFor(a, d) else null
        job.destVol = vol
        if (vol != null) {
            if (vol.readOnly) throw IOException("${vol.label} read-only hai: usme likh nahi sakte")
            if (!vol.writable) throw IOException("${vol.label} par Android is version me likhne nahi deta (sirf padh sakte hain)")
        }
        // jagah: same drive par move to rename hota hai, jagah nahi lagti
        fun rootKey(f: java.io.File): String = (if (a != null) LocalFs.volumeFor(a, f)?.root?.absolutePath else null) ?: "primary"
        val sameStorageMove = job.move && job.items.all { it.store == Store.LOCAL && rootKey(java.io.File(it.path)) == rootKey(d) }
        if (!sameStorageMove) {
            val avail = try { StatFs(d.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
            val conflicts = job.items.any { it.name in taken }      // conflict ho to skip / replace se jagah badal sakti hai: file-by-file check
            if (!conflicts && job.bytesTotal > avail) {
                throw IOException("Jagah kam hai: ${fmtSize(job.bytesTotal)} chahiye, sirf ${fmtSize(avail)} khaali")
            }
        }
    }

    /** Low-level IOException ko saaf Hinglish wajah me badalo; drive nikal gayi ho to DriveGone. */
    private fun friendly(job: LocalJob, e: Throwable, src: java.io.File?, dest: java.io.File): Throwable {
        if (e !is IOException || e is JobCancelled || e is DavAuthException || e is DriveGone) return e
        val a = job.app
        if (a != null) {
            if (LocalFs.underMissingVolume(a, dest)) return DriveGone("Drive nikal gayi (ya unmount ho gayi): copy ruk gayi")
            if (src != null && LocalFs.underMissingVolume(a, src)) return DriveGone("Source drive nikal gayi: copy ruk gayi")
        }
        val m = e.message ?: ""
        return when {
            m.contains("EFBIG") || m.contains("File too large", true) ->
                IOException("File itni badi hai ki drive ka filesystem nahi sambhalta (FAT32 par max 4 GB). exFAT me format karo")
            m.contains("ENOSPC") || m.contains("No space", true) -> IOException("Drive me jagah khatam ho gayi")
            m.contains("EROFS") || m.contains("Read-only", true) -> IOException("Drive read-only ho gayi")
            m.contains("EACCES") || m.contains("Permission denied", true) ->
                IOException("Is drive par likhne ki permission nahi (Android restriction)")
            else -> e
        }
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
        taken: MutableSet<String>,
        rel: String
    ): Boolean {
        if (job.cancelled) throw JobCancelled()
        val destLocal = job.destStore == Store.LOCAL
        var target = name
        val exists = name in taken
        if (exists) {
            val act = job.decisions?.get(rel) ?: job.onConflict
            if (isDir) {
                if (act == "rename") target = uniqueName(name, true, taken)
            } else {
                when (act) {
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
                    if (!transfer(job, srcStore, k.url, k.name, k.isDir, k.size, newDirUrl, childTaken, rel + "/" + k.name)) ok = false
                } catch (e: JobCancelled) {
                    throw e
                } catch (e: DavAuthException) {
                    throw e
                } catch (e: DriveGone) {
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
        copyFile(job, srcStore, srcUrl, destDirUrl, target, merging, size)
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

    private fun copyFile(job: LocalJob, srcStore: Store, srcUrl: String, destDirUrl: String, destName: String, overwrite: Boolean, size: Long) {
        val srcLocal = srcStore == Store.LOCAL
        if (job.destStore == Store.LOCAL) {
            val dest = java.io.File(LocalFs.fileOf(destDirUrl), destName)
            val vol = job.destVol
            // FAT32: 4 GB se badi file copy shuru karne se pehle hi roko (warna 4 GB likh ke fail hota)
            if (vol != null && size > vol.maxFileBytes) {
                throw IOException("'$destName' (${fmtSize(size)}) ${vol.fs} drive par nahi ja sakti: 4 GB se badi file nahi chalti. Drive ko exFAT me format karo")
            }
            // is file ke liye jagah
            if (size > 0) {
                val avail = try {
                    StatFs(dest.parentFile!!.path).availableBytes + (if (overwrite && dest.exists()) dest.length() else 0L)
                } catch (e: Exception) { Long.MAX_VALUE }
                if (avail < size) throw IOException("Jagah nahi: '$destName' (${fmtSize(size)}) ke liye sirf ${fmtSize(avail)} khaali")
            }
            val srcFile: java.io.File? = if (srcLocal) LocalFs.fileOf(srcUrl) else null
            val expected = srcFile?.length() ?: -1L
            val tmp = java.io.File(dest.parentFile, destName + PART_EXT)
            try {
                var written = 0L
                if (srcFile != null) {
                    FileInputStream(srcFile).use { ins ->
                        FileOutputStream(tmp).use { out ->
                            written = pump(job, ins, out)
                            out.flush()
                            if (vol != null) out.fd.sync()      // drive par sach me likh do (nikalne par adhoori na rahe)
                        }
                    }
                } else {
                    val req = Request.Builder().url(srcUrl).build()
                    Dav.client.newCall(req).execute().use { r ->
                        if (r.code == 401) throw DavAuthException()
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val body = r.body ?: throw IOException("khaali jawab")
                        body.byteStream().use { ins ->
                            FileOutputStream(tmp).use { out ->
                                written = pump(job, ins, out)
                                out.flush()
                                if (vol != null) out.fd.sync()
                            }
                        }
                    }
                }
                // verify: phone/drive source ho to size bilkul barabar hona chahiye (move me source isi ke baad hatta hai)
                if (expected >= 0 && written != expected) {
                    throw IOException("Copy adhoori rahi ($written / $expected bytes)")
                }
                if (tmp.length() != written) throw IOException("Drive par file poori save nahi hui")
                if (overwrite && dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("File save nahi hui")
            } catch (e: Throwable) {
                try { tmp.delete() } catch (x: Throwable) { }
                throw friendly(job, e, srcFile, dest)
            }
        } else {
            // phone -> server (AIC / HF)
            val f = LocalFs.fileOf(srcUrl)
            try {
                Dav.put(Dav.withSlash(destDirUrl) + Uri.encode(destName), CountingFileBody(f, job))
            } catch (e: Throwable) {
                val a = job.app
                if (a != null && e is IOException && e !is JobCancelled && e !is DavAuthException && LocalFs.underMissingVolume(a, f)) {
                    throw DriveGone("Source drive nikal gayi: upload ruk gaya")
                }
                throw e
            }
        }
    }

    private fun pump(job: LocalJob, ins: InputStream, out: OutputStream): Long {
        val buf = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            if (job.cancelled) throw JobCancelled()
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            job.bytesDone += n
            total += n
        }
        return total
    }
}
