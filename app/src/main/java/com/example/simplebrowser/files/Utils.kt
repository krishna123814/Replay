package com.example.simplebrowser.files

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Kind { DIR, IMAGE, VIDEO, AUDIO, OTHER }

data class Entry(
    val name: String,
    val url: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    /** Search result: current folder se relative parent folder (khaali = isi folder ka). */
    val sub: String = ""
) {
    val kind: Kind = Kinds.of(name, isDir)
}

object Kinds {
    private val img = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val vid = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv", "wmv", "mpg", "mpeg")
    private val aud = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "mka")

    fun of(name: String, isDir: Boolean): Kind {
        if (isDir) return Kind.DIR
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            ext in img -> Kind.IMAGE
            ext in vid -> Kind.VIDEO
            ext in aud -> Kind.AUDIO
            else -> Kind.OTHER
        }
    }

    fun emoji(k: Kind): String = when (k) {
        Kind.DIR -> "📁"
        Kind.IMAGE -> "🖼"
        Kind.VIDEO -> "🎬"
        Kind.AUDIO -> "🎵"
        Kind.OTHER -> "📄"
    }
}

/** Playback / viewer ke liye in-memory queue (Intent me bada data nahi bhejte). */
object Queue {
    @Volatile var images: List<Entry> = emptyList()
    @Volatile var media: List<Entry> = emptyList()
    @Volatile var mediaIndex: Int = 0
    @Volatile var pending: Boolean = false

    /** video url -> subtitle (.srt/.vtt/.ass) url, jo usi folder me same naam se mili. */
    @Volatile var subs: Map<String, String> = emptyMap()

    /** Viewer se delete / badlav hua: browser wapas aakar listing refresh kare. */
    @Volatile var dirty: Boolean = false
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

fun fmtDate(ms: Long): String {
    if (ms <= 0L) return ""
    return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
}

/** "ep2" < "ep10" jaisa natural sort. */
fun natural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val si = i
            while (i < a.length && a[i].isDigit()) i++
            val sj = j
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(si, i).trimStart('0')
            val nb = b.substring(sj, j).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
