package com.example.simplebrowser.files

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Server ne `/xfer` par error diya (400 galat input, 403 read-only, 429 queue bhari, 503 HF configured nahi...). */
class XferException(val code: Int, msg: String) : IOException(msg)

/** `/xfer/status` ka jawab. */
data class XferJob(
    val id: String,
    val state: String,          // queued | running | done | error | cancelled
    val mode: String,           // copy | move
    val destStore: Store,
    val destPath: String,
    val items: Int,
    val filesTotal: Int,
    val filesDone: Int,
    val filesSkipped: Int,
    val filesFailed: Int,
    val bytesTotal: Long,
    val bytesDone: Long,
    val current: String,
    val errors: List<String>,
    val created: Long,
    val finished: Long
) {
    val active: Boolean get() = state == "queued" || state == "running"

    /** 0..100, ya -1 agar abhi pata nahi (HF ki list ban rahi hai / queue me hai). */
    val pct: Int
        get() = when {
            bytesTotal > 0 -> (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100)
            filesTotal > 0 -> ((filesDone + filesSkipped + filesFailed) * 100 / filesTotal).coerceIn(0, 100)
            state == "done" -> 100
            else -> -1
        }

    fun title(): String = (if (mode == "move") "Move" else "Copy") + " → " + destStore.label + ":" + destPath

    fun stateLabel(): String = when (state) {
        "queued" -> "⏳ Queue me"
        "running" -> "▶ Chal raha hai"
        "done" -> "✅ Ho gaya"
        "cancelled" -> "⛔ Cancel hua"
        else -> "⚠ Error"
    }

    fun line(): String {
        val sb = StringBuilder()
        sb.append("$items item")
        if (filesTotal > 0) sb.append(" · files $filesDone/$filesTotal")
        if (filesSkipped > 0) sb.append(" · skip $filesSkipped")
        if (filesFailed > 0) sb.append(" · fail $filesFailed")
        if (bytesTotal > 0) sb.append(" · ${fmtSize(bytesDone)} / ${fmtSize(bytesTotal)}")
        return sb.toString()
    }
}

/**
 * `/xfer` ka client (copy / move server par chalta hai, phone se bytes nahi guzarte).
 * Sab calls blocking hain: background thread se bulao. Basic-auth `Dav.client` ke interceptor se lagta hai.
 */
object Xfer {
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun call(req: Request): JSONObject {
        Dav.client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (r.code == 401) throw DavAuthException()
            val obj = try {
                JSONObject(txt)
            } catch (e: Exception) {
                null
            }
            if (!r.isSuccessful) {
                val msg = obj?.optString("error").orEmpty().ifEmpty {
                    if (r.code == 404) "Server par /xfer nahi mila (server update karo)" else "HTTP ${r.code}"
                }
                throw XferException(r.code, msg)
            }
            return obj ?: throw IOException("server ka jawab samajh nahi aaya")
        }
    }

    private fun parse(o: JSONObject): XferJob {
        val d = o.optJSONObject("dest")
        val errs = ArrayList<String>()
        val ea = o.optJSONArray("errors")
        if (ea != null) {
            for (i in 0 until ea.length()) errs.add(ea.optString(i))
        }
        return XferJob(
            id = o.optString("id"),
            state = o.optString("state"),
            mode = o.optString("mode", "copy"),
            destStore = Store.from(d?.optString("store")),
            destPath = d?.optString("path") ?: "/",
            items = o.optInt("items"),
            filesTotal = o.optInt("files_total"),
            filesDone = o.optInt("files_done"),
            filesSkipped = o.optInt("files_skipped"),
            filesFailed = o.optInt("files_failed"),
            bytesTotal = o.optLong("bytes_total"),
            bytesDone = o.optLong("bytes_done"),
            current = o.optString("current"),
            errors = errs,
            created = o.optLong("created"),
            finished = o.optLong("finished")
        )
    }

    /** onConflict: "skip" | "rename" | "overwrite". destPath folder hai (jaise "/video/new/"). Job ki id lautata hai. */
    fun start(items: List<ClipItem>, dest: Store, destPath: String, move: Boolean, onConflict: String): String {
        val arr = JSONArray()
        for (it in items) arr.put(JSONObject().put("store", it.store.id).put("path", it.path))
        val body = JSONObject()
            .put("items", arr)
            .put("dest", JSONObject().put("store", dest.id).put("path", destPath))
            .put("mode", if (move) "move" else "copy")
            .put("on_conflict", onConflict)
        val req = Request.Builder()
            .url(Storages.xferBase() + "/start")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        return call(req).getString("id")
    }

    fun status(id: String): XferJob {
        val req = Request.Builder().url(Storages.xferBase() + "/status?id=" + android.net.Uri.encode(id)).build()
        return parse(call(req))
    }

    fun cancel(id: String) {
        val req = Request.Builder()
            .url(Storages.xferBase() + "/cancel?id=" + android.net.Uri.encode(id))
            .post("".toRequestBody(null))
            .build()
        call(req)
    }

    fun list(): List<XferJob> {
        val req = Request.Builder().url(Storages.xferBase() + "/list").build()
        val arr = call(req).optJSONArray("jobs") ?: return emptyList()
        val out = ArrayList<XferJob>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parse(o))
        }
        return out
    }

    /** Server (AIC) disk: (free, total) bytes. Nahi mili to null. */
    fun disk(): Pair<Long, Long>? {
        val req = Request.Builder().url(Storages.xferBase() + "/disk").build()
        val o = call(req)
        val total = o.optLong("total")
        if (total <= 0L) return null
        return Pair(o.optLong("free"), total)
    }
}
