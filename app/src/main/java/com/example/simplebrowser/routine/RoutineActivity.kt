package com.example.simplebrowser.routine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/**
 * ⏰ Routine / Tasks — server wale chart.html ke ⏰ panel ka same page (assets/routine/index.html).
 * 13 tab: Routine, To-Do, Habit, Practice, Sunday, Holiday, Dipanshu, Rough, Info, Travel, Accounts, Reports, Email, Ekadashi.
 *
 * STEP 2: server sync. Local copy: filesDir/routine/*.json. Server: GET dav/app_state/{routine,accounts}.json,
 * save = POST api/local_state_save (RoutineNet). Reports / Email / Ekadashi / Holiday = server WebSocket /ws (wsCall).
 * Sync ka logic page ke JS me hai (assets/routine/index.html), yahan sirf network + file bridge.
 */
class RoutineActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var store: RoutineStore

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#1A1E29")
        window.navigationBarColor = Color.parseColor("#131722")
        store = RoutineStore(applicationContext)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#131722"))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
        }
        web.addJavascriptInterface(Bridge(), "LionRt")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "file") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }
        // confirm() / prompt() / alert() WebView me apne aap nahi chalte — dialog yahan banate hain
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@RoutineActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@RoutineActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                val et = EditText(this@RoutineActivity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setText(defaultValue ?: "")
                    setSelection(text.length)
                }
                val box = FrameLayout(this@RoutineActivity).apply {
                    val p = (20 * resources.displayMetrics.density).toInt()
                    setPadding(p, 8, p, 0)
                    addView(et)
                }
                AlertDialog.Builder(this@RoutineActivity)
                    .setMessage(message)
                    .setView(box)
                    .setPositiveButton("OK") { _, _ -> result.confirm(et.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
        setContentView(web)

        // Back: pehle page ke andar ka screen (Accounts statement, Info "All entries") band, phir Activity
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(function(){try{return window._lionBack?window._lionBack():false}catch(e){return false}})()") { r ->
                    if (r != "true") {
                        // pending save/sync hai to JS use poora karke (max ~4 sec) LionRt.close() bulayega
                        web.evaluateJavascript("(function(){try{window._lionExit();return true}catch(e){return false}})()") { r2 ->
                            if (r2 != "true") finish()
                        }
                    }
                }
            }
        })

        web.loadUrl("file:///android_asset/routine/index.html")
    }

    override fun onResume() { super.onResume(); web.onResume() }
    override fun onPause() {
        try { web.evaluateJavascript("try{window._lionFlush&&window._lionFlush()}catch(e){}", null) } catch (_: Exception) {}
        web.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        try { web.removeJavascriptInterface("LionRt"); web.destroy() } catch (_: Exception) {}
        RoutineNet.close()
        super.onDestroy()
    }

    /** JS ↔ phone bridge (window.LionRt). */
    inner class Bridge {
        @JavascriptInterface fun get(key: String): String = store.read(key)
        @JavascriptInterface fun set(key: String, value: String): Boolean = store.write(key, value)
        @JavascriptInterface fun close() { runOnUiThread { finish() } }

        /** Async HTTP (sirf routine/accounts load + local_state_save). Jawab: window._lionNet(id, ok, '{"code":..,"body":".."}') */
        @JavascriptInterface fun http(id: Int, method: String, path: String, body: String) {
            RoutineNet.io.execute {
                try {
                    val (code, txt) = RoutineNet.http(applicationContext, method, path, body)
                    deliver(id, true, JSONObject().put("code", code).put("body", txt).toString())
                } catch (e: Exception) {
                    deliver(id, false, e.message ?: "network error")
                }
            }
        }

        /** Async WebSocket action (chart.html ke wsCall jaisa). Jawab: window._lionNet(id, ok, '<server data JSON>') */
        @JavascriptInterface fun ws(id: Int, action: String, dataJson: String, timeoutMs: Int) {
            RoutineNet.io.execute {
                try {
                    deliver(id, true, RoutineNet.wsCall(applicationContext, action, dataJson, timeoutMs.toLong()))
                } catch (e: Exception) {
                    deliver(id, false, e.message ?: "ws error")
                }
            }
        }

        /** Export JSON: Downloads/Lion/ me file save. */
        @JavascriptInterface fun saveFile(name: String, text: String): Boolean {
            val where = saveToDownloads(name, text)
            runOnUiThread {
                Toast.makeText(
                    this@RoutineActivity,
                    if (where != null) "Saved: $where" else "Save nahi hua",
                    Toast.LENGTH_LONG
                ).show()
            }
            return where != null
        }
    }

    private fun deliver(id: Int, ok: Boolean, payload: String) {
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            web.evaluateJavascript("window._lionNet($id,$ok,${JSONObject.quote(payload)})", null)
        }
    }

    private fun saveToDownloads(name: String, text: String): String? {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "export.json" }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Lion")
                }
                val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return null
                "Downloads/Lion/$safe"
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Lion").apply { mkdirs() }
                File(dir, safe).writeText(text, Charsets.UTF_8)
                File(dir, safe).absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Phone par data: filesDir/routine/<key>.json (tmp file + rename, taaki beech me band hone par data na bigde). */
class RoutineStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "routine").apply { mkdirs() }
    private val lock = Any()

    private fun file(key: String): File {
        val k = key.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "x" }
        return File(dir, "$k.json")
    }

    fun read(key: String): String = synchronized(lock) {
        try {
            val f = file(key)
            if (f.exists()) f.readText(Charsets.UTF_8) else ""
        } catch (_: Exception) { "" }
    }

    fun write(key: String, value: String): Boolean = synchronized(lock) {
        try {
            val f = file(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(value, Charsets.UTF_8)
            if (f.exists()) f.copyTo(File(dir, f.name + ".bak"), overwrite = true)
            if (!tmp.renameTo(f)) { f.writeText(value, Charsets.UTF_8); tmp.delete() }
            true
        } catch (_: Exception) { false }
    }
}
