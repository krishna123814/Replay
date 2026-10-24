package com.example.simplebrowser.money

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.simplebrowser.files.Dav
import com.example.simplebrowser.files.Session
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 💵 Money Manager ka data (server wale chart.html jaisa hi blob):
 *   { v:1, cats:[{id,type,name,subs:[{id,name}]}], tx:[{id,type,ts,amount,cat,sub,acct,catName,subName,note}],
 *     accts:[..], actual, budgets{}, est[], t, tg[] }
 * Load : GET  <server>/dav/app_state/moneymgr.json   (File Manager wala hi login)
 * Save : POST <server>/api/local_state_save {kind:"moneymgr", seq, payload:blob}  (server Telegram ke changes merge karta hai)
 * Blob JSONObject hi rakha hai taaki jo fields app nahi jaanta (est, budgets, tg ...) bhi bina chhede wapas chale jaayein.
 */
object MoneyRepo {
    private const val CACHE = "moneymgr_cache.json"
    private val JSON_T = "application/json; charset=utf-8".toMediaType()

    @Volatile var blob: JSONObject = emptyBlob()
    @Volatile var loaded = false
    @Volatile var readOnly = false      // true = server nahi mila, sirf purana cache dikh raha hai
    @Volatile var dirty = false         // true = last save server tak nahi pahuncha
    private var app: Context? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    fun emptyBlob(): JSONObject {
        val o = JSONObject()
        o.put("v", 1)
        o.put("cats", JSONArray())
        o.put("tx", JSONArray())
        o.put("t", 0)
        return o
    }

    private fun origin(): String? {
        val u = Session.baseUrl.toHttpUrlOrNull() ?: return null
        return u.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }

    private fun cacheFile(c: Context) = File(c.filesDir, CACHE)

    private fun writeCache(c: Context, txt: String) {
        try { cacheFile(c).writeText(txt) } catch (e: Exception) { }
    }

    private fun readCache(c: Context): JSONObject? {
        return try {
            val f = cacheFile(c)
            if (!f.exists()) null else JSONObject(f.readText())
        } catch (e: Exception) { null }
    }

    // ───────────────────────── load / save ─────────────────────────

    fun load(ctx: Context, cb: (Boolean, String) -> Unit) {
        val a = ctx.applicationContext
        app = a
        io.execute {
            var ok = false
            var msg = ""
            try {
                if (!Session.hasCreds()) Session.load(a)
                if (!Session.hasCreds()) throw IOException("Pehle File Manager me login karo")
                val org = origin() ?: throw IOException("Server URL galat")
                val req = Request.Builder().url(org + "dav/app_state/moneymgr.json").get().build()
                var code = 0
                var txt = ""
                Dav.client.newCall(req).execute().use { r ->
                    code = r.code
                    txt = r.body?.string() ?: ""
                }
                if (code == 404) {
                    blob = emptyBlob(); loaded = true; readOnly = false; dirty = false; ok = true
                } else if (code == 401) {
                    throw IOException("Login galat (401)")
                } else if (code !in 200..299) {
                    throw IOException("HTTP $code")
                } else {
                    val o = JSONObject(txt)
                    if (o.optJSONArray("cats") == null || o.optJSONArray("tx") == null) throw IOException("Data ka shape galat")
                    blob = o; loaded = true; readOnly = false; dirty = false; ok = true
                    writeCache(a, txt)
                }
            } catch (e: Exception) {
                msg = e.message ?: "error"
                val c = readCache(a)
                if (c != null) { blob = c; loaded = true; readOnly = true }
            }
            ui.post { cb(ok, msg) }
        }
    }

    fun touch() { blob.put("t", System.currentTimeMillis()) }

    fun save(cb: ((Boolean, String) -> Unit)? = null) {
        if (readOnly) { cb?.invoke(false, "Offline cache hai, save nahi ho sakta"); return }
        val snap = blob.toString()
        io.execute {
            var ok = false
            var msg = ""
            try {
                val org = origin() ?: throw IOException("Server URL galat")
                val body = JSONObject()
                body.put("kind", "moneymgr")
                body.put("seq", System.currentTimeMillis())
                body.put("payload", JSONObject(snap))
                val req = Request.Builder().url(org + "api/local_state_save")
                    .post(body.toString().toRequestBody(JSON_T)).build()
                var code = 0
                var txt = ""
                Dav.client.newCall(req).execute().use { r ->
                    code = r.code
                    txt = r.body?.string() ?: ""
                }
                if (code != 200) throw IOException("HTTP $code")
                val j = JSONObject(txt)
                if (!j.optBoolean("ok", false)) throw IOException(j.optString("msg", "server ne mana kiya"))
                ok = true
                dirty = false
                app?.let { writeCache(it, snap) }
            } catch (e: Exception) {
                msg = e.message ?: "error"
                dirty = true
            }
            ui.post { cb?.invoke(ok, msg) }
        }
    }

