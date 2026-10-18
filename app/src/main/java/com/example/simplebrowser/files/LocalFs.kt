package com.example.simplebrowser.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.util.Locale

/** Phone ki local storage (File API). Entry.url = "file:///storage/emulated/0/..." (folder ho to trailing "/"). */
object LocalFs {

    fun isLocal(url: String): Boolean = url.startsWith("file:")

    fun rootDir(): File = Environment.getExternalStorageDirectory()

    /**
     * Ek removable storage: SD card, USB pendrive (OTG) ya card reader, kisi bhi halat me
     * (mounted, check ho rahi, khul nahi rahi, bina eject nikali gayi...).
     */
    class Vol(
        var label: String,
        val root: File,
        val usb: Boolean,
        val readOnly: Boolean,
        val uuid: String? = null,
        val state: String = Environment.MEDIA_MOUNTED,
        /** "FAT32", "exFAT", "NTFS", "ext4", "FAT/exFAT" (pakka nahi) ya "?" */
        val fs: String = "?",
        /** false = Android is drive par likhne nahi de raha (jaise purane Android par SD/USB root) */
        val writable: Boolean = true
    ) {
        val icon: String get() = if (usb) "🔌" else "💾"
        val short: String get() = if (usb) "USB" else "SD"
        val kind: String get() = if (usb) "USB pendrive" else "SD card"
        val mounted: Boolean
            get() = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
        val canWrite: Boolean get() = mounted && !readOnly && writable

        /** FAT32 par ek file 4 GB - 1 byte se badi nahi ho sakti. Baaki (ya pata nahi) = koi limit nahi maani. */
        val maxFileBytes: Long get() = if (fs == "FAT32") 4L * 1024 * 1024 * 1024 - 1 else Long.MAX_VALUE

        /** Mounted na ho to ek line me wajah. */
        fun stateText(): String = when (state) {
            Environment.MEDIA_CHECKING -> "Check ho rahi hai…"
            Environment.MEDIA_UNMOUNTABLE, Environment.MEDIA_NOFS ->
                "❌ Drive khul nahi sakti: filesystem support nahi / kharab (NTFS, RAW?). Computer par format karo (exFAT / FAT32)"
            Environment.MEDIA_EJECTING -> "Eject ho rahi hai…"
            Environment.MEDIA_BAD_REMOVAL -> "⚠ Bina eject kiye nikali gayi: dobara lagao"
            Environment.MEDIA_SHARED -> "Computer ke saath share mode me hai"
            Environment.MEDIA_UNMOUNTED -> "Mount nahi hai: dabao, Settings se mount karo"
            else -> "Mount nahi hai"
        }
    }

    // ───────────────────────── detection ─────────────────────────

