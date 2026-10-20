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
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
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
            bringDotsToFront()
        } else {
            w.restore()     // pehle se khuli hai: minimized ho to kholo, warna upar le aao
        }
        return START_STICKY
    }

    /** Sab dots ko windows ke upar rakho taaki hamesha dikhen. */
    fun bringDotsToFront() {
        for (w in windows.values) w.reAddDot()
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
 * Floating app ki window (File Manager / Money / Routine) — floating browser jaisa DOT system:
 *  - dot tap -> window show/hide
 *  - dot long-press -> pill menu (back, maximize, show/hide, main menu, [files home], settings, close)
 *  - dot drag -> move; neeche ❌ zone me chhodo to window band
 *  - window me alag title bar / minimize / close button nahi: sirf upar patla drag handle aur ◢ resize
 */
class AppWindow(private val svc: FloatAppService, private val kind: String) : OvlScreen.Host {

    private val ctx: Context = ContextThemeWrapper(svc, R.style.FilesTheme)
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = svc.getSharedPreferences("FloatAppPrefs", Context.MODE_PRIVATE)
    private val idx = FloatApps.index(kind)
    private val density = svc.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    private fun screenW(): Int = svc.resources.displayMetrics.widthPixels
    private fun screenH(): Int = svc.resources.displayMetrics.heightPixels

    // ── Window B: app window ──────────────────────────────────────────
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var dragHandle: FrameLayout
    private lateinit var body: FrameLayout
    private lateinit var grip: TextView
    private lateinit var params: WindowManager.LayoutParams

    // ── Window A: dot + pill ──────────────────────────────────────────
    private lateinit var dotRoot: LinearLayout
    private lateinit var dotWrapper: FrameLayout
    private lateinit var dotView: View
    private lateinit var pill: LinearLayout
    private lateinit var dotParams: WindowManager.LayoutParams
    private lateinit var btnWindow: TextView
    private lateinit var btnMaximize: TextView
    private val pillButtons = ArrayList<TextView>()

    // ── close zone (dot drag ke waqt) ─────────────────────────────────
    private lateinit var closeZone: FrameLayout
    private lateinit var closeParams: WindowManager.LayoutParams
    private var isCloseZoneVisible = false

    private val stack = ArrayList<OvlScreen>()
    private var hidden = false          // window chhupi hai (sirf dot dikhta hai)
    private var maximized = false
    private var destroyed = false
    private var isPillOpen = false
    private var pillOnLeft = false
    private var winX = 0
    private var winY = 0
    private var winW = 0
    private var winH = 0
    private var dotCX = 0
    private var dotCY = 0

    private val normalFlags =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private val collapseRunnable = Runnable { closePill() }
    private var longPressed = false
    private val longPressRunnable = Runnable {
        longPressed = true
        dotWrapper.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (isPillOpen) scheduleAutoCollapse() else openPill()
    }

    // ── start / destroy ───────────────────────────────────────────────
    fun start() {
        winW = prefs.getInt("${kind}_w", minOf(dp(340), screenW() - dp(16))).coerceIn(dp(220), screenW())
        winH = prefs.getInt("${kind}_h", minOf(dp(520), screenH() - dp(120))).coerceIn(dp(280), screenH())
        winX = prefs.getInt("${kind}_x", dp(8) + idx * dp(24)).coerceIn(-dp(40), maxOf(0, screenW() - dp(60)))
        winY = prefs.getInt("${kind}_y", dp(70) + idx * dp(24)).coerceIn(0, maxOf(0, screenH() - dp(60)))
        // dot default: right edge (har app ka dot thoda alag neeche)
        dotCX = prefs.getInt("${kind}_dx", screenW() - dp(24)).coerceIn(0, screenW())
        dotCY = prefs.getInt("${kind}_dy", screenH() / 4 + dp(200) + idx * dp(56)).coerceIn(0, screenH())

        buildUi()

        params = WindowManager.LayoutParams(winW, winH, overlayType(), normalFlags, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = winX
        params.y = winY
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        // Window A (dot + pill): kabhi focus nahi leta
        dotParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or normalFlags,
            PixelFormat.TRANSLUCENT
        )
        dotParams.gravity = Gravity.TOP or Gravity.START
        dotParams.x = dotCX - dp(20)
        dotParams.y = dotCY - dp(20)
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            dotParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // pehle window, phir dot -> dot hamesha upar rahe
        wm.addView(root, params)
        wm.addView(dotRoot, dotParams)
        applyDotTheme()

        push(FloatApps.root(kind, ctx, this))
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        val list = ArrayList(stack)
        stack.clear()
        for ((i, s) in list.withIndex()) {
            s.isFinishing = true
            try { if (i == list.size - 1 && !hidden) s.onPause() } catch (e: Exception) { }
            try { s.onDestroy() } catch (e: Exception) { }
        }
        if (isCloseZoneVisible) {
            try { wm.removeView(closeZone) } catch (e: Exception) { }
            isCloseZoneVisible = false
        }
        try { wm.removeView(dotRoot) } catch (e: Exception) { }
        try { wm.removeView(root) } catch (e: Exception) { }
    }

    /** Dot ko window ke upar rakho (remove+add). */
    fun reAddDot() {
        if (destroyed || !::dotRoot.isInitialized) return
        try {
            wm.removeViewImmediate(dotRoot)
            wm.addView(dotRoot, dotParams)
        } catch (e: Exception) { }
    }

    // ── UI ────────────────────────────────────────────────────────────
    private fun mkBtn(label: String, onClick: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.textSize = 14f
        t.layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        t.setOnClickListener { onClick() }
        pillButtons.add(t)
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

        // patla drag handle (browser jaisa) — koi button nahi
        dragHandle = FrameLayout(ctx)
        dragHandle.setBackgroundColor(Color.parseColor("#1e222d"))
        val bar = View(ctx)
        val barShape = GradientDrawable()
        barShape.cornerRadius = dp(2).toFloat()
        barShape.setColor(Color.parseColor("#66FFFFFF"))
        bar.background = barShape
        dragHandle.addView(bar, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
        attachDrag(dragHandle)
        panel.addView(dragHandle, LinearLayout.LayoutParams(MATCH, dp(18)))

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

        buildDot()
    }

    private fun buildDot() {
        dotRoot = LinearLayout(ctx)
        dotRoot.orientation = LinearLayout.HORIZONTAL
        dotRoot.gravity = Gravity.CENTER_VERTICAL

        dotWrapper = FrameLayout(ctx)
        dotView = View(ctx)
        val dotShape = GradientDrawable()
        dotShape.shape = GradientDrawable.OVAL
        val dotColors = arrayOf("#F5A623", "#66BB6A", "#AB47BC") // files, money, routine
        dotShape.setColor(Color.parseColor(dotColors[idx % dotColors.size]))
        dotShape.setStroke(maxOf(1, (1.5f * density).toInt()), Color.parseColor("#B3FFFFFF"))
        dotView.background = dotShape
        dotWrapper.addView(dotView, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        dotRoot.addView(dotWrapper, LinearLayout.LayoutParams(dp(40), dp(40)))
        attachDot()

        // pill: dark rounded row, dot ke paas khulta hai
        pill = LinearLayout(ctx)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER
        pill.setPadding(dp(4), dp(4), dp(4), dp(4))
        val pillShape = GradientDrawable()
        pillShape.cornerRadius = dp(20).toFloat()
        pillShape.setColor(Color.parseColor("#F21e222d"))
        pill.background = pillShape

        mkBtn("◀") { scheduleAutoCollapse(); showWindow(); backStep() }
        btnMaximize = mkBtn("⛶") { scheduleAutoCollapse(); showWindow(); toggleMaximize() }
        btnWindow = mkBtn("—") { scheduleAutoCollapse(); toggleWindow() }
        mkBtn("🏠") { scheduleAutoCollapse(); openMainMenu() }
        if (kind == FloatApps.FILES) mkBtn("📁") { scheduleAutoCollapse(); showWindow(); goHome() }
        mkBtn("⚙") { scheduleAutoCollapse(); showSettings() }
        mkBtn("✕") { svc.closeWindow(kind) }
        for (b in pillButtons) pill.addView(b)

        // close zone (alag overlay window, dot drag ke waqt dikhta hai)
        closeZone = FrameLayout(ctx)
        val cross = TextView(ctx)
        cross.text = "❌"
        cross.textSize = 26f
        cross.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#CCFF3333"))
        closeZone.background = bg
        closeZone.addView(cross, FrameLayout.LayoutParams(dp(65), dp(65), Gravity.CENTER))
        closeParams = WindowManager.LayoutParams(
            dp(65), dp(65), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        closeParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        closeParams.y = dp(40)
    }

    // ── dot gestures: tap = show/hide, long-press = pill, drag = move, ❌ = band ──
    private fun attachDot() {
        var sX = 0; var sY = 0; var sTX = 0f; var sTY = 0f; var dragging = false
        dotWrapper.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sX = dotCX; sY = dotCY; sTX = e.rawX; sTY = e.rawY
                    dragging = false
                    longPressed = false
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = if (longPressed) 30 else 10
                    if (dragging || abs(e.rawX - sTX) > slop || abs(e.rawY - sTY) > slop) {
                        if (!dragging) {
                            dragging = true
                            handler.removeCallbacks(longPressRunnable)
                            if (isPillOpen) closePill()
                            showCloseZone()
                        }
                        dotCX = (sX + (e.rawX - sTX).toInt()).coerceIn(0, screenW())
                        dotCY = (sY + (e.rawY - sTY).toInt()).coerceIn(0, screenH())
                        placeDotWindow()
                        val over = isOverCloseZone(e.rawX, e.rawY)
                        closeZone.scaleX = if (over) 1.3f else 1.0f
                        closeZone.scaleY = if (over) 1.3f else 1.0f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        hideCloseZone()
                        if (isOverCloseZone(e.rawX, e.rawY)) svc.closeWindow(kind)
                        else prefs.edit().putInt("${kind}_dx", dotCX).putInt("${kind}_dy", dotCY).apply()
                    } else if (!longPressed) {
                        if (isPillOpen) closePill() else toggleWindow()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (dragging) hideCloseZone()
                    true
                }
                else -> false
            }
        }
    }

    private fun showCloseZone() {
        if (isCloseZoneVisible) return
        try { wm.addView(closeZone, closeParams); isCloseZoneVisible = true } catch (e: Exception) { }
    }

    private fun hideCloseZone() {
        if (!isCloseZoneVisible) return
        try { wm.removeView(closeZone) } catch (e: Exception) { }
        isCloseZoneVisible = false
    }

    private fun isOverCloseZone(x: Float, y: Float): Boolean =
        y > screenH() - dp(140) && x > screenW() / 3 && x < (screenW() * 2) / 3

    // ── pill ──────────────────────────────────────────────────────────
    private fun pillW(): Int = dp(32) * pillButtons.count { it.visibility != View.GONE } + dp(8)

    private fun placeDotWindow() {
        if (destroyed) return
        var x = dotCX - dp(20)
        if (isPillOpen) {
            if (pillOnLeft) x -= pillW()
            val totalW = dp(40) + pillW()
            x = x.coerceIn(0, maxOf(0, screenW() - totalW))
        }
        dotParams.x = x
        dotParams.y = dotCY - dp(20)
        try { wm.updateViewLayout(dotRoot, dotParams) } catch (e: Exception) { }
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, 4000L)
    }

    private fun openPill() {
        if (isPillOpen) return
        val sw = screenW()
        val pw = pillW()
        val fitsLeft = dotCX - dp(20) - pw >= 0
        val fitsRight = dotCX + dp(20) + pw <= sw
        val preferLeft = dotCX > sw / 2
        pillOnLeft = when {
            preferLeft && fitsLeft -> true
            !preferLeft && fitsRight -> false
            fitsLeft -> true
            fitsRight -> false
            else -> preferLeft
        }
        val plp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (pillOnLeft) dotRoot.addView(pill, 0, plp) else dotRoot.addView(pill, plp)
        isPillOpen = true
        placeDotWindow()
        scheduleAutoCollapse()
    }

    private fun closePill() {
        handler.removeCallbacks(collapseRunnable)
        if (!isPillOpen) return
        dotRoot.removeView(pill)
        isPillOpen = false
        placeDotWindow()
    }

    // ── dot theme (size / opacity) ────────────────────────────────────
    private fun applyDotTheme() {
        val size = prefs.getInt("dot_size", 16).coerceIn(10, 28)
        val lp = dotView.layoutParams as FrameLayout.LayoutParams
        lp.width = dp(size)
        lp.height = dp(size)
        dotView.layoutParams = lp
        dotView.alpha = maxOf(prefs.getInt("dot_opacity", 100), 25) / 100f
    }

    private fun showSettings() {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(12), dp(20), dp(4))
        fun label(t: String) = TextView(ctx).apply { text = t; textSize = 13f }
        fun seek(max: Int, cur: Int, onChange: (Int) -> Unit) = SeekBar(ctx).also { sb ->
            sb.max = max
            sb.progress = cur
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(label("Dot size (10–28dp)"))
        col.addView(seek(18, prefs.getInt("dot_size", 16).coerceIn(10, 28) - 10) {
            prefs.edit().putInt("dot_size", it + 10).apply(); applyDotTheme()
        })
        col.addView(label("Dot opacity"))
        col.addView(seek(100, prefs.getInt("dot_opacity", 100)) {
            prefs.edit().putInt("dot_opacity", it).apply(); applyDotTheme()
        })
        OvlBuilder(ctx)
            .setTitle(FloatApps.title(kind) + " — dot settings")
            .setView(col)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── drag / resize ─────────────────────────────────────────────────
    private fun attachDrag(v: View) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        v.setOnTouchListener { _, e ->
            if (!maximized && !hidden) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = params.x; oy = params.y }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (ox + (e.rawX - sx).toInt()).coerceIn(-dp(40), maxOf(0, screenW() - dp(60)))
                        params.y = (oy + (e.rawY - sy).toInt()).coerceIn(0, maxOf(0, screenH() - dp(60)))
                        updateWin()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        winX = params.x; winY = params.y
                        saveGeometry()
                    }
                }
            }
            true
        }
    }

    private fun attachResize(v: View) {
        var sx = 0f; var sy = 0f; var ow = 0; var oh = 0
        v.setOnTouchListener { _, e ->
            if (!maximized && !hidden) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ow = params.width; oh = params.height }
                    MotionEvent.ACTION_MOVE -> {
                        params.width = (ow + (e.rawX - sx).toInt()).coerceIn(dp(220), screenW())
                        params.height = (oh + (e.rawY - sy).toInt()).coerceIn(dp(280), screenH())
                        updateWin()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        winW = params.width; winH = params.height
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

    /** hidden / maximized / normal ke hisaab se window ka size aur flags. */
    private fun applyParams() {
        if (destroyed || !::params.isInitialized) return
        if (hidden) {
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
        if (maximized && !hidden) lp.setMargins(0, sysDim("status_bar_height"), 0, sysDim("navigation_bar_height"))
        else lp.setMargins(0, 0, 0, 0)
        panel.layoutParams = lp
        dragHandle.visibility = if (maximized) View.GONE else View.VISIBLE
        grip.visibility = if (maximized || hidden) View.GONE else View.VISIBLE
        root.visibility = if (hidden) View.GONE else View.VISIBLE
        btnWindow.text = if (hidden) "🪟" else "—"
        updateWin()
    }

    // ── show / hide / maximize ────────────────────────────────────────
    private fun hideWindow() {
        if (hidden || destroyed) return
        try { stack.lastOrNull()?.onPause() } catch (e: Exception) { }
        hidden = true
        applyParams()
    }

    private fun showWindow() {
        if (!hidden || destroyed) return
        hidden = false
        // is window ko baaki windows ke upar lao, phir dot ko uske upar
        try { wm.removeViewImmediate(root); wm.addView(root, params) } catch (e: Exception) { }
        applyParams()
        svc.bringDotsToFront()
        notifyResized()
        try { stack.lastOrNull()?.onResume() } catch (e: Exception) { }
    }

    private fun toggleWindow() {
        if (hidden) showWindow() else hideWindow()
    }

    /** Service se: hidden ho to kholo; khuli ho to bas upar le aao. */
    fun restore() {
        if (destroyed) return
        if (hidden) {
            showWindow()
        } else {
            try { wm.removeViewImmediate(root); wm.addView(root, params) } catch (e: Exception) { }
            svc.bringDotsToFront()
        }
    }

    private fun toggleMaximize() {
        maximized = !maximized
        applyParams()
        btnMaximize.text = if (maximized) "🗗" else "⛶"
        root.post { notifyResized() }
    }

    // ── menu / navigation ─────────────────────────────────────────────
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

    /** pill ka ◀: andar ki screen ka back; sabse pehli screen par kuch nahi. */
    private fun backStep() {
        val top = stack.lastOrNull() ?: return
        if (top.onBack()) return
        if (stack.size > 1) finishScreen(top)
    }

    private fun onBackInWindow() {
        val top = stack.lastOrNull() ?: return
        if (top.onBack()) return
        if (stack.size == 1) hideWindow() // sabse pehli screen par back: window band nahi, bas hide
        else finishScreen(top)
    }

    // ── screen stack (OvlScreen.Host) ─────────────────────────────────
    private fun mount(v: View) {
        body.removeAllViews()
        (v.parent as? ViewGroup)?.removeView(v)
        body.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun push(s: OvlScreen) {
        val prev = stack.lastOrNull()
        if (prev != null && !hidden) {
            try { prev.onPause() } catch (e: Exception) { }
        }
        stack.add(s)
        s.onCreate(null)
        if (!s.isFinishing && !hidden) s.onResume()
    }

    override fun setContent(s: OvlScreen, v: View) {
        if (stack.lastOrNull() === s) mount(v)
    }

    override fun finishScreen(s: OvlScreen) {
        if (destroyed) return
        val i = stack.indexOf(s)
        if (i < 0) return
        val wasTop = i == stack.size - 1
        if (wasTop && !hidden) {
            try { s.onPause() } catch (e: Exception) { }
        }
        stack.removeAt(i)
        try { s.onDestroy() } catch (e: Exception) { }
        if (stack.isEmpty()) {
            svc.closeWindow(kind)
            return
        }
        if (wasTop) {
            val t = stack[stack.size - 1]
            t.screenView?.let { mount(it) }
            if (!hidden) {
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
