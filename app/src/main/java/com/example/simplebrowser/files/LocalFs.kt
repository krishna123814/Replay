package com.example.simplebrowser.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/** Phone ki local storage (File API). Entry.url = "file:///storage/emulated/0/..." (folder ho to trailing "/"). */
object LocalFs {

    fun isLocal(url: String): Boolean = url.startsWith("file:")

    fun rootDir(): File = Environment.getExternalStorageDirectory()

    /**
     * SD card (removable, mounted) ka root folder; nahi hai to null.
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