    /** Kitne USB mass-storage device (pendrive / card reader / hard disk) abhi OTG par lage hain. */
    fun usbStorageCount(ctx: Context): Int {
        return try {
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            var n = 0
            for (d in um.deviceList.values) {
                for (i in 0 until d.interfaceCount) {
                    if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                        n++
                        break
                    }
                }
            }
            n
        } catch (e: Exception) {
            0
        }
    }

    private val sdWords = Regex("(^|[^a-z])sd([^a-z]|$)|sd.?card|memory card|micro.?sd|tf card", RegexOption.IGNORE_CASE)
    private val usbWords = Regex("usb|otg|pen.?drive|flash|u.?disk|thumb|stick|drive|disk", RegexOption.IGNORE_CASE)

    /** Naam + OTG me USB storage lagi hone se tay karta hai ki ye USB hai ya SD. */
    private fun isUsbVolume(desc: String, usbMsc: Int): Boolean {
        if (sdWords.containsMatchIn(desc)) return false
        if (usbWords.containsMatchIn(desc)) return true
        return usbMsc > 0           // naam se pata nahi chala: OTG par storage lagi hai to USB maano
    }

    /** /proc/mounts se filesystem. FAT32 par 4 GB limit ke liye zaroori. */
    private fun fsOf(uuid: String?, root: File): String {
        try {
            val key = uuid?.lowercase(Locale.ROOT)
            for (l in File("/proc/mounts").readLines()) {
                val p = l.split(' ')
                if (p.size < 3) continue
                val mp = p[1]
                val hit = mp == root.absolutePath || (key != null && mp.lowercase(Locale.ROOT).endsWith("/$key"))
                if (!hit) continue
                when (p[2].lowercase(Locale.ROOT)) {
                    "vfat", "msdos" -> return "FAT32"
                    "exfat", "texfat" -> return "exFAT"
                    "ntfs", "ntfs3", "tntfs" -> return "NTFS"
                    "fuseblk" -> return "NTFS/exFAT"
                    "ext4", "ext3", "ext2" -> return "ext4"
                    "f2fs" -> return "f2fs"
                    "sdfat" -> return "FAT/exFAT"
                    else -> { }          // fuse / sdcardfs wrapper: asli filesystem agli line me hoti hai
                }
            }
        } catch (e: Exception) { }
        // UUID "ABCD-1234" jaisi ho to FAT ya exFAT hi hoti hai
        if (uuid != null && Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(uuid)) return "FAT/exFAT"
        return "?"
    }

    private fun dirOf(v: StorageVolume): File? {
        var dir: File? = null
        if (Build.VERSION.SDK_INT >= 30) dir = v.directory
        if (dir == null) {
            try {
                dir = File(v.javaClass.getMethod("getPath").invoke(v) as String)
            } catch (e: Exception) { }
        }
        return dir
    }

    /**
     * Saari removable storages, kisi bhi halat me (mounted + check ho rahi + khul nahi rahi...).
     * Khaali slot (MEDIA_REMOVED) shamil nahi.
     */
    fun allRemovable(ctx: Context): List<Vol> {
        val out = ArrayList<Vol>()
        val seen = HashSet<String>()
        val usbMsc = usbStorageCount(ctx)
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                if (v.isPrimary || !v.isRemovable) continue
                val st = v.state
                if (st == Environment.MEDIA_REMOVED) continue
                val mounted = st == Environment.MEDIA_MOUNTED || st == Environment.MEDIA_MOUNTED_READ_ONLY
                val uuid = v.uuid
                var dir = dirOf(v)
                if (dir == null) {
                    if (mounted) continue
                    dir = File("/storage/" + (uuid ?: "unknown"))
                }
                if (!seen.add(uuid ?: dir.absolutePath)) continue
                val desc = try { v.getDescription(ctx) ?: "" } catch (e: Exception) { "" }
                val usb = isUsbVolume(desc, usbMsc)
                val ro = st == Environment.MEDIA_MOUNTED_READ_ONLY
                val fs = if (mounted) fsOf(uuid, dir) else "?"
                val writable = mounted && !ro && try { dir.canWrite() } catch (e: Exception) { false }
                out.add(
                    Vol(desc.ifBlank { if (usb) "USB drive" else "SD card" }, dir, usb, ro, uuid, st, fs, writable)
                )
            }
        } catch (e: Exception) { }
        // fallback (purane Android): app ke external dirs se volume ka root
        try {
            val prim = rootDir().absolutePath
            for (d in ctx.getExternalFilesDirs(null)) {
                if (d == null) continue
                val p = d.absolutePath
                val i = p.indexOf("/Android/data")
                if (i <= 0) continue
                val root = File(p.substring(0, i))
                if (root.absolutePath != prim && Environment.isExternalStorageRemovable(d) && seen.add(root.absolutePath)) {
                    val usb = usbMsc > 0
                    out.add(
                        Vol(
                            if (usb) "USB drive" else "SD card", root, usb, false, null,
                            Environment.MEDIA_MOUNTED, fsOf(null, root), try { root.canWrite() } catch (e: Exception) { false }
                        )
                    )
                }
            }
        } catch (e: Exception) { }
        // ek jaise naam wali do drive ho to UUID jod do taaki alag pehchaani jayein
        for ((_, same) in out.groupBy { it.label }) {
            if (same.size > 1) for (v in same) v.label = v.label + " (" + (v.uuid ?: v.root.name) + ")"
        }
        return out
    }

    /** Abhi lagi aur mounted storages (SD card + pendrive + card reader). Khaali list = kuch nahi lagi. */
    fun removableVolumes(ctx: Context): List<Vol> = allRemovable(ctx).filter { it.mounted }

    /** USB storage device lagi hai par Android ne koi volume banaya hi nahi (unsupported / RAW filesystem). */
    fun usbWithoutVolume(ctx: Context, vols: List<Vol>): Boolean =
        usbStorageCount(ctx) > vols.count { it.usb }

    /** Ye file / folder kis removable drive par hai (mounted drive me); phone ki storage = null. */
    fun volumeFor(ctx: Context, f: File): Vol? {
        val p = f.absolutePath
        return removableVolumes(ctx).firstOrNull {
            val r = it.root.absolutePath
            p == r || p.startsWith("$r/")
        }
    }

    /** true = path kisi aisi removable drive ka hai jo ab mounted nahi (drive nikal gayi / unmount ho gayi). */
    fun underMissingVolume(ctx: Context, f: File): Boolean {
        val p = f.absolutePath
        if (!p.startsWith("/storage/")) return false
        val second = p.removePrefix("/storage/").substringBefore('/')
        if (second == "emulated" || second == "self" || second.isEmpty()) return false
        val root = "/storage/$second"
        return removableVolumes(ctx).none {
            val r = it.root.absolutePath
            r == root || p.startsWith("$r/")
        }
    }

    /** Storage ki jaankari (dialog ke liye). */
    fun infoText(v: Vol): String {
        val sb = StringBuilder()
        sb.append("Type: ").append(v.kind).append('\n')
        sb.append("Filesystem: ").append(if (v.fs == "?") "pata nahi" else v.fs).append('\n')
        if (v.uuid != null) sb.append("ID: ").append(v.uuid).append('\n')
        if (v.mounted) {
            try {
                val sf = StatFs(v.root.path)
                val total = sf.totalBytes
                val free = sf.availableBytes
                val used = (total - free).coerceAtLeast(0L)
                val pct = if (total > 0) (used * 100 / total).toInt() else 0
                sb.append("Total: ").append(fmtSize(total)).append('\n')
                sb.append("Use hui: ").append(fmtSize(used)).append(" (").append(pct).append("%)\n")
                sb.append("Khaali: ").append(fmtSize(free)).append('\n')
            } catch (e: Exception) { }
            sb.append("Access: ").append(
                when {
                    v.readOnly -> "sirf padh sakte hain (read-only)"
                    !v.writable -> "sirf padh sakte hain (Android is drive par likhne nahi deta)"
                    else -> "padh + likh sakte hain"
                }
            ).append('\n')
            if (v.fs == "FAT32") sb.append("⚠ FAT32: 4 GB se badi file is drive par nahi ja sakti (exFAT me format karo)\n")
        } else {
            sb.append(v.stateText()).append('\n')
        }
        sb.append("Path: ").append(v.root.path)
        return sb.toString()
    }

    /** Android ki storage settings (yahin se drive Eject / Mount hoti hai). */
    fun openStorageSettings(ctx: Context) {
        val acts = arrayOf(
            Settings.ACTION_MEMORY_CARD_SETTINGS,
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            Settings.ACTION_SETTINGS
        )
        for (a in acts) {
            try {
                ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) { }
        }
    }

    /**
     * SD card (removable, mounted) ka root folder; nahi hai to null. (Purana: sirf pehli.)
     * Android 11+ par "All files access" me SD card bhi aa jata hai (alag permission nahi).
     */
    fun sdRoot(ctx: Context): File? {
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                if (v.isPrimary || !v.isRemovable || v.state != Environment.MEDIA_MOUNTED) continue
                if (Build.VERSION.SDK_INT >= 30) {
                    val d = v.directory
                    if (d != null) return d
                }
            }
        } catch (e: Exception) { }
        // purane Android / fallback: app ke external dirs se volume ka root nikaalo
        try {
            val prim = rootDir().absolutePath
            for (d in ctx.getExternalFilesDirs(null)) {
                if (d == null) continue
                val p = d.absolutePath
                val i = p.indexOf("/Android/data")
                if (i <= 0) continue
                val root = File(p.substring(0, i))
                if (root.absolutePath != prim && Environment.isExternalStorageRemovable(d)) return root
            }
        } catch (e: Exception) { }
        return null
    }

    fun urlOf(f: File, dir: Boolean): String {
        val s = Uri.fromFile(f).toString()
        return if (dir && !s.endsWith("/")) "$s/" else s
    }

    fun rootUrl(): String = urlOf(rootDir(), true)

    fun fileOf(url: String): File {
        val p = Uri.parse(url).path ?: throw IOException("path galat")
        return File(p)
    }

    /** All-files access (Android 11+) ya storage permission (Android 10 se neeche). */
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun list(dirUrl: String): List<Entry> {
        val d = fileOf(dirUrl)
        val arr = d.listFiles()
            ?: throw IOException("Folder nahi khul raha (permission ya Android ki restriction)")
        val out = ArrayList<Entry>(arr.size)
        for (f in arr) {
            val dir = f.isDirectory
            out.add(Entry(f.name, urlOf(f, dir), dir, if (dir) 0L else f.length(), f.lastModified()))
        }
        return out
    }

    /** Folder ke andar total (files, bytes). */
    fun countTree(f: File): Pair<Int, Long> {
        if (!f.isDirectory) return Pair(1, f.length())
        var n = 0
        var b = 0L
        val kids = f.listFiles() ?: return Pair(0, 0L)
        for (k in kids) {
            val (a, c) = countTree(k)
            n += a
            b += c
        }
        return Pair(n, b)
    }

    fun nameOf(url: String): String = Uri.decode(url.trimEnd('/').substringAfterLast('/'))
}

