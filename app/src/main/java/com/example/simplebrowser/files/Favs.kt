package com.example.simplebrowser.files

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favourites: kisi bhi storage (AIC / HF / Phone / SD / USB) ka folder ya file.
 * Path storage ke andar ka hota hai ("/movies/new"); Phone / SD ke liye poora absolute path.
 */
object Favs {

    data class Fav(val store: Store, val path: String, val name: String, val isDir: Boolean)

    private const val PREFS = "lion_favs"
    private const val KEY = "items"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun norm(path: String): String {
        val t = path.trimEnd('/')
        return if (t.isEmpty()) "/" else t
    }

    fun key(store: Store, path: String): String = store.id + "|" + norm(path)

    @Synchronized
    fun list(ctx: Context): List<Fav> {
        val out = ArrayList<Fav>()
        try {
            val arr = JSONArray(sp(ctx).getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("p")
                if (p.isEmpty()) continue
                out.add(Fav(Store.from(o.optString("s")), p, o.optString("n"), o.optBoolean("d", true)))
            }
        } catch (e: Exception) { }
        return out
    }

    @Synchronized
    private fun save(ctx: Context, l: List<Fav>) {
        val arr = JSONArray()
        for (f in l) {
            arr.put(JSONObject().put("s", f.store.id).put("p", f.path).put("n", f.name).put("d", f.isDir))
        }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun keys(ctx: Context): Set<String> = list(ctx).map { key(it.store, it.path) }.toHashSet()

    fun has(ctx: Context, store: Store, path: String): Boolean = key(store, path) in keys(ctx)

    @Synchronized
    fun add(ctx: Context, store: Store, path: String, name: String, isDir: Boolean) {
        val l = list(ctx).toMutableList()
        val k = key(store, path)
        if (l.any { key(it.store, it.path) == k }) return
        l.add(Fav(store, norm(path), name, isDir))
        save(ctx, l)
    }

    @Synchronized
    fun remove(ctx: Context, store: Store, path: String) {
        val k = key(store, path)
        save(ctx, list(ctx).filter { key(it.store, it.path) != k })
    }

    /** true = ab favourite hai, false = hata diya. */
    fun toggle(ctx: Context, store: Store, path: String, name: String, isDir: Boolean): Boolean {
        return if (has(ctx, store, path)) {
            remove(ctx, store, path)
            false
        } else {
            add(ctx, store, path, name, isDir)
            true
        }
    }
}
