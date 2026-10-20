package com.example.simplebrowser.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.simplebrowser.MainActivity
import com.example.simplebrowser.R
import com.example.simplebrowser.money.FloatMoney
import com.example.simplebrowser.routine.FloatRoutine
import kotlin.math.abs

/** Kaun-kaun si floating apps hain (har ek ki apni alag window). */
object FloatApps {
    const val FILES = "files"
    const val MONEY = "money"
    const val ROUTINE = "routine"

    fun title(k: String): String = when (k) {
        FILES -> "📁 File Manager"
        MONEY -> "💵 Money Manager"
        else -> "⏰ Routine / Tasks"
    }

    fun icon(k: String): String = when (k) {
        FILES -> "📁"
        MONEY -> "💵"
        else -> "⏰"
    }

    fun index(k: String): Int = when (k) {
        FILES -> 0
        MONEY -> 1
        else -> 2
    }

    fun root(k: String, ctx: Context, host: OvlScreen.Host): OvlScreen = when (k) {
        FILES -> FloatHome(ctx, Intent(), host)
        MONEY -> FloatMoney(ctx, Intent(), host)
        else -> FloatRoutine(ctx, Intent(), host)
    }
}

/**
 * Floating File Manager / Money / Routine: browser wali floating window se bilkul alag service.
 * Har app ki apni alag window hai, isliye ek ka load dusre par nahi padta.
 */