/** Server (WebDAV) aur phone, dono par ek jaise file operations. */
object Fs {
    fun list(url: String): List<Entry> =
        if (LocalFs.isLocal(url)) LocalFs.list(url) else Dav.list(url)

    fun mkdir(dirUrl: String, name: String) {
        if (LocalFs.isLocal(dirUrl)) {
            val d = File(LocalFs.fileOf(dirUrl), name)
            if (d.exists()) throw IOException("Is naam ka item pehle se hai")
            if (!d.mkdirs()) throw IOException("Folder nahi bana")
        } else {
            Dav.mkcol(Dav.withSlash(dirUrl) + Uri.encode(name))
        }
    }

    fun rename(e: Entry, dirUrl: String, newName: String) {
        if (LocalFs.isLocal(e.url)) {
            val src = LocalFs.fileOf(e.url)
            val dst = File(src.parentFile, newName)
            if (dst.exists()) throw IOException("Is naam ka item pehle se hai")
            if (!src.renameTo(dst)) throw IOException("Rename nahi hua")
        } else {
            Dav.move(e.url, Dav.withSlash(dirUrl) + Uri.encode(newName) + (if (e.isDir) "/" else ""))
        }
    }

    fun delete(url: String) {
        if (LocalFs.isLocal(url)) {
            val f = LocalFs.fileOf(url)
            if (!f.deleteRecursively()) throw IOException("Delete nahi hua: ${f.name}")
        } else {
            Dav.delete(url)
        }
    }
}