    // ───────────────────────── read helpers ─────────────────────────

    private fun list(k: String): List<JSONObject> {
        val a = blob.optJSONArray(k) ?: return emptyList()
        val l = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i)
            if (o != null) l.add(o)
        }
        return l
    }

    fun cats(): List<JSONObject> = list("cats")
    fun txs(): List<JSONObject> = list("tx")

    /** 'rich kids' category ki entry? (iski counting total/balance me nahi judti, sirf Analysis me) */
    fun isRk(t: JSONObject): Boolean {
        val c = catOf(t.optString("cat"))
        return rkNorm(if (c != null) c.optString("name") else t.optString("catName")) == "rich kids"
    }

    /** rich kids ko chhodke baaki sab entries (totals ke liye) */
    fun txsN(): List<JSONObject> = txs().filter { !isRk(it) }
    fun catsOf(type: String): List<JSONObject> = cats().filter { it.optString("type") == type }
    fun catOf(id: String): JSONObject? = if (id.isEmpty()) null else cats().firstOrNull { it.optString("id") == id }
    fun subsOf(c: JSONObject?): List<JSONObject> {
        val a = c?.optJSONArray("subs") ?: return emptyList()
        val l = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i)
            if (o != null) l.add(o)
        }
        return l
    }
    fun subOf(c: JSONObject?, id: String): JSONObject? = if (id.isEmpty()) null else subsOf(c).firstOrNull { it.optString("id") == id }

    fun catName(t: JSONObject): String {
        val c = catOf(t.optString("cat"))
        val n = if (c != null) c.optString("name") else t.optString("catName")
        return if (n.isEmpty()) "?" else n
    }

    fun subName(t: JSONObject): String {
        val sid = t.optString("sub")
        if (sid.isEmpty()) return ""
        val s = subOf(catOf(t.optString("cat")), sid)
        return if (s != null) s.optString("name") else t.optString("subName")
    }

    fun accts(): List<String> {
        val l = ArrayList<String>()
        fun add(x: String) { val s = x.trim(); if (s.isNotEmpty() && !l.contains(s)) l.add(s) }
        add("Cash")
        val a = blob.optJSONArray("accts")
        if (a != null) for (i in 0 until a.length()) add(a.optString(i))
        for (t in txs()) add(t.optString("acct"))
        return l
    }

    fun newId(p: String): String {
        val r = (Math.random() * 1679616).toInt().toString(36)
        return p + System.currentTimeMillis().toString(36) + r
    }

    private val nf: NumberFormat by lazy {
        val f = NumberFormat.getNumberInstance(Locale("en", "IN"))
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        f
    }

    fun fmt(n: Double): String = "₹" + nf.format(n)

    class Sum { var i = 0.0; var e = 0.0 }

    /** mo = null -> poora saal */
    fun sum(y: Int, mo: Int?): Sum {
        val s = Sum()
        val cal = Calendar.getInstance()
        for (t in txs()) {
            if (isRk(t)) continue
            cal.timeInMillis = t.optLong("ts")
            if (cal.get(Calendar.YEAR) == y && (mo == null || cal.get(Calendar.MONTH) == mo)) {
                if (t.optString("type") == "income") s.i += t.optDouble("amount", 0.0) else s.e += t.optDouble("amount", 0.0)
            }
        }
        return s
    }

    fun sumAll(): Sum {
        val s = Sum()
        for (t in txs()) {
            if (isRk(t)) continue
            if (t.optString("type") == "income") s.i += t.optDouble("amount", 0.0) else s.e += t.optDouble("amount", 0.0)
        }
        return s
    }

    fun actual(): Double? {
        if (!blob.has("actual") || blob.isNull("actual")) return null
        val v = blob.optDouble("actual", Double.NaN)
        return if (v.isNaN()) null else v
    }

    fun setActual(v: Double?) {
        if (v == null) blob.put("actual", JSONObject.NULL) else blob.put("actual", Math.round(v * 100) / 100.0)
    }

    // ───────────────────────── change helpers (caller baad me touch()+save() kare) ─────────────────────────

    fun addCat(type: String, name: String): JSONObject {
        val ex = catsOf(type).firstOrNull { it.optString("name").equals(name, ignoreCase = true) }
        if (ex != null) return ex
        val c = JSONObject()
        c.put("id", newId("c")); c.put("type", type); c.put("name", name); c.put("subs", JSONArray())
        blob.getJSONArray("cats").put(c)
        return c
    }

    fun addSub(c: JSONObject, name: String): JSONObject {
        val ex = subsOf(c).firstOrNull { it.optString("name").equals(name, ignoreCase = true) }
        if (ex != null) return ex
        var arr = c.optJSONArray("subs")
        if (arr == null) { arr = JSONArray(); c.put("subs", arr) }
        val s = JSONObject()
        s.put("id", newId("s")); s.put("name", name)
        arr.put(s)
        return s
    }

    fun addAcct(name: String): String {
        val ex = accts().firstOrNull { it.equals(name, ignoreCase = true) }
        if (ex != null) return ex
        var arr = blob.optJSONArray("accts")
        if (arr == null) { arr = JSONArray(); blob.put("accts", arr) }
        arr.put(name)
        return name
    }

    fun renameCat(id: String, name: String) {
        val c = catOf(id) ?: return
        c.put("name", name)
        for (t in txs()) if (t.optString("cat") == id) t.put("catName", name)
    }

    fun renameSub(cid: String, sid: String, name: String) {
        val s = subOf(catOf(cid), sid) ?: return
        s.put("name", name)
        for (t in txs()) if (t.optString("cat") == cid && t.optString("sub") == sid) t.put("subName", name)
    }

    fun deleteCat(id: String) {
        val na = JSONArray()
        for (c in cats()) if (c.optString("id") != id) na.put(c)
        blob.put("cats", na)      // purani entries me catName snapshot bacha rehta hai
    }

    fun deleteSub(cid: String, sid: String) {
        val c = catOf(cid) ?: return
        val na = JSONArray()
        for (s in subsOf(c)) if (s.optString("id") != sid) na.put(s)
        c.put("subs", na)
    }

    /** editId null/empty = nayi entry, warna usi id ko badlo. false = edit wali entry nahi mili. */
    fun putTx(rec: JSONObject, editId: String?): Boolean {
        val arr = blob.getJSONArray("tx")
        if (editId.isNullOrEmpty()) { arr.put(rec); return true }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null && o.optString("id") == editId) { arr.put(i, rec); return true }
        }
        return false
    }

    fun deleteTx(id: String) {
        val na = JSONArray()
        for (t in txs()) if (t.optString("id") != id) na.put(t)
        blob.put("tx", na)
    }

    // ───────────────────────── "rich kids" rule (server wale app jaisa) ─────────────────────────
    // Oct 2026 se har mahine category "rich kids" me Rs 500 ki ek Income entry pehle; tab tak baaki entries block. Iski counting total se alag.

    private val rkStart: Long by lazy {
        val c = Calendar.getInstance()
        c.clear(); c.set(2026, 9, 1)
        c.timeInMillis
    }

    private val rkExpUntil: Long by lazy {
        val c = Calendar.getInstance()
        c.clear(); c.set(2026, 9, 24)
        c.timeInMillis
    }

    fun rkNorm(x: String): String =
        x.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    private fun rkQual(t: JSONObject): Boolean {
        val ty = t.optString("type")
        if (ty != "income" && ty != "expense") return false
        // ₹500 *expense* sirf purani entry (24 Oct 2026 se pehle) valid; ab se ₹500 *income*
        if (ty == "expense" && t.optLong("ts") >= rkExpUntil) return false
        if (Math.round(t.optDouble("amount", 0.0) * 100) != 50000L) return false
        val c = catOf(t.optString("cat"))
        return rkNorm(if (c != null) c.optString("name") else t.optString("catName")) == "rich kids"
    }

    fun rkPending(): Int {
        val now = Calendar.getInstance()
        if (now.timeInMillis < rkStart) return 0
        val need = (now.get(Calendar.YEAR) - 2026) * 12 + now.get(Calendar.MONTH) - 9 + 1
        var have = 0
        for (t in txs()) if (t.optLong("ts") >= rkStart && rkQual(t)) have++
        return Math.max(0, need - have)
    }

    /** Pending ho aur category na ho to bana deta hai. true = blob badla (save karna hai). */
    fun rkEnsure(): Boolean {
        if (rkPending() == 0) return false
        var changed = false
        for (ty in listOf("income", "expense")) {
            if (catsOf(ty).none { rkNorm(it.optString("name")) == "rich kids" }) { addCat(ty, "rich kids"); changed = true }
        }
        return changed
    }
}