class FloatAppService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.simplebrowser.STOP_FLOAT_APPS"
        const val EXTRA_APP = "app"
        const val CHANNEL_ID = "floating_apps"
        const val NOTIF_ID = 1102

        fun start(ctx: Context, app: String) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, FloatAppService::class.java).putExtra(EXTRA_APP, app)
            )
        }
    }

    private val windows = LinkedHashMap<String, AppWindow>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val app = intent?.getStringExtra(EXTRA_APP)
        if (app == null) {
            // system ne service dobara chalayi (koi app nahi bataya): kuch khula nahi to band
            if (windows.isEmpty()) {
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        val w = windows[app]
        if (w == null) {
            val nw = AppWindow(this, app)
            windows[app] = nw
            nw.start()
        } else {
            w.restore()     // pehle se khuli hai: minimized ho to kholo, warna upar le aao
        }
        return START_STICKY
    }

    /** Ek app ki window band (baaki chalti rahein); koi na bache to service band. */
    fun closeWindow(app: String) {
        val w = windows.remove(app)
        if (w != null) {
            try { w.destroy() } catch (e: Exception) { }
        }
        if (windows.isEmpty()) stopSelf()
    }

    override fun onDestroy() {
        for (w in ArrayList(windows.values)) {
            try { w.destroy() } catch (e: Exception) { }
        }
        windows.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Lion Floating Apps", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, FloatAppService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 10, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 11, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_lion)
            .setContentTitle("Lion floating apps chal rahe hain")
            .setContentText("Sab band karne ke liye \"Band karo\" dabao")
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Band karo", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}

/**
 * Floating app ki window (File Manager / Money / Routine): title bar (drag), ⋮ menu (Main menu...), — minimize (bubble), ✕ band,
 * neeche-right kone se resize. Andar Home / Browser screens ka stack chalta hai.
 */
class AppWindow(private val svc: FloatAppService, private val kind: String) : OvlScreen.Host {

    private val ctx: Context = ContextThemeWrapper(svc, R.style.FilesTheme)
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = svc.getSharedPreferences("FloatAppPrefs", Context.MODE_PRIVATE)
    private val idx = FloatApps.index(kind)
    private val density = svc.resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    private fun screenW(): Int = svc.resources.displayMetrics.widthPixels
    private fun screenH(): Int = svc.resources.displayMetrics.heightPixels

    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var grip: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var bubble: TextView? = null

    private val stack = ArrayList<OvlScreen>()
    private var minimized = false
    private var maximized = false
    private var destroyed = false
    private var winX = 0
    private var winY = 0
    private var winW = 0
    private var winH = 0

    private val normalFlags =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    // ── start / destroy ───────────────────────────────────────────────
    fun start() {
        winW = prefs.getInt("${kind}_w", minOf(dp(340), screenW() - dp(16))).coerceIn(dp(220), screenW())
        winH = prefs.getInt("${kind}_h", minOf(dp(520), screenH() - dp(120))).coerceIn(dp(280), screenH())
        winX = prefs.getInt("${kind}_x", dp(8) + idx * dp(24)).coerceIn(-dp(40), maxOf(0, screenW() - dp(60)))
        winY = prefs.getInt("${kind}_y", dp(70) + idx * dp(24)).coerceIn(0, maxOf(0, screenH() - dp(60)))

        buildUi()

        params = WindowManager.LayoutParams(winW, winH, overlayType(), normalFlags, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = winX
        params.y = winY
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, params)

        push(FloatApps.root(kind, ctx, this))
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        val list = ArrayList(stack)
        stack.clear()
        for ((i, s) in list.withIndex()) {
            s.isFinishing = true
            try { if (i == list.size - 1 && !minimized) s.onPause() } catch (e: Exception) { }
            try { s.onDestroy() } catch (e: Exception) { }
        }
        hideBubble()
        try { wm.removeView(root) } catch (e: Exception) { }
    }

    // ── UI ────────────────────────────────────────────────────────────
    private fun barBtn(label: String, onClick: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 16f
        t.gravity = Gravity.CENTER
        t.layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildUi() {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onBackInWindow()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }

        panel = LinearLayout(ctx)
        panel.orientation = LinearLayout.VERTICAL
        val pbg = GradientDrawable()
        pbg.setColor(Color.WHITE)
        pbg.setStroke(dp(1), 0xFF37474F.toInt())
        panel.background = pbg

        val titleBar = LinearLayout(ctx)
        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.setBackgroundColor(0xFF37474F.toInt())
        val titleTv = TextView(ctx)
        titleTv.text = FloatApps.title(kind)
        titleTv.setTextColor(Color.WHITE)
        titleTv.textSize = 13f
        titleTv.typeface = Typeface.DEFAULT_BOLD
        titleTv.maxLines = 1
        titleTv.gravity = Gravity.CENTER_VERTICAL
        titleTv.setPadding(dp(10), 0, dp(4), 0)
        titleBar.addView(titleTv, LinearLayout.LayoutParams(0, MATCH, 1f))
        titleBar.addView(barBtn("⋮") { showWindowMenu() })
        titleBar.addView(barBtn("—") { minimize() })
        titleBar.addView(barBtn("✕") { svc.closeWindow(kind) })
        attachDrag(titleBar)
        panel.addView(titleBar, LinearLayout.LayoutParams(MATCH, dp(34)))

        body = FrameLayout(ctx)
        body.setBackgroundColor(Color.WHITE)
        panel.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))

        grip = TextView(ctx)
        grip.text = "◢"
        grip.setTextColor(0xFF607D8B.toInt())
        grip.textSize = 16f
        grip.gravity = Gravity.CENTER
        grip.alpha = 0.8f
        root.addView(grip, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.END))
        attachResize(grip)
    }

    private fun attachDrag(v: View) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        v.setOnTouchListener { _, e ->
            if (!maximized && !minimized) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        ox = params.x
                        oy = params.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (ox + (e.rawX - sx).toInt()).coerceIn(-dp(40), maxOf(0, screenW() - dp(60)))
                        params.y = (oy + (e.rawY - sy).toInt()).coerceIn(0, maxOf(0, screenH() - dp(60)))
                        updateWin()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        winX = params.x
                        winY = params.y
                        saveGeometry()
                    }
                }
            }
            true
        }
    }

    private fun attachResize(v: View) {
        var sx = 0f
        var sy = 0f
        var ow = 0
        var oh = 0
        v.setOnTouchListener { _, e ->
            if (!maximized && !minimized) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        ow = params.width
                        oh = params.height
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.width = (ow + (e.rawX - sx).toInt()).coerceIn(dp(220), screenW())
                        params.height = (oh + (e.rawY - sy).toInt()).coerceIn(dp(280), screenH())
                        updateWin()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        winW = params.width
                        winH = params.height
                        saveGeometry()
                        notifyResized()
                    }
                }
            }
            true
        }
    }

    private fun saveGeometry() {
        prefs.edit().putInt("${kind}_x", winX).putInt("${kind}_y", winY).putInt("${kind}_w", winW).putInt("${kind}_h", winH).apply()
    }

    private fun updateWin() {
        if (destroyed) return
        try { wm.updateViewLayout(root, params) } catch (e: Exception) { }
    }

    private fun notifyResized() {
        stack.lastOrNull()?.let { try { it.onHostResized() } catch (e: Exception) { } }
    }

    private fun sysDim(name: String): Int {
        val id = svc.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else 0
    }

    /** minimized / maximized / normal ke hisaab se window ka size aur flags. */
    private fun applyParams() {
        if (destroyed || !::params.isInitialized) return
        if (minimized) {
            params.x = 0
            params.y = 0
            params.width = 1
            params.height = 1
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or normalFlags
        } else if (maximized) {
            params.x = 0
            params.y = 0
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.flags = normalFlags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            params.x = winX
            params.y = winY
            params.width = winW
            params.height = winH
            params.flags = normalFlags
        }
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        if (maximized && !minimized) lp.setMargins(0, sysDim("status_bar_height"), 0, sysDim("navigation_bar_height"))
        else lp.setMargins(0, 0, 0, 0)
        panel.layoutParams = lp
        grip.visibility = if (maximized || minimized) View.GONE else View.VISIBLE
        root.visibility = if (minimized) View.GONE else View.VISIBLE
        updateWin()
    }

    // ── minimize / maximize / bubble ──────────────────────────────────
    fun minimize() {
        if (minimized || destroyed) return
        try { stack.lastOrNull()?.onPause() } catch (e: Exception) { }
        minimized = true
        applyParams()
        showBubble()
    }

    /** Minimized ho to wapas kholo; khuli ho to bas upar le aao. */
    fun restore() {
        if (destroyed) return
        if (minimized) {
            minimized = false
            hideBubble()
            applyParams()
            notifyResized()
            try { stack.lastOrNull()?.onResume() } catch (e: Exception) { }
        } else {
            try {
                wm.removeViewImmediate(root)
                wm.addView(root, params)
            } catch (e: Exception) { }
        }
    }

    private fun toggleMaximize() {
        maximized = !maximized
        applyParams()
        root.post { notifyResized() }
    }

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(48)
        val b = TextView(ctx)
        b.text = FloatApps.icon(kind)
        b.textSize = 22f
        b.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xDDEF6C00.toInt())
        b.background = bg
        val bp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or normalFlags,
            PixelFormat.TRANSLUCENT
        )
        bp.gravity = Gravity.TOP or Gravity.START
        bp.x = prefs.getInt("${kind}_bx", screenW() - size - dp(4)).coerceIn(0, maxOf(0, screenW() - size))
        bp.y = prefs.getInt("${kind}_by", screenH() / 3 + idx * dp(56)).coerceIn(0, maxOf(0, screenH() - size))

        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = bp.x
                    oy = bp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        bp.x = (ox + dx).coerceIn(0, maxOf(0, screenW() - size))
                        bp.y = (oy + dy).coerceIn(0, maxOf(0, screenH() - size))
                        try { wm.updateViewLayout(b, bp) } catch (ex: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) restore()
                    else prefs.edit().putInt("${kind}_bx", bp.x).putInt("${kind}_by", bp.y).apply()
                }
            }
            true
        }
        try {
            wm.addView(b, bp)
            bubble = b
        } catch (e: Exception) { }
    }

    private fun hideBubble() {
        val b = bubble ?: return
        bubble = null
        try { wm.removeView(b) } catch (e: Exception) { }
    }

    // ── menu ──────────────────────────────────────────────────────────
    private fun openMainMenu() {
        try {
            svc.startActivity(
                Intent(svc, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
        } catch (e: Exception) {
        }
    }

    private fun goHome() {
        while (stack.size > 1) finishScreen(stack[stack.size - 1])
    }

    private fun showWindowMenu() {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add("🏠 Main menu"); acts.add { openMainMenu() }
        labels.add(if (maximized) "🗗 Chhota karo (restore)" else "🗖 Poori screen (maximize)"); acts.add { toggleMaximize() }
        if (kind == FloatApps.FILES) {
            labels.add("📁 File Manager Home (storages)"); acts.add { goHome() }
        }
        labels.add("➖ Minimize"); acts.add { minimize() }
        labels.add("✕ " + FloatApps.title(kind) + " band karo"); acts.add { svc.closeWindow(kind) }
        OvlBuilder(ctx)
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .show()
    }

    private fun onBackInWindow() {
        val top = stack.lastOrNull() ?: return
        if (top.onBack()) return
        if (stack.size == 1) {
            // sabse pehli screen par back: window band nahi, bas minimize
            minimize()
        } else {
            finishScreen(top)
        }
    }

    // ── screen stack (OvlScreen.Host) ─────────────────────────────────
    private fun mount(v: View) {
        body.removeAllViews()
        (v.parent as? ViewGroup)?.removeView(v)
        body.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun push(s: OvlScreen) {
        val prev = stack.lastOrNull()
        if (prev != null && !minimized) {
            try { prev.onPause() } catch (e: Exception) { }
        }
        stack.add(s)
        s.onCreate(null)
        if (!s.isFinishing && !minimized) s.onResume()
    }

    override fun setContent(s: OvlScreen, v: View) {
        if (stack.lastOrNull() === s) mount(v)
    }

    override fun finishScreen(s: OvlScreen) {
        if (destroyed) return
        val idx = stack.indexOf(s)
        if (idx < 0) return
        val wasTop = idx == stack.size - 1
        if (wasTop && !minimized) {
            try { s.onPause() } catch (e: Exception) { }
        }
        stack.removeAt(idx)
        try { s.onDestroy() } catch (e: Exception) { }
        if (stack.isEmpty()) {
            svc.closeWindow(kind)
            return
        }
        if (wasTop) {
            val t = stack[stack.size - 1]
            t.contentView?.let { mount(it) }
            if (!minimized) {
                try { t.onResume() } catch (e: Exception) { }
            }
        }
    }

    override fun open(i: Intent): Boolean {
        if (kind != FloatApps.FILES) return false
        val cn = i.component?.className ?: return false
        if (cn == BrowserActivity::class.java.name) {
            push(FloatBrowser(ctx, Intent(i), this))
            return true
        }
        if (cn == HomeActivity::class.java.name) {
            goHome()
            return true
        }
        return false
    }

    override fun widthPx(): Int {
        if (!::params.isInitialized) return screenW()
        val w = if (maximized) screenW() else params.width
        return maxOf(w - dp(2), dp(100))
    }
}
