package com.example.simplebrowser.files

import android.content.Context
import com.example.simplebrowser.DlSettings
import com.example.simplebrowser.ServerSync
import okhttp3.Credentials

/** Server URL / login + chhoti settings. Password app ke private storage me rehta hai. */
object Session {
    const val DEFAULT_URL = "https://u8cyy.aiccloud.online/dav/"

    @Volatile var baseUrl: String = DEFAULT_URL
    @Volatile var user: String = ""
    @Volatile var pass: String = ""

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("myfiles", Context.MODE_PRIVATE)

    private fun posPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("positions", Context.MODE_PRIVATE)

    /** Login Lion ke ServerSync wale settings (ss_url / ss_user / ss_pass) se: browser aur file manager ka ek hi login. */
    fun load(ctx: Context) {
        val a = ctx.applicationContext
        baseUrl = DlSettings.getStr(a, ServerSync.K_URL, DEFAULT_URL).ifBlank { DEFAULT_URL }
        user = DlSettings.getStr(a, ServerSync.K_USER, "")
        pass = DlSettings.getStr(a, ServerSync.K_PASS, "")
    }

    fun set(url: String, u: String, p: String) {
        baseUrl = url
        user = u
        pass = p
    }

    fun persist(ctx: Context) {
        val a = ctx.applicationContext
        DlSettings.setStr(a, ServerSync.K_URL, baseUrl)
        DlSettings.setStr(a, ServerSync.K_USER, user)
        DlSettings.setStr(a, ServerSync.K_PASS, pass)
    }

    fun clearPass(ctx: Context) {
        pass = ""
        clearClip()
        DlSettings.setStr(ctx.applicationContext, ServerSync.K_PASS, "")
    }

    /** Storage ka root URL (AIC = `/dav/`, HF = `/hf/`), login URL se nikalta hai. */
    fun rootFor(store: Store): String = Storages.root(store)

    // Copy / Move clipboard (sirf memory me: app band hui to chali jaati hai; server ki job alag chalti rehti hai)
    @Volatile var clip: List<ClipItem> = emptyList()
    @Volatile var clipMove: Boolean = false

    fun setClip(items: List<ClipItem>, move: Boolean) {
        clip = items
        clipMove = move
    }

    fun clearClip() {
        clip = emptyList()
        clipMove = false
    }

    fun hasCreds(): Boolean = user.isNotEmpty() && pass.isNotEmpty()

    fun auth(): String = Credentials.basic(user, pass)

    fun bool(ctx: Context, key: String, def: Boolean): Boolean = sp(ctx).getBoolean(key, def)
    fun setBool(ctx: Context, key: String, v: Boolean) = sp(ctx).edit().putBoolean(key, v).apply()
    fun int(ctx: Context, key: String, def: Int): Int = sp(ctx).getInt(key, def)
    fun setInt(ctx: Context, key: String, v: Int) = sp(ctx).edit().putInt(key, v).apply()

    // Video/audio resume position (ms), key = file URL
    fun getPos(ctx: Context, key: String): Long = posPrefs(ctx).getLong(key, 0L)
    fun setPos(ctx: Context, key: String, ms: Long) = posPrefs(ctx).edit().putLong(key, ms).apply()
}
