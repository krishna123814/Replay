package com.example.simplebrowser.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import com.example.simplebrowser.R

/**
 * Floating window ke andar chalne wali "screen". Isme Activity jaise hi naam hain (setContentView, finish,
 * runOnUiThread, registerForActivityResult, onResume/onPause/onDestroy...) taaki File Manager ka purana code
 * (HomeActivity / BrowserActivity) bina badle yahan chal sake. Activity nahi hai: window FloatFilesService banata hai.
 */
open class OvlScreen(base: Context, val intent: Intent, val host: Host) :
    ContextThemeWrapper(base, R.style.FilesTheme) {

    /** Floating window (frame) jo screens ko sambhalta hai. */
    interface Host {
        fun setContent(s: OvlScreen, v: View)
        fun finishScreen(s: OvlScreen)
        /** true = window ke andar hi handle ho gaya (jaise Home -> Browser), false = normal startActivity. */
        fun open(i: Intent): Boolean
        /** Window ki abhi ki chaudai (pixels). */
        fun widthPx(): Int
    }

    val RESULT_OK: Int = -1
    var isFinishing: Boolean = false
    val isDestroyed: Boolean get() = isFinishing
    var contentView: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onDestroy() {}
    /** Back dabane par: true = screen ne handle kar liya, false = window decide kare (screen band / minimize). */
    open fun onBack(): Boolean = false
    /** Window ka size badla. */
    open fun onHostResized() {}

    fun setContentView(v: View) {
        contentView = v
        host.setContent(this, v)
    }

    fun finish() {
        if (isFinishing) return
        isFinishing = true
        host.finishScreen(this)
    }

    @Suppress("UNUSED_PARAMETER")
    fun setResult(code: Int, data: Intent? = null) {
    }

    fun runOnUiThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    override fun startActivity(intent: Intent) {
        if (host.open(intent)) return
        super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("UNCHECKED_CAST")
    fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: (O) -> Unit
    ): OvlLauncher<I> {
        return OvlLauncher(this, contract) { r -> callback(r as O) }
    }
}

/** registerForActivityResult(...) ka launcher: result ek chhoti transparent Activity (OvlProxyActivity) se aata hai. */
class OvlLauncher<I>(
    private val ctx: Context,
    private val contract: ActivityResultContract<I, *>,
    private val cb: (Any?) -> Unit
) {
    fun launch(input: I) {
        OvlProxy.start(ctx, contract, input, cb)
    }
}

object OvlProxy {
    class Req(
        val contract: ActivityResultContract<Any?, Any?>,
        val input: Any?,
        val cb: (Any?) -> Unit
    )

    @Volatile
    var pending: Req? = null

    @Suppress("UNCHECKED_CAST")
    fun start(ctx: Context, contract: ActivityResultContract<*, *>, input: Any?, cb: (Any?) -> Unit) {
        pending = Req(contract as ActivityResultContract<Any?, Any?>, input, cb)
        ctx.applicationContext.startActivity(
            Intent(ctx.applicationContext, OvlProxyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Transparent Activity: file picker / permission jaise system screens ka result floating window tak pahunchata hai. */
class OvlProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = OvlProxy.pending
        OvlProxy.pending = null
        if (req == null) {
            finish()
            return
        }
        val launcher = registerForActivityResult(req.contract) { res ->
            try {
                req.cb(res)
            } finally {
                finish()
            }
        }
        launcher.launch(req.input)
    }
}

/** Overlay window type (SYSTEM_ALERT_WINDOW permission se). */
fun overlayType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

/**
 * AlertDialog.Builder jaisa hi; bas agar context Activity nahi hai (floating window) to dialog overlay type me khulta hai,
 * warna floating window ke upar dikhta hi nahi.
 */
class OvlBuilder(ctx: Context) : AlertDialog.Builder(ctx) {
    override fun create(): AlertDialog {
        val d = super.create()
        if (context !is Activity) d.window?.setType(overlayType())
        return d
    }
}

/** Main-thread helpers (Activity.runOnUiThread ki jagah, jo floating window me nahi hai). */
object Ui {
    private val h = Handler(Looper.getMainLooper())

    fun alive(c: Context): Boolean = when (c) {
        is Activity -> !c.isFinishing
        is OvlScreen -> !c.isFinishing
        else -> true
    }

    fun post(c: Context, block: () -> Unit) {
        h.post { if (alive(c)) block() }
    }
}

/** Framework dialogs (DatePickerDialog, TimePickerDialog...) ko floating window ke upar dikhane ke liye. */
fun ovlShow(owner: Context, d: android.app.Dialog) {
    if (owner !is Activity) d.window?.setType(overlayType())
    d.show()
}
