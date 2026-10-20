package com.example.simplebrowser.routine

import android.content.Context
import com.example.simplebrowser.files.Dav
import com.example.simplebrowser.files.Session
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ⏰ Routine ka network layer (File Manager / Money Manager wala hi login: Session).
 *  - http(): sirf 2 raaste — GET dav/app_state/{routine,accounts}.json aur POST api/local_state_save
 *  - wsCall(): server ka WebSocket /ws (chart.html ke wsCall jaisa: {id, action, data} -> {id, ok, data|error})
 * Saare calls blocking hain — RoutineActivity inhe background thread se chalata hai.
 */
object RoutineNet {
    private val JSON_T = "application/json; charset=utf-8".toMediaType()
    val io = Executors.newCachedThreadPool()

    private fun prepare(ctx: Context) {
        val a = ctx.applicationContext
        if (!Session.hasCreds()) Session.load(a)
        if (!Session.hasCreds()) throw IOException("Pehle File Manager me login karo (Server settings)")
    }

    private fun origin(): String {
        val u = Session.baseUrl.toHttpUrlOrNull() ?: throw IOException("Server URL galat")
        return u.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }

    private val allowedGet = setOf("dav/app_state/routine.json", "dav/app_state/accounts.json")

    /** @return (HTTP code, body) */
    fun http(ctx: Context, method: String, path: String, body: String): Pair<Int, String> {
        prepare(ctx)
        val p = path.trimStart('/')
        val rb = Request.Builder().url(origin() + p)
        if (method.equals("POST", true)) {
            if (p != "api/local_state_save") throw IOException("path allowed nahi")
            rb.post(body.toRequestBody(JSON_T))
        } else {
            if (p !in allowedGet) throw IOException("path allowed nahi")
            rb.get()
        }
        Dav.client.newCall(rb.build()).execute().use { r: Response ->
            return Pair(r.code, r.body?.string() ?: "")
        }
    }

    // ───────────────────────── WebSocket ─────────────────────────

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var data: String? = null
        @Volatile var err: String? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val seq = AtomicLong()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var wsOpen = false

    private fun failAll(msg: String) {
        for ((k, p) in pending) {
            p.err = msg
            p.latch.countDown()
            pending.remove(k)
        }
    }

    private fun onMessage(text: String) {
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        if (!m.has("id")) return                    // hello / push snapshot — humein nahi chahiye
        val id = m.opt("id")?.toString() ?: return
        val p = pending.remove(id) ?: return
        if (m.optBoolean("ok", false)) {
            val d = m.opt("data")
            p.data = when (d) {
                null, JSONObject.NULL -> "null"
                is JSONObject, is JSONArray -> d.toString()
                is String -> JSONObject.quote(d)
                else -> d.toString()
            }
        } else {
            p.err = m.optString("error", "WS action fail")
        }
        p.latch.countDown()
    }

    @Synchronized
    private fun ensureWs(): WebSocket {
        val cur = ws
        if (cur != null && wsOpen) return cur
        cur?.cancel()
        wsOpen = false
        val org = origin()
        val wsUrl = org.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "ws"
        val client = Dav.client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val opened = CountDownLatch(1)
        var openErr: String? = null
        val sock = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                wsOpen = true
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === ws) this@RoutineNet.onMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === ws) { wsOpen = false; failAll("WS band ho gaya") }
                opened.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                openErr = (response?.let { "HTTP ${it.code}" } ?: t.message) ?: "connect fail"
                if (webSocket === ws) { wsOpen = false; failAll("WS toot gaya: $openErr") }
                opened.countDown()
            }
        })
        ws = sock
        if (!opened.await(12, TimeUnit.SECONDS) || !wsOpen) {
            sock.cancel()
            if (ws === sock) ws = null
            throw IOException("WS connect nahi hua" + (openErr?.let { " ($it)" } ?: ""))
        }
        return sock
    }

    /** @return server ka `data` (JSON text). Fail par IOException (message = server ka error). */
    fun wsCall(ctx: Context, action: String, dataJson: String, timeoutMs: Long): String {
        prepare(ctx)
        val sock = ensureWs()
        val id = "L" + seq.incrementAndGet()
        val p = Pending()
        pending[id] = p
        val payload: Any = if (dataJson.isBlank() || dataJson == "null") JSONObject.NULL else JSONTokener(dataJson).nextValue()
        val msg = JSONObject().put("id", id).put("action", action).put("data", payload)
        if (!sock.send(msg.toString())) {
            pending.remove(id)
            wsOpen = false
            throw IOException("WS send fail")
        }
        if (!p.latch.await(timeoutMs.coerceIn(1000, 60000), TimeUnit.MILLISECONDS)) {
            pending.remove(id)
            throw IOException("WS timeout: $action")
        }
        p.err?.let { throw IOException(it) }
        return p.data ?: "null"
    }

    fun close() {
        try { ws?.cancel() } catch (_: Exception) {}
        ws = null
        wsOpen = false
        failAll("band")
    }
}
