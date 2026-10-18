package com.example.simplebrowser

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Lion floating browser: kisi bhi app ke upar tairta golden dot.
 * - dot tap -> browser window show/hide; dot long-press -> chhota pill menu (back, refresh, maximize, window show/hide, settings, close); drag -> move
 * - dot ko kahin bhi drag karo; neeche ❌ zone me chhodo to us window ko band kar do
 * - pill me ➕: nayi independent floating window (alag URL, alag dot, max 3); ✕ sirf usi window ko band karta hai
 * - browser window: upar ke handle se drag, ◢ se resize, ⛶ maximize
 * - ⚙ settings: URL, icon size, opacity, dot size
 * - text select -> Lion Copy bar (Copy / Select all / Paste)
 * - <input type=file> ke liye file picker, JS prompt, fullscreen video
 */
/**
 * Overlay window me system text-selection menu (ActionMode) nahi ban pata, aur WebView ko jab ActionMode
 * nahi milta to wo selection khud clear kar deta hai. Isliye yaha ek khaali "dummy" ActionMode dete hain:
 * WebView ko lagta hai menu khul gaya, selection tika rehta hai, aur dikhta sirf humara Copy bar hai.
 */
private class DummyActionMode(
    private val ctx: Context,
    private val anchor: View,
    private val cb: ActionMode.Callback?
) : ActionMode() {
    private var finished = false
    private val menuObj: Menu by lazy { PopupMenu(ctx, anchor).menu }
    override fun setTitle(title: CharSequence?) {}
    override fun setTitle(resId: Int) {}
    override fun setSubtitle(subtitle: CharSequence?) {}
    override fun setSubtitle(resId: Int) {}
    override fun setCustomView(view: View?) {}
    override fun invalidate() {}
    override fun finish() {
        if (finished) return
        finished = true
        try { cb?.onDestroyActionMode(this) } catch (e: Exception) { }
    }
    override fun getMenu(): Menu = menuObj
    override fun getTitle(): CharSequence? = null
    override fun getSubtitle(): CharSequence? = null
    override fun getCustomView(): View? = null
    override fun getMenuInflater(): MenuInflater = MenuInflater(ctx)
}

private class LionWebView(c: Context) : WebView(c) {
    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        DummyActionMode(context, this, callback)

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        DummyActionMode(context, this, callback)
}

class FloatingService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.simplebrowser.STOP_FLOATING"
        const val ACTION_UPDATE = "com.example.simplebrowser.UPDATE_NOW"
        const val CHANNEL_ID = "floating_browser"
        const val NOTIF_ID = 1101
        const val PREFS = "FloatingAppPrefs"
        const val MAX_WINDOWS = 3

        // File picker (input type=file) ka result yahan hold hota hai
        var filePathCallback: ValueCallback<Array<Uri>>? = null
        var instance: FloatingService? = null
    }

    // ek se zyada independent floating windows (har ek ka apna slot id 0..MAX_WINDOWS-1)
    private val windows = ArrayList<FloatWindow>()
    private val handler = Handler(Looper.getMainLooper())

    // update download progress (0..99), -1 = download nahi chal raha
    private var downloadPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        goForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createWindowIfNeeded()

        // 6 ghante me max ek baar naya version dekho
        if (UpdateChecker.shouldAutoCheck(this)) {
            UpdateChecker.check(this) { info -> if (info != null) onUpdateFound(info) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // notification se "Band karo": sab band, agli baar fresh start
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("open_slots").apply()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        createWindowIfNeeded()
        if (intent?.action == ACTION_UPDATE) {
            val p = UpdateChecker.pending
            if (p != null) {
                startUpdate(p)
            } else {
                UpdateChecker.check(this) { info ->
                    if (info != null) {
                        UpdateChecker.pending = info
                        startUpdate(info)
                    } else {
                        Toast.makeText(this, "Lion up to date", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        for (w in windows) w.destroy()
        windows.clear()
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ── multi window ──────────────────────────────────────────────────────
    private fun urlKey(id: Int): String = if (id == 0) "custom_url" else "w${id}_custom_url"

    private fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            val ipPort = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")
            u = if (ipPort.matches(u)) "http://$u" else "https://$u"
        }
        return u
    }

    // ── bookmarks (sab windows ke liye shared) ────────────────────────────
    fun loadBookmarks(): MutableList<Bookmark> {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("bookmarks", null)
        val list = ArrayList<Bookmark>()
        if (raw == null) {
            // pehli baar: default site pehle se bookmark
            list.add(Bookmark("Lion default", AppConfig.DEFAULT_URL))
            saveBookmarks(list)
            return list
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("url")
                if (u.isNotEmpty()) list.add(Bookmark(o.optString("title", u), u))
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("title", b.title).put("url", b.url))
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("bookmarks", arr.toString()).apply()
    }

    /** 0 = add hua, 1 = pehle se hai, 2 = list full, 3 = valid URL nahi */
    fun addBookmark(title: String, rawUrl: String): Int {
        val u = rawUrl.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return 3
        val list = loadBookmarks()
        if (list.any { it.url == u }) return 1
        if (list.size >= BOOKMARK_MAX) return 2
        val t = title.trim().ifEmpty { Uri.parse(u).host ?: u }.take(40)
        list.add(Bookmark(t, u))
        saveBookmarks(list)
        return 0
    }

    fun removeBookmark(rawUrl: String) {
        val list = loadBookmarks()
        list.removeAll { it.url == rawUrl }
        saveBookmarks(list)
    }

    private fun saveOpenSlots() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("open_slots", windows.joinToString(",") { it.id.toString() })
            .apply()
    }

    private fun createWindowIfNeeded() {
        if (windows.isNotEmpty()) return
        if (!Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var ids = (prefs.getString("open_slots", "") ?: "")
            .split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0 until MAX_WINDOWS }.distinct()
        if (ids.isEmpty()) ids = listOf(0)
        for (id in ids) {
            val url = prefs.getString(urlKey(id), null)
                ?: prefs.getString(urlKey(0), AppConfig.DEFAULT_URL) ?: AppConfig.DEFAULT_URL
            val w = FloatWindow(this, id, url)
            windows.add(w)
            w.show()
        }
        saveOpenSlots()
        refreshBadges()
        refreshNotification()
    }

    /** Nayi independent window (naya URL). Max MAX_WINDOWS. */
    fun openNewWindow(rawUrl: String) {
        val url = normalizeUrl(rawUrl)
        if (url.isEmpty()) return
        if (windows.size >= MAX_WINDOWS) {
            Toast.makeText(this, "Max $MAX_WINDOWS windows khul sakti hain", Toast.LENGTH_SHORT).show()
            return
        }
        val used = windows.map { it.id }.toSet()
        val id = (0 until MAX_WINDOWS).firstOrNull { it !in used } ?: return
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(urlKey(id), url).apply()
        val w = FloatWindow(this, id, url)
        windows.add(w)
        w.show()
        w.openBrowser()
        saveOpenSlots()
        refreshBadges()
        refreshNotification()
        if (layoutMode != 0) {
            applyLayout()
            handler.post { bringDotsToFront() }
        }
    }

    /** Sirf ek window band karo; aakhri band hone par service bhi band. */
    fun closeWindow(w: FloatWindow) {
        if (!windows.remove(w)) return
        w.destroy()
        saveOpenSlots()
        if (windows.isEmpty()) {
            stopSelf()
        } else {
            refreshBadges()
            refreshNotification()
            if (windows.size < 2) {
                layoutMode = 0
                applyLayout()
            } else if (layoutMode != 0) {
                applyLayout()
            }
        }
    }

    /** Renderer mara to usi slot me window dobara banao. */
    fun restartWindow(w: FloatWindow) {
        val idx = windows.indexOf(w)
        if (idx < 0) return
        val id = w.id
        val u = w.homeUrl()
        w.destroy()
        val nw = FloatWindow(this, id, u)
        windows[idx] = nw
        nw.show()
        refreshBadges()
    }

    // ── windows layout: sab windows ek saath, barabar hisse me ─────────────
    // 0 = normal, 1 = upar-neeche barabar, 2 = side-by-side barabar
    private var layoutMode = 0

    private fun statusBarHeight(): Int {
        val rid = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (rid > 0) resources.getDimensionPixelSize(rid) else 0
    }

    /** [x, y, width, height] : system bars chhodkar jitni jagah milti hai. */
    private fun usableArea(): IntArray {
        val m = resources.displayMetrics
        var left = 0
        var top = statusBarHeight()
        var w = m.widthPixels
        var h = m.heightPixels - top
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = wm.currentWindowMetrics
                val ins = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val b = metrics.bounds
                left = ins.left
                top = ins.top
                w = b.width() - ins.left - ins.right
                h = b.height() - ins.top - ins.bottom
            } catch (e: Exception) {
            }
        }
        return intArrayOf(left, top, maxOf(w, 1), maxOf(h, 1))
    }

    private fun applyLayout() {
        val list = windows.sortedBy { it.id }
        val n = list.size
        if (n < 2 || layoutMode == 0) {
            for (w in list) w.restoreGeometry()
            return
        }
        val a = usableArea()
        for ((i, w) in list.withIndex()) {
            if (layoutMode == 1) {
                val h = a[3] / n
                w.tileTo(a[0], a[1] + i * h, a[2], if (i == n - 1) a[3] - h * (n - 1) else h)
            } else {
                val wd = a[2] / n
                w.tileTo(a[0] + i * wd, a[1], if (i == n - 1) a[2] - wd * (n - 1) else wd, a[3])
            }
        }
    }

    /** Pill ka ▤ button: normal -> upar-neeche -> side-by-side -> normal */
    fun cycleLayout() {
        if (windows.size < 2) {
            Toast.makeText(this, "Layout ke liye kam se kam 2 windows chahiye", Toast.LENGTH_SHORT).show()
            return
        }
        layoutMode = (layoutMode + 1) % 3
        applyLayout()
        val msg = when (layoutMode) {
            1 -> "Layout: ${windows.size} windows upar-neeche barabar"
            2 -> "Layout: ${windows.size} windows side-by-side barabar"
            else -> "Layout: pehle jaisa"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        handler.post { bringDotsToFront() }
    }

    private fun refreshBadges() {
        val multi = windows.size > 1
        for (w in windows) w.setBadge(multi)
    }

    /** Sab dots ko browser windows ke upar rakho taaki hamesha dikhen. */
    fun bringDotsToFront() {
        for (w in windows) w.reAddDot()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Lion", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, FloatingService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val installIntent = UpdateChecker.readyInstallIntent
        val hasPending = UpdateChecker.pending != null
        val text = when {
            downloadPercent in 0..99 -> "Update download ho raha hai: $downloadPercent%"
            installIntent != null -> "Update download ho gaya – \"Install karo\" dabao"
            hasPending -> "Naya version available"
            windows.size > 1 -> "${windows.size} windows khuli hain – \"Band karo\" se sab band"
            else -> "Band karne ke liye \"Band karo\" dabao"
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_lion)
            .setContentTitle("Lion chal raha hai")
            .setContentText(text)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Band karo", stopPi)
            .setOngoing(true)
        if (installIntent != null) {
            val installPi = PendingIntent.getActivity(
                this, 3, installIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(android.R.drawable.stat_sys_download_done, "Install karo", installPi)
        } else if (hasPending && downloadPercent < 0) {
            val upd = Intent(this, FloatingService::class.java).setAction(ACTION_UPDATE)
            val updPi = PendingIntent.getService(
                this, 2, upd, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(android.R.drawable.stat_sys_download, "Update karo", updPi)
        }
        return b.build()
    }

    private fun refreshNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
        }
    }

    // ── in-app update ─────────────────────────────────────────────────────
    private fun onUpdateFound(info: UpdateChecker.Info) {
        if (instance == null) return
        UpdateChecker.pending = info
        Toast.makeText(this, "Lion update available (tap notification)", Toast.LENGTH_LONG).show()
        refreshNotification()
    }

    private fun startUpdate(info: UpdateChecker.Info) {
        if (!UpdateChecker.ensureInstallPermission(this)) return
        Toast.makeText(this, "Download ho raha hai…", Toast.LENGTH_SHORT).show()
        downloadPercent = 0
        refreshNotification()
        UpdateChecker.downloadAndInstall(
            this, info,
            onProgress = { pct ->
                if (pct >= 100) {
                    downloadPercent = -1
                    refreshNotification()
                } else if (pct % 5 == 0 && pct != downloadPercent) {
                    downloadPercent = pct
                    refreshNotification()
                }
            },
            onError = { msg ->
                downloadPercent = -1
                refreshNotification()
                Toast.makeText(this, "Update nahi ho paya: $msg", Toast.LENGTH_LONG).show()
            },
            onInstallFailed = {
                // install screen seedha nahi khuli -> notification me "Install karo" action
                refreshNotification()
                Toast.makeText(this, "Notification me \"Install karo\" dabao", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    inner class FloatWindow(private val ctx: Context, val id: Int, private var url: String) {

        private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val density = ctx.resources.displayMetrics.density
        private val handler = Handler(Looper.getMainLooper())

        // ── Window B: browser ─────────────────────────────────────────────
        private lateinit var root: FrameLayout
        private lateinit var layoutFull: FrameLayout
        private lateinit var content: LinearLayout
        private lateinit var dragHandle: FrameLayout
        private lateinit var webView: WebView
        private lateinit var layoutSettings: ScrollView
        private lateinit var resizeCorner: TextView
        private lateinit var copyBar: LinearLayout
        private lateinit var params: WindowManager.LayoutParams

        // ── Window A: dot + pill ──────────────────────────────────────────
        private lateinit var dotRoot: LinearLayout
        private lateinit var dotWrapper: FrameLayout
        private lateinit var dotView: View
        private lateinit var badge: TextView
        private lateinit var pill: LinearLayout
        private lateinit var dotParams: WindowManager.LayoutParams

        // ── close zone (dot drag ke waqt) ─────────────────────────────────
        private lateinit var closeZone: FrameLayout
        private lateinit var closeParams: WindowManager.LayoutParams

        private lateinit var btnBack: TextView

        // gestures: edge swipe (back/forward) + pull-to-refresh
        private lateinit var swipeRefresh: SwipeRefreshLayout
        private lateinit var edgeLeft: View
        private lateinit var edgeRight: View
        private lateinit var swipeHint: TextView
        private lateinit var swipeHintBg: GradientDrawable
        private lateinit var gestureSwitch: Switch
        @Volatile private var innerScrolled = false
        private lateinit var btnRefresh: TextView
        private lateinit var btnMaximize: TextView
        private lateinit var btnWindow: TextView
        private lateinit var btnNew: TextView
        private lateinit var btnLayout: TextView
        private lateinit var btnSettings: TextView
        private lateinit var btnFiles: TextView
        private lateinit var btnClose: TextView

        private lateinit var inputUrl: EditText
        private lateinit var bookmarkBox: LinearLayout
        private lateinit var sbSize: SeekBar
        private lateinit var sbOpacity: SeekBar
        private lateinit var sbDotSize: SeekBar

        private val menuButtons = ArrayList<TextView>()

        private var isMaximized = false
        private var isBrowserShown = false
        private var isPillOpen = false
        private var pillOnLeft = false
        private var isCloseZoneVisible = false

        private var customView: View? = null
        private var customViewCallback: WebChromeClient.CustomViewCallback? = null
        private var wasMaximizedByJS = false

        // Copy bar state
        private var lastSelection = ""
        private var editFocused = false
        // selection engine ne clear kar di ho tab bhi bar kuch second tika rahe (last text yaad rehta hai)
        private val clearSelRunnable = Runnable {
            lastSelection = ""
            updateCopyBar()
        }

        // dot ka center (screen pixels)
        private var dotCX = 0
        private var dotCY = 0
        private var lastWindowX = 0
        private var lastWindowY = 0
        private var normalW = 0
        private var normalH = 0

        private val collapseRunnable = Runnable { closePill() }

        // dot long-press: pill menu kholo (haptic ke saath)
        private var longPressed = false
        private val longPressRunnable = Runnable {
            longPressed = true
            dotWrapper.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (isPillOpen) scheduleAutoCollapse() else openPill()
        }

        private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        // slot 0 purane keys use karta hai (purani saved position/URL na jaye)
        private fun dotKey(axis: String): String = if (id == 0) "dot_$axis" else "w${id}_dot_$axis"

        fun homeUrl(): String = url

        /** Layout: is window ko diye gaye hisse me rakho aur dikhao. */
        fun tileTo(x: Int, y: Int, w: Int, h: Int) {
            if (isMaximized) toggleMaximize()
            lastWindowX = x
            lastWindowY = y
            normalW = w
            normalH = h
            if (isBrowserShown) applyBrowserParams() else showBrowser()
        }

        /** Layout se pehle wali (saved) jagah/size wapas. */
        fun restoreGeometry() {
            val m = ctx.resources.displayMetrics
            lastWindowX = prefs.getInt("w${id}_win_x", dp(8) + id * dp(24))
                .coerceIn(-dp(40), maxOf(0, m.widthPixels - dp(60)))
            lastWindowY = prefs.getInt("w${id}_win_y", dp(60) + id * dp(24))
                .coerceIn(0, maxOf(0, m.heightPixels - dp(60)))
            normalW = prefs.getInt("w${id}_win_w", minOf(dp(360), m.widthPixels - dp(16)))
                .coerceIn(dp(200), m.widthPixels)
            normalH = prefs.getInt("w${id}_win_h", minOf(dp(500), m.heightPixels - dp(80)))
                .coerceIn(dp(260), m.heightPixels)
            if (isBrowserShown) applyBrowserParams()
        }

        fun openBrowser() { showBrowser() }

        fun setBadge(show: Boolean) {
            if (!::badge.isInitialized) return
            badge.visibility = if (show) View.VISIBLE else View.GONE
        }

        fun reAddDot() {
            try {
                wm.removeView(dotRoot)
                wm.addView(dotRoot, dotParams)
            } catch (e: Exception) { }
        }

        private fun dialogBuilder(): AlertDialog.Builder =
            AlertDialog.Builder(
                ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            )

        private fun showOverlayDialog(b: AlertDialog.Builder) {
            val d = b.create()
            d.window?.setType(overlayType())
            d.show()
        }

        // bookmark chun kar nayi window kholo
        private fun pickBookmarkForNewWindow() {
            val list = this@FloatingService.loadBookmarks()
            if (list.isEmpty()) {
                Toast.makeText(ctx, "Abhi koi bookmark nahi hai", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val names = Array(list.size) { list[it].title }
                showOverlayDialog(
                    dialogBuilder().setTitle("Bookmark se nayi window")
                        .setItems(names) { _, which ->
                            this@FloatingService.openNewWindow(list[which].url)
                        }
                        .setNegativeButton("Cancel", null)
                )
            } catch (e: Exception) { }
        }

        private fun refreshBookmarkList() {
            bookmarkBox.removeAllViews()
            val list = this@FloatingService.loadBookmarks()
            if (list.isEmpty()) {
                val t = TextView(ctx)
                t.text = "Abhi koi bookmark nahi hai"
                t.setTextColor(Color.parseColor("#8a8f9c"))
                t.textSize = 12f
                bookmarkBox.addView(t)
                return
            }
            for (bm in list) {
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setBackgroundColor(Color.parseColor("#2a2e39"))
                row.setPadding(dp(10), dp(6), dp(4), dp(6))

                val txt = TextView(ctx)
                txt.text = bm.title + "\n" + bm.url
                txt.setTextColor(Color.WHITE)
                txt.textSize = 12f
                txt.maxLines = 2
                txt.ellipsize = android.text.TextUtils.TruncateAt.END
                txt.setOnClickListener {
                    // ek tap: is window ka URL badlo aur load karo
                    prefs.edit().putString(urlKey(id), bm.url).apply()
                    url = bm.url
                    inputUrl.setText(url)
                    webView.loadUrl(url)
                    layoutSettings.visibility = View.GONE
                }
                row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val del = TextView(ctx)
                del.text = "✕"
                del.setTextColor(Color.parseColor("#ff6b6b"))
                del.textSize = 16f
                del.gravity = Gravity.CENTER
                del.setOnClickListener {
                    try {
                        showOverlayDialog(
                            dialogBuilder().setTitle("Bookmark hataye?")
                                .setMessage(bm.title)
                                .setPositiveButton("Hatao") { _, _ ->
                                    this@FloatingService.removeBookmark(bm.url)
                                    refreshBookmarkList()
                                }
                                .setNegativeButton("Rehne do", null)
                        )
                    } catch (e: Exception) { }
                }
                row.addView(del, LinearLayout.LayoutParams(dp(40), dp(40)))

                val rlp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                rlp.topMargin = dp(4)
                bookmarkBox.addView(row, rlp)
            }
        }

        private fun saveCurrentAsBookmark() {
            val u = webView.url ?: url
            val t = webView.title ?: ""
            when (this@FloatingService.addBookmark(t, u)) {
                0 -> Toast.makeText(ctx, "Bookmark ho gaya", Toast.LENGTH_SHORT).show()
                1 -> Toast.makeText(ctx, "Ye page pehle se bookmark hai", Toast.LENGTH_SHORT).show()
                2 -> Toast.makeText(ctx, "Bookmark list full ($BOOKMARK_MAX)", Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(ctx, "Is page ko bookmark nahi kar sakte", Toast.LENGTH_SHORT).show()
            }
            refreshBookmarkList()
        }

        private fun saveGeometry() {
            prefs.edit()
                .putInt("w${id}_win_x", lastWindowX).putInt("w${id}_win_y", lastWindowY)
                .putInt("w${id}_win_w", normalW).putInt("w${id}_win_h", normalH)
                .apply()
        }

        private fun promptNewWindow() {
            if (windows.size >= MAX_WINDOWS) {
                Toast.makeText(ctx, "Max $MAX_WINDOWS windows khul sakti hain", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val themed = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                val input = EditText(themed)
                input.setSingleLine(true)
                input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                input.setText(url)
                input.selectAll()
                val dialog = AlertDialog.Builder(themed)
                    .setTitle("Nayi window – URL daalo")
                    .setView(input)
                    .setPositiveButton("Kholo") { _, _ ->
                        this@FloatingService.openNewWindow(input.text.toString())
                    }
                    .setNeutralButton("Bookmarks") { _, _ -> pickBookmarkForNewWindow() }
                    .setNegativeButton("Cancel", null)
                    .create()
                dialog.window?.setType(overlayType())
                dialog.show()
            } catch (e: Exception) {
                this@FloatingService.openNewWindow(url)
            }
        }

        @Suppress("DEPRECATION")
        private fun overlayType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        // ── entry ─────────────────────────────────────────────────────────
        fun show() {
            val m = ctx.resources.displayMetrics
            // har window ka apna saved position/size (slot id ke hisaab se)
            lastWindowX = prefs.getInt("w${id}_win_x", dp(8) + id * dp(24))
                .coerceIn(-dp(40), maxOf(0, m.widthPixels - dp(60)))
            lastWindowY = prefs.getInt("w${id}_win_y", dp(60) + id * dp(24))
                .coerceIn(0, maxOf(0, m.heightPixels - dp(60)))
            // default: right edge, screen height ka 1/4 (har nayi window thodi neeche)
            dotCX = prefs.getInt(dotKey("x"), m.widthPixels - dp(24))
            dotCY = prefs.getInt(dotKey("y"), m.heightPixels / 4 + id * dp(64))
            dotCX = dotCX.coerceIn(0, m.widthPixels)
            dotCY = dotCY.coerceIn(0, m.heightPixels)

            buildUi()
            setupWindowParams()
            setupControls()
            setupWebView()
            loadSavedSettings()

            // pehle browser window (hidden), phir dot -> dot hamesha upar rahe
            wm.addView(root, params)
            wm.addView(dotRoot, dotParams)
            webView.loadUrl(url)
        }

        fun destroy() {
            handler.removeCallbacksAndMessages(null)
            try { CookieManager.getInstance().flush() } catch (e: Exception) { }
            if (isCloseZoneVisible) {
                try { wm.removeView(closeZone) } catch (e: Exception) { }
                isCloseZoneVisible = false
            }
            try { wm.removeView(dotRoot) } catch (e: Exception) { }
            try { wm.removeView(root) } catch (e: Exception) { }
            try { webView.destroy() } catch (e: Exception) { }
        }

        // ── UI (code se banta hai, XML ki zaroorat nahi) ───────────────────
        private fun mkBtn(label: String): TextView {
            val t = TextView(ctx)
            t.text = label
            t.setTextColor(Color.WHITE)
            t.gravity = Gravity.CENTER
            t.textSize = 14f
            t.layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            menuButtons.add(t)
            return t
        }

        private fun buildUi() {
            // ---------- Window B: browser ----------
            root = FrameLayout(ctx)
            root.visibility = View.GONE

            layoutFull = FrameLayout(ctx)
            layoutFull.setBackgroundColor(Color.parseColor("#131722"))

            content = LinearLayout(ctx)
            content.orientation = LinearLayout.VERTICAL

            // upar patla drag handle (horizontal, vertical side patti nahi)
            dragHandle = FrameLayout(ctx)
            dragHandle.setBackgroundColor(Color.parseColor("#1e222d"))
            val grip = View(ctx)
            val gripShape = GradientDrawable()
            gripShape.shape = GradientDrawable.RECTANGLE
            gripShape.cornerRadius = dp(2).toFloat()
            gripShape.setColor(Color.parseColor("#88FFFFFF"))
            grip.background = gripShape
            dragHandle.addView(grip, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))

            webView = LionWebView(ctx)

            content.addView(dragHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
            // webView -> SwipeRefreshLayout (pull-to-refresh) -> holder (+ invisible edge zones + hint arrow)
            swipeRefresh = SwipeRefreshLayout(ctx)
            swipeRefresh.addView(
                webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            val holder = FrameLayout(ctx)
            holder.addView(
                swipeRefresh, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            edgeLeft = View(ctx)
            edgeRight = View(ctx)
            holder.addView(
                edgeLeft,
                FrameLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
            )
            holder.addView(
                edgeRight,
                FrameLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            )
            swipeHint = TextView(ctx)
            swipeHint.gravity = Gravity.CENTER
            swipeHint.setTextColor(Color.WHITE)
            swipeHint.textSize = 16f
            swipeHintBg = GradientDrawable()
            swipeHintBg.shape = GradientDrawable.OVAL
            swipeHintBg.setColor(Color.parseColor("#E61e222d"))
            swipeHint.background = swipeHintBg
            swipeHint.visibility = View.GONE
            holder.addView(
                swipeHint, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.START)
            )
            content.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            layoutFull.addView(
                content,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            resizeCorner = TextView(ctx)
            resizeCorner.text = "◢"
            resizeCorner.setTextColor(Color.parseColor("#CCFFFFFF"))
            resizeCorner.setBackgroundColor(Color.parseColor("#66000000"))
            resizeCorner.gravity = Gravity.CENTER
            resizeCorner.textSize = 14f
            layoutFull.addView(
                resizeCorner,
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END)
            )

            buildCopyBar()
            layoutFull.addView(
                copyBar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).also { it.bottomMargin = dp(32) }
            )

            buildSettings()
            layoutFull.addView(
                layoutSettings,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            root.addView(
                layoutFull,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            // ---------- Window A: dot + pill ----------
            dotRoot = LinearLayout(ctx)
            dotRoot.orientation = LinearLayout.HORIZONTAL
            dotRoot.gravity = Gravity.CENTER_VERTICAL

            dotWrapper = FrameLayout(ctx)
            dotView = View(ctx)
            val dotShape = GradientDrawable()
            dotShape.shape = GradientDrawable.OVAL
            val dotColors = arrayOf("#F5A623", "#29B6F6", "#66BB6A")
            dotShape.setColor(Color.parseColor(dotColors[id % dotColors.size]))
            dotShape.setStroke(maxOf(1, (1.5f * density).toInt()), Color.parseColor("#B3FFFFFF"))
            dotView.background = dotShape
            dotWrapper.addView(dotView, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            // window number (sirf tab dikhta hai jab 2+ windows ho)
            badge = TextView(ctx)
            badge.text = (id + 1).toString()
            badge.gravity = Gravity.CENTER
            badge.setTextColor(Color.parseColor("#1A1A1A"))
            badge.setTypeface(null, Typeface.BOLD)
            badge.textSize = 9f
            badge.visibility = View.GONE
            dotWrapper.addView(
                badge,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            dotRoot.addView(dotWrapper, LinearLayout.LayoutParams(dp(40), dp(40)))

            // pill: dark rounded row, dot ke paas khulta hai
            pill = LinearLayout(ctx)
            pill.orientation = LinearLayout.HORIZONTAL
            pill.gravity = Gravity.CENTER
            pill.setPadding(dp(4), dp(4), dp(4), dp(4))
            val pillShape = GradientDrawable()
            pillShape.shape = GradientDrawable.RECTANGLE
            pillShape.cornerRadius = dp(20).toFloat()
            pillShape.setColor(Color.parseColor("#F21e222d")) // ~0.95 alpha
            pill.background = pillShape

            btnBack = mkBtn("◀")
            btnRefresh = mkBtn("⟳")
            btnMaximize = mkBtn("⛶")
            btnWindow = mkBtn("🪟")
            btnNew = mkBtn("➕")
            btnLayout = mkBtn("▤")
            btnFiles = mkBtn("📁")
            btnSettings = mkBtn("⚙")
            btnClose = mkBtn("✕")
            for (b in menuButtons) pill.addView(b)

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

        // ── Copy bar (Copy / Select all / Paste) ──────────────────────────
        private fun copyBtn(text: String, onTap: () -> Unit): TextView {
            val t = TextView(ctx)
            t.text = text
            t.setTextColor(Color.WHITE)
            t.textSize = 13f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(14), dp(8), dp(14), dp(8))
            t.setOnClickListener { onTap() }
            return t
        }

        private fun buildCopyBar() {
            copyBar = LinearLayout(ctx)
            copyBar.orientation = LinearLayout.HORIZONTAL
            copyBar.gravity = Gravity.CENTER
            copyBar.isClickable = true
            copyBar.visibility = View.GONE
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.RECTANGLE
            shape.cornerRadius = dp(20).toFloat()
            shape.setColor(Color.parseColor("#F21e222d"))
            shape.setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            copyBar.background = shape
            copyBar.addView(copyBtn("Copy") { doCopy() })
            copyBar.addView(copyBtn("Select all") { doSelectAll() })
            copyBar.addView(copyBtn("Paste") { doPaste() })
        }

        private fun updateCopyBar() {
            copyBar.visibility =
                if (lastSelection.isNotBlank() || editFocused) View.VISIBLE else View.GONE
        }

        private fun doCopy() {
            if (lastSelection.isBlank()) {
                Toast.makeText(ctx, "Pehle text select karo", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Lion", lastSelection))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Copy nahi ho paya", Toast.LENGTH_SHORT).show()
                return
            }
            webView.evaluateJavascript(
                "(function(){var a=document.activeElement;" +
                    "if(a&&(a.tagName==='TEXTAREA'||a.tagName==='INPUT')&&typeof a.selectionStart==='number'){a.selectionEnd=a.selectionStart;}" +
                    "window.getSelection().removeAllRanges();})()",
                null
            )
            handler.removeCallbacks(clearSelRunnable)
            lastSelection = ""
            updateCopyBar()
        }

        private fun doSelectAll() {
            webView.requestFocus()
            webView.evaluateJavascript("document.execCommand('selectAll')", null)
        }

        private fun doPaste() {
            val text: String? = try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx)?.toString() else null
            } catch (e: Exception) {
                null
            }
            if (text.isNullOrEmpty()) {
                Toast.makeText(ctx, "Clipboard khali/blocked", Toast.LENGTH_SHORT).show()
                return
            }
            webView.requestFocus()
            webView.evaluateJavascript(
                "document.execCommand('insertText', false, " + JSONObject.quote(text) + ")",
                null
            )
        }

        // JS -> Kotlin: selection / input focus ki khabar
        inner class Bridge {
            // page ke andar koi scrollable div scroll hua ho to pull-to-refresh na chale
            @JavascriptInterface
            fun onInnerScroll(scrolled: Boolean) {
                innerScrolled = scrolled
            }

            @JavascriptInterface
            fun onState(text: String?, editable: Boolean) {
                handler.post {
                    handler.removeCallbacks(clearSelRunnable)
                    if (!text.isNullOrBlank()) {
                        lastSelection = text
                    } else {
                        // khali aaya: turant mat hatao, 6 sec baad hatao
                        handler.postDelayed(clearSelRunnable, 6000L)
                    }
                    editFocused = editable
                    updateCopyBar()
                }
            }

            // <select> dropdown: JS ne pakda, yahan overlay dialog me list dikhao
            @JavascriptInterface
            fun pickSelect(json: String?) {
                handler.post {
                    try {
                        val o = org.json.JSONObject(json ?: return@post)
                        val arr = o.getJSONArray("o")
                        val labels = ArrayList<String>()
                        val off = HashSet<Int>()
                        for (i in 0 until arr.length()) {
                            val it = arr.getJSONObject(i)
                            labels.add(it.optString("t"))
                            if (it.optInt("d", 0) == 1) off.add(i)
                        }
                        if (labels.isEmpty()) return@post
                        DlUi.selectPick(ctx, true, o.optString("title"), labels, o.optInt("i", -1)) { idx ->
                            if (idx !in off) webView.evaluateJavascript("window.__lionSelSet && window.__lionSelSet(" + idx + ")", null)
                        }
                    } catch (e: Exception) {
                    }
                }
            }

            // page par naya touch aaya aur koi selection nahi: bar turant hata do
            @JavascriptInterface
            fun onTap() {
                handler.post {
                    handler.removeCallbacks(clearSelRunnable)
                    lastSelection = ""
                    updateCopyBar()
                }
            }
        }

        // <select> dropdown fix: floating (overlay) window me native popup nahi khulta.
        // Tap pakad ke apni list dikhate hain; chunne par value set + input/change event.
        private val selectJs = """
(function () {
  if (window.__lionSel) return;
  window.__lionSel = 1;
  var tx = 0, ty = 0, moved = false, lastOpen = 0;
  function selOf(ev) {
    var t = ev.target;
    var s = t && t.closest ? t.closest('select') : null;
    if (!s || s.disabled || s.multiple || s.size > 1) return null;
    return s;
  }
  function label(s) {
    var l = s.getAttribute('aria-label') || '';
    if (!l && s.labels && s.labels[0]) l = s.labels[0].textContent || '';
    if (!l) l = s.name || s.id || '';
    return l.replace(/\s+/g, ' ').trim();
  }
  function openSel(s) {
    var now = Date.now();
    if (now - lastOpen < 500) return;
    lastOpen = now;
    var opts = [];
    for (var i = 0; i < s.options.length; i++) {
      var o = s.options[i];
      opts.push({ t: (o.text || o.label || o.value || '').replace(/\s+/g, ' ').trim(), d: (o.disabled ? 1 : 0) });
    }
    window.__lionSelEl = s;
    try { s.blur(); } catch (e) {}
    try { LionBridge.pickSelect(JSON.stringify({ i: s.selectedIndex, o: opts, title: label(s) })); } catch (e) {}
  }
  window.__lionSelSet = function (i) {
    var s = window.__lionSelEl;
    if (!s || i < 0 || i >= s.options.length) return;
    s.selectedIndex = i;
    s.dispatchEvent(new Event('input', { bubbles: true }));
    s.dispatchEvent(new Event('change', { bubbles: true }));
  };
  document.addEventListener('touchstart', function (ev) {
    var t = ev.touches && ev.touches[0];
    if (t) { tx = t.clientX; ty = t.clientY; moved = false; }
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function (ev) {
    var t = ev.touches && ev.touches[0];
    if (t && (Math.abs(t.clientX - tx) > 10 || Math.abs(t.clientY - ty) > 10)) moved = true;
  }, { capture: true, passive: true });
  document.addEventListener('touchend', function (ev) {
    var s = selOf(ev);
    if (!s || moved) return;
    ev.preventDefault();
    openSel(s);
  }, { capture: true, passive: false });
  ['mousedown', 'click'].forEach(function (n) {
    document.addEventListener(n, function (ev) {
      var s = selOf(ev);
      if (!s) return;
      ev.preventDefault();
      ev.stopPropagation();
      if (n === 'click') openSel(s);
    }, true);
  });
})();
""".trimIndent()

        private val scrollJs = """
            (function () {
              if (window.__lionScr) return;
              window.__lionScr = true;
              function chk(t) {
                try {
                  while (t && t !== document.documentElement) {
                    if (t.scrollTop > 0) {
                      var o = getComputedStyle(t).overflowY;
                      if (o === 'auto' || o === 'scroll' || o === 'overlay') return true;
                    }
                    t = t.parentElement;
                  }
                } catch (e) {}
                return false;
              }
              document.addEventListener('touchstart', function (e) {
                try { LionBridge.onInnerScroll(chk(e.target)); } catch (x) {}
                try { if (String(window.getSelection()) === '') LionBridge.onTap(); } catch (x) {}
              }, { capture: true, passive: true });
            })();
        """.trimIndent()

        private val selectionJs = """
            (function () {
              if (window.__lionSel) return;
              window.__lionSel = true;
              function isEd(el) {
                if (!el) return false;
                var t = el.tagName;
                return t === 'TEXTAREA' || t === 'INPUT' || el.isContentEditable === true;
              }
              function sel() {
                var t = '';
                var a = document.activeElement;
                try {
                  if (a && (a.tagName === 'TEXTAREA' || a.tagName === 'INPUT') && typeof a.selectionStart === 'number') {
                    t = String(a.value).substring(a.selectionStart, a.selectionEnd);
                  }
                } catch (e) {}
                if (!t) t = String(window.getSelection());
                return t;
              }
              function push() {
                try { LionBridge.onState(sel(), isEd(document.activeElement)); } catch (e) {}
              }
              document.addEventListener('selectionchange', push);
              document.addEventListener('focusin', push);
              document.addEventListener('focusout', function () { setTimeout(push, 50); });
              document.addEventListener('keyup', push);
              document.addEventListener('touchend', function () { setTimeout(push, 50); });
            })();
        """.trimIndent()

        private fun label(text: String): TextView {
            val t = TextView(ctx)
            t.text = text
            t.setTextColor(Color.parseColor("#d1d4dc"))
            t.textSize = 13f
            t.setPadding(0, dp(12), 0, dp(2))
            return t
        }

        private fun blueButton(text: String): Button {
            val b = Button(ctx)
            b.text = text
            b.setTextColor(Color.WHITE)
            b.setBackgroundColor(Color.parseColor("#2962ff"))
            return b
        }

        private fun buildSettings() {
            layoutSettings = ScrollView(ctx)
            layoutSettings.setBackgroundColor(Color.parseColor("#F01e222d"))
            layoutSettings.visibility = View.GONE

            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            col.addView(label("Website URL"), lp)
            inputUrl = EditText(ctx)
            inputUrl.setTextColor(Color.WHITE)
            inputUrl.textSize = 13f
            inputUrl.setSingleLine(true)
            inputUrl.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            col.addView(inputUrl, lp)

            val saveBtn = blueButton("Save URL aur load karo")
            saveBtn.setOnClickListener {
                val newUrl = normalizeUrl(inputUrl.text.toString())
                if (newUrl.isNotEmpty()) {
                    prefs.edit().putString(urlKey(id), newUrl).apply()
                    url = newUrl
                    webView.loadUrl(url)
                    layoutSettings.visibility = View.GONE
                }
            }
            col.addView(saveBtn, lp)

            col.addView(label("Bookmarks"), lp)
            val bmSaveBtn = blueButton("⭐ Current page bookmark karo")
            bmSaveBtn.setOnClickListener { saveCurrentAsBookmark() }
            col.addView(bmSaveBtn, lp)
            bookmarkBox = LinearLayout(ctx)
            bookmarkBox.orientation = LinearLayout.VERTICAL
            col.addView(bookmarkBox, lp)

            col.addView(label("Icon size"), lp)
            sbSize = SeekBar(ctx)
            sbSize.max = 10
            col.addView(sbSize, lp)

            col.addView(label("Dot opacity"), lp)
            sbOpacity = SeekBar(ctx)
            sbOpacity.max = 100
            col.addView(sbOpacity, lp)

            col.addView(label("Dot size (10–28dp)"), lp)
            sbDotSize = SeekBar(ctx)
            sbDotSize.max = 18 // progress 0..18 -> 10..28dp
            col.addView(sbDotSize, lp)

            gestureSwitch = Switch(ctx)
            gestureSwitch.text = "Swipe gestures (edge swipe back/forward)"
            gestureSwitch.setTextColor(Color.parseColor("#d1d4dc"))
            gestureSwitch.textSize = 13f
            gestureSwitch.setPadding(0, dp(12), 0, dp(4))
            gestureSwitch.isChecked = prefs.getBoolean("gestures_on", true)
            col.addView(gestureSwitch, lp)

            val dlBtn = blueButton("⬇ Downloads / Schedule")
            dlBtn.setOnClickListener { Downloader.manager(ctx, true) }
            col.addView(dlBtn, lp)

            val dlQueueBtn = blueButton("📥 Downloads (phone + server)")
            dlQueueBtn.setOnClickListener { DlUi.queue(ctx, true) }
            col.addView(dlQueueBtn, lp)

            val dlSetBtn = blueButton("⚡ Download settings (fast / retry / folders)")
            dlSetBtn.setOnClickListener { DlUi.settings(ctx, true) }
            col.addView(dlSetBtn, lp)

            val histBtn = blueButton("📜 History")
            histBtn.setOnClickListener {
                History.show(ctx, true) { u ->
                    webView.loadUrl(u)
                    layoutSettings.visibility = View.GONE
                }
            }
            col.addView(histBtn, lp)

            val updateBtn = blueButton("Update check karo (v${BuildConfig.VERSION_NAME})")
            updateBtn.setOnClickListener { manualCheckUpdate(updateBtn) }
            col.addView(updateBtn, lp)

            val closeBtn = blueButton("Settings band karo")
            closeBtn.setOnClickListener { layoutSettings.visibility = View.GONE }
            col.addView(closeBtn, lp)

            layoutSettings.addView(col)
        }

        private fun manualCheckUpdate(btn: Button) {
            val oldText = btn.text
            btn.isEnabled = false
            btn.text = "Check ho raha hai…"
            UpdateChecker.check(ctx) { info ->
                btn.isEnabled = true
                btn.text = oldText
                if (info == null) {
                    Toast.makeText(ctx, "Lion up to date", Toast.LENGTH_SHORT).show()
                } else {
                    UpdateChecker.pending = info
                    refreshNotification()
                    try {
                        val themed = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        val dialog = AlertDialog.Builder(themed)
                            .setTitle("Update available")
                            .setMessage("Lion ka naya version mila. Abhi update karein?")
                            .setPositiveButton("Update karo") { _, _ -> startUpdate(info) }
                            .setNegativeButton("Baad me", null)
                            .create()
                        dialog.window?.setType(overlayType())
                        dialog.show()
                    } catch (e: Exception) {
                        startUpdate(info)
                    }
                }
            }
        }

        // ── window params ─────────────────────────────────────────────────
        private fun setupWindowParams() {
            val m = ctx.resources.displayMetrics
            normalW = prefs.getInt("w${id}_win_w", minOf(dp(360), m.widthPixels - dp(16)))
                .coerceIn(dp(200), m.widthPixels)
            normalH = prefs.getInt("w${id}_win_h", minOf(dp(500), m.heightPixels - dp(80)))
                .coerceIn(dp(260), m.heightPixels)

            // Window B (browser): shuru me hidden
            params = WindowManager.LayoutParams(
                1, 1, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= 28) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0

            // Window A (dot + pill): kabhi focus nahi leta
            dotParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= 28) {
                dotParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            dotParams.gravity = Gravity.TOP or Gravity.START
            dotParams.x = dotCX - dp(20)
            dotParams.y = dotCY - dp(20)
        }

        private fun updateBrowserLayout() {
            try { wm.updateViewLayout(root, params) } catch (e: Exception) { }
        }

        private fun updateDotLayout() {
            try { wm.updateViewLayout(dotRoot, dotParams) } catch (e: Exception) { }
        }

        // browser window ka size/flags: hidden ya visible, normal ya maximized
        private fun applyBrowserParams() {
            if (!isBrowserShown) {
                params.x = 0
                params.y = 0
                params.width = 1
                params.height = 1
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                if (isMaximized) {
                    params.x = 0
                    params.y = 0
                    params.width = WindowManager.LayoutParams.MATCH_PARENT
                    params.height = WindowManager.LayoutParams.MATCH_PARENT
                } else {
                    params.x = lastWindowX
                    params.y = lastWindowY
                    params.width = normalW
                    params.height = normalH
                }
                // focusable (no NOT_FOCUSABLE): keyboard / text input ke liye
                var f = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                // maximize: status bar + navigation bar ke peeche tak poori screen
                if (isMaximized) f = f or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                params.flags = f
            }
            updateBrowserLayout()
        }

        private fun applyTextSize(size: Float) {
            for (b in menuButtons) b.textSize = size
        }

        private fun applyOpacity(progress: Int) {
            dotView.alpha = maxOf(progress, 25) / 100f
        }

        private fun applyDotSize(sizeDp: Int) {
            val s = dp(sizeDp.coerceIn(10, 28))
            val lp = dotView.layoutParams as FrameLayout.LayoutParams
            lp.width = s
            lp.height = s
            dotView.layoutParams = lp
            if (::badge.isInitialized) {
                badge.textSize = (sizeDp.coerceIn(10, 28) * 0.55f).coerceIn(7f, 15f)
            }
        }

        // theme (icon size / opacity / dot size) sab windows ke liye shared hai
        fun applyThemeFromPrefs() {
            applyTextSize(12f + prefs.getInt("theme_size", 2))
            applyOpacity(prefs.getInt("theme_opacity", 75))
            applyDotSize(prefs.getInt("dot_size", 16))
            applyGesturePrefs()
        }

        // gestures on = edge zones (pull-to-refresh hamesha off), pill me back button nahi; off = purana back button
        private fun applyGesturePrefs() {
            if (!::swipeRefresh.isInitialized) return
            val on = prefs.getBoolean("gestures_on", true)
            val edgeVis = if (on && customView == null) View.VISIBLE else View.GONE
            edgeLeft.visibility = edgeVis
            edgeRight.visibility = edgeVis
            swipeRefresh.isEnabled = false // pull-to-refresh permanently off (chart touch lag/accidental reload)
            val backVis = if (on) View.GONE else View.VISIBLE
            if (btnBack.visibility != backVis) {
                btnBack.visibility = backVis
                if (isPillOpen) placeDotWindow()
            }
        }

        private fun loadSavedSettings() {
            val size = prefs.getInt("theme_size", 2)
            sbSize.progress = size
            sbOpacity.progress = prefs.getInt("theme_opacity", 75)
            sbDotSize.progress = prefs.getInt("dot_size", 16).coerceIn(10, 28) - 10
            applyThemeFromPrefs()

            inputUrl.setText(prefs.getString(urlKey(id), url))
        }

        private fun syncOtherWindowsTheme() {
            for (w in windows) if (w !== this) w.applyThemeFromPrefs()
        }

        // ── dot + pill ────────────────────────────────────────────────────
        private fun pillW(): Int = dp(32) * menuButtons.count { it.visibility != View.GONE } + dp(8)

        // dot ke center ke hisaab se Window A ki jagah set karo
        private fun placeDotWindow() {
            var x = dotCX - dp(20)
            if (isPillOpen) {
                if (pillOnLeft) x -= pillW()
                val sw = ctx.resources.displayMetrics.widthPixels
                val totalW = dp(40) + pillW()
                x = x.coerceIn(0, maxOf(0, sw - totalW)) // screen se bahar na jaye
            }
            dotParams.x = x
            dotParams.y = dotCY - dp(20)
            updateDotLayout()
        }

        private fun scheduleAutoCollapse() {
            handler.removeCallbacks(collapseRunnable)
            handler.postDelayed(collapseRunnable, 4000L)
        }

        private fun openPill() {
            if (isPillOpen) return
            val sw = ctx.resources.displayMetrics.widthPixels
            val pw = pillW()
            val fitsLeft = dotCX - dp(20) - pw >= 0
            val fitsRight = dotCX + dp(20) + pw <= sw
            val preferLeft = dotCX > sw / 2 // right half me ho to pill left me
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

        private fun togglePill() {
            if (isPillOpen) closePill() else openPill()
        }

        // ── browser show / hide ───────────────────────────────────────────
        private fun showBrowser() {
            if (isBrowserShown) return
            isBrowserShown = true
            // is window ko baaki windows ke upar lao (abhi hidden hai, isliye safe)
            try {
                wm.removeView(root)
                wm.addView(root, params)
            } catch (e: Exception) { }
            root.visibility = View.VISIBLE
            btnWindow.text = "—"
            applyBrowserParams()
            // dots hamesha browser windows ke upar rahen
            handler.post { this@FloatingService.bringDotsToFront() }
        }

        private fun hideBrowser() {
            if (!isBrowserShown) return
            layoutSettings.visibility = View.GONE
            isBrowserShown = false
            root.visibility = View.GONE
            btnWindow.text = "🪟"
            applyBrowserParams()
        }

        private fun toggleBrowser() {
            if (isBrowserShown) hideBrowser() else showBrowser()
        }

        // ── controls ──────────────────────────────────────────────────────
        // ── gestures ──────────────────────────────────────────────────────
        private fun setupGestures() {
            swipeRefresh.setColorSchemeColors(Color.parseColor("#2962ff"))
            swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1e222d"))
            // pull-to-refresh gesture band: disabled layout touch intercept hi nahi karta, chart touch seedha WebView ko jata h
            swipeRefresh.isEnabled = false
            swipeRefresh.setOnChildScrollUpCallback { _, _ -> true }
            edgeLeft.setOnTouchListener(edgeTouch(true))
            edgeRight.setOnTouchListener(edgeTouch(false))
            gestureSwitch.setOnCheckedChangeListener { _, on ->
                if (prefs.getBoolean("gestures_on", true) == on) return@setOnCheckedChangeListener
                prefs.edit().putBoolean("gestures_on", on).apply()
                applyGesturePrefs()
                syncOtherWindowsTheme()
            }
        }

        // invisible edge zone: left se andar = Back, right se andar = Forward
        private fun edgeTouch(isBack: Boolean): View.OnTouchListener {
            val threshold = dp(80)
            val startSlop = dp(8)
            var sx = 0f
            var sy = 0f
            var reached = false
            return View.OnTouchListener { v, e ->
                val can = if (isBack) webView.canGoBack() else webView.canGoForward()
                val dx = if (isBack) e.rawX - sx else sx - e.rawX
                val dy = abs(e.rawY - sy)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        reached = false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (can && dx > startSlop && dx > dy) {
                            val p = (dx / threshold).coerceIn(0f, 1f)
                            val size = dp(36)
                            val lp = swipeHint.layoutParams as FrameLayout.LayoutParams
                            lp.gravity = Gravity.TOP or (if (isBack) Gravity.START else Gravity.END)
                            swipeHint.layoutParams = lp
                            swipeHint.text = if (isBack) "◀" else "▶"
                            val travel = p * (size + dp(12))
                            swipeHint.translationX = if (isBack) -size + travel else size - travel
                            swipeHint.translationY = e.y - size / 2f
                            swipeHint.alpha = p
                            swipeHint.visibility = View.VISIBLE
                            val nowReached = dx >= threshold
                            if (nowReached != reached) {
                                reached = nowReached
                                swipeHintBg.setColor(Color.parseColor(if (reached) "#2962ff" else "#E61e222d"))
                                if (reached) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        } else {
                            swipeHint.visibility = View.GONE
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        swipeHint.visibility = View.GONE
                        swipeHintBg.setColor(Color.parseColor("#E61e222d"))
                        if (can && dx >= threshold && dx > dy * 1.5f) {
                            if (isBack) webView.goBack() else webView.goForward()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        swipeHint.visibility = View.GONE
                        swipeHintBg.setColor(Color.parseColor("#E61e222d"))
                    }
                }
                true
            }
        }

        private fun setupControls() {
            setupGestures()
            btnRefresh.setOnClickListener {
                scheduleAutoCollapse()
                showBrowser()
                webView.reload()
            }
            btnBack.setOnClickListener {
                scheduleAutoCollapse()
                showBrowser()
                if (webView.canGoBack()) webView.goBack()
            }
            btnMaximize.setOnClickListener {
                scheduleAutoCollapse()
                showBrowser()
                toggleMaximize()
            }
            btnWindow.setOnClickListener {
                scheduleAutoCollapse()
                toggleBrowser()
            }
            btnFiles.setOnClickListener {
                scheduleAutoCollapse()
                try {
                    ctx.startActivity(
                        Intent(ctx, com.example.simplebrowser.files.HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Toast.makeText(ctx, "File Manager nahi khula: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
            btnSettings.setOnClickListener {
                scheduleAutoCollapse()
                showBrowser()
                refreshBookmarkList()
                gestureSwitch.isChecked = prefs.getBoolean("gestures_on", true)
                layoutSettings.visibility = View.VISIBLE
            }
            btnNew.setOnClickListener {
                scheduleAutoCollapse()
                promptNewWindow()
            }
            btnLayout.setOnClickListener {
                scheduleAutoCollapse()
                this@FloatingService.cycleLayout()
            }
            btnClose.setOnClickListener { this@FloatingService.closeWindow(this) }

            // dot: tap = browser show/hide, long-press = pill menu, drag = move, ❌ zone me chhodo = band
            var sX = 0; var sY = 0; var sTX = 0f; var sTY = 0f; var dragging = false
            dotWrapper.setOnTouchListener { _, e ->
                val m = ctx.resources.displayMetrics
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sX = dotCX; sY = dotCY; sTX = e.rawX; sTY = e.rawY
                        dragging = false
                        longPressed = false
                        handler.removeCallbacks(longPressRunnable)
                        handler.postDelayed(
                            longPressRunnable,
                            ViewConfiguration.getLongPressTimeout().toLong()
                        )
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
                            dotCX = (sX + (e.rawX - sTX).toInt()).coerceIn(0, m.widthPixels)
                            dotCY = (sY + (e.rawY - sTY).toInt()).coerceIn(0, m.heightPixels)
                            placeDotWindow()
                            val over = isOverCloseZone(e.rawX, e.rawY, m.widthPixels, m.heightPixels)
                            closeZone.scaleX = if (over) 1.3f else 1.0f
                            closeZone.scaleY = if (over) 1.3f else 1.0f
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (dragging) {
                            hideCloseZone()
                            if (isOverCloseZone(e.rawX, e.rawY, m.widthPixels, m.heightPixels)) {
                                this@FloatingService.closeWindow(this)
                            } else {
                                prefs.edit().putInt(dotKey("x"), dotCX).putInt(dotKey("y"), dotCY).apply()
                            }
                        } else if (longPressed) {
                            // menu khul chuka hai, ungli uthane par kuch nahi
                        } else {
                            // simple tap
                            if (isPillOpen) closePill() else toggleBrowser()
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

            // browser window drag (upar ka patla handle)
            var dX = 0; var dY = 0; var dTX = 0f; var dTY = 0f
            dragHandle.setOnTouchListener { _, e ->
                if (isMaximized) {
                    false
                } else when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = params.x; dY = params.y; dTX = e.rawX; dTY = e.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = dX + (e.rawX - dTX).toInt()
                        params.y = dY + (e.rawY - dTY).toInt()
                        lastWindowX = params.x
                        lastWindowY = params.y
                        updateBrowserLayout()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        saveGeometry()
                        true
                    }
                    else -> false
                }
            }

            // resize (◢)
            var rW = 0; var rH = 0; var rTX = 0f; var rTY = 0f
            resizeCorner.setOnTouchListener { _, e ->
                if (isMaximized) {
                    false
                } else when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        rW = params.width; rH = params.height; rTX = e.rawX; rTY = e.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.width = maxOf(dp(200), rW + (e.rawX - rTX).toInt())
                        params.height = maxOf(dp(260), rH + (e.rawY - rTY).toInt())
                        normalW = params.width
                        normalH = params.height
                        updateBrowserLayout()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        saveGeometry()
                        true
                    }
                    else -> false
                }
            }

            // settings seekbars
            sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putInt("theme_size", progress).apply()
                    applyTextSize(12f + progress)
                    if (fromUser) syncOtherWindowsTheme()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putInt("theme_opacity", progress).apply()
                    applyOpacity(progress)
                    if (fromUser) syncOtherWindowsTheme()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            sbDotSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val s = progress + 10
                    if (fromUser) prefs.edit().putInt("dot_size", s).apply()
                    applyDotSize(s)
                    if (fromUser) syncOtherWindowsTheme()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        private fun showCloseZone() {
            if (isCloseZoneVisible) return
            try {
                wm.addView(closeZone, closeParams)
                isCloseZoneVisible = true
            } catch (e: Exception) { }
        }

        private fun hideCloseZone() {
            if (!isCloseZoneVisible) return
            try { wm.removeView(closeZone) } catch (e: Exception) { }
            isCloseZoneVisible = false
        }

        private fun isOverCloseZone(x: Float, y: Float, screenW: Int, screenH: Int): Boolean =
            y > screenH - dp(140) && x > screenW / 3 && x < (screenW * 2) / 3

        // ── maximize ──────────────────────────────────────────────────────
        private fun toggleMaximize() {
            isMaximized = !isMaximized
            dragHandle.visibility = if (isMaximized) View.GONE else View.VISIBLE
            applyBrowserParams()
        }

        // ── WebView ───────────────────────────────────────────────────────
        @Suppress("SetJavaScriptEnabled")
        private fun setupWebView() {
            val s = webView.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.allowContentAccess = true
            s.allowFileAccess = false
            s.setSupportMultipleWindows(true)
            s.javaScriptCanOpenWindowsAutomatically = true
            s.userAgentString = PopupLogin.cleanUa(ctx)
            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // Streamlit image/canvas OOM fix: hardware layer nahi
            webView.setLayerType(View.LAYER_TYPE_NONE, null)

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, true)

            webView.isFocusable = true
            webView.isFocusableInTouchMode = true

            // system long-press selection chalne do + apna Copy bar
            webView.isLongClickable = true
            webView.setOnLongClickListener { false }
            webView.addJavascriptInterface(Bridge(), "LionBridge")

            // file download: Abhi / Schedule dialog (overlay)
            webView.addJavascriptInterface(BlobDl.Bridge(ctx.applicationContext), BlobDl.BRIDGE_NAME)
            webView.addJavascriptInterface(VideoDetect.Bridge(ctx, true), VideoDetect.BRIDGE_NAME)
            webView.setDownloadListener { dlUrl, ua, cd, mime, len ->
                Downloader.offer(ctx, true, dlUrl, ua, cd, mime, len, webView.url, webView)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                    handler.removeCallbacks(clearSelRunnable)
                    lastSelection = ""
                    editFocused = false
                    updateCopyBar()
                    VideoDetect.inject(view)
                    view?.evaluateJavascript(selectJs, null)
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    CookieManager.getInstance().flush()
                    view?.evaluateJavascript(selectionJs, null)
                    view?.evaluateJavascript(scrollJs, null)
                    view?.evaluateJavascript(selectJs, null)
                    VideoDetect.inject(view)
                    swipeRefresh.isRefreshing = false
                    History.touchTitle(ctx, pageUrl, view?.title)
                }

                // har navigation (SPA pushState bhi) history me
                override fun doUpdateVisitedHistory(view: WebView?, visitedUrl: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, visitedUrl, isReload)
                    History.record(ctx, visitedUrl, view?.title)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return false
                    val scheme = (target.scheme ?: return false).lowercase()
                    // http/https (google search, login, kuch bhi) hamesha isi window me khule
                    if (scheme == "http" || scheme == "https") return false
                    // page ke apne internal schemes bhi yahin
                    if (scheme == "about" || scheme == "data" || scheme == "blob" ||
                        scheme == "javascript" || scheme == "file"
                    ) return false
                    // tel:, mailto:, intent:, upi: jaise links ko hi dusri app me kholo
                    val intent = Intent(Intent.ACTION_VIEW, target)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(intent) } catch (e: Exception) { }
                    return true
                }

                // offline / site na mile: sirf main page par chhota error page (loop na bane)
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    val req = request ?: return
                    if (!req.isForMainFrame) return
                    val failed = req.url.toString()
                    val jsUrl = failed
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "")
                        .replace("\r", "")
                        .replace("<", "%3C")
                    val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                        "<body style='background:#1e222d;color:#d1d4dc;font-family:sans-serif;text-align:center;padding:40px 16px'>" +
                        "<h3>Internet / site nahi mil rahi</h3>" +
                        "<p>Connection check karke dobara try karo.</p>" +
                        "<button style='background:#2962ff;color:#fff;border:0;padding:12px 22px;font-size:16px;border-radius:6px' " +
                        "onclick=\"location.replace('" + jsUrl + "')\">Dobara try karo</button>" +
                        "</body></html>"
                    view?.loadDataWithBaseURL(failed, html, "text/html", "UTF-8", null)
                }

                // Streamlit/chart OOM me renderer mare to window dobara banao
                @android.annotation.TargetApi(26)
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    handler.post { this@FloatingService.restartWindow(this@FloatWindow) }
                    return true
                }
            }

            webView.webChromeClient = object : WebChromeClient() {

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    History.touchTitle(ctx, view?.url, title)
                }

                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                ): Boolean {
                    // window.open / Google login popup: asli popup WebView (window.opener bana rahe)
                    return PopupLogin.open(ctx, true, resultMsg)
                }

                override fun onJsPrompt(
                    view: WebView?, pageUrl: String?, message: String?,
                    defaultValue: String?, result: JsPromptResult?
                ): Boolean {
                    if (result == null) return false
                    return try {
                        val themed = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        val input = EditText(themed)
                        input.setText(defaultValue ?: "")
                        val dialog = AlertDialog.Builder(themed)
                            .setTitle("Input chahiye")
                            .setMessage(message)
                            .setView(input)
                            .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                            .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                            .setOnCancelListener { result.cancel() }
                            .create()
                        dialog.window?.setType(overlayType())
                        dialog.show()
                        true
                    } catch (e: Exception) {
                        result.cancel()
                        false
                    }
                }

                override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                    if (customView != null || view == null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    val parent = swipeRefresh.parent as ViewGroup
                    val index = parent.indexOfChild(swipeRefresh)
                    view.setBackgroundColor(Color.BLACK)
                    parent.addView(view, index, swipeRefresh.layoutParams)
                    swipeRefresh.visibility = View.GONE
                    applyGesturePrefs()
                    if (!isMaximized) {
                        wasMaximizedByJS = true
                        toggleMaximize()
                    }
                }

                override fun onHideCustomView() {
                    val v = customView ?: return
                    (v.parent as? ViewGroup)?.removeView(v)
                    swipeRefresh.visibility = View.VISIBLE
                    if (wasMaximizedByJS) {
                        if (isMaximized) toggleMaximize()
                        wasMaximizedByJS = false
                    }
                    customView = null
                    applyGesturePrefs()
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }

                override fun onShowFileChooser(
                    webViewParam: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: WebChromeClient.FileChooserParams?
                ): Boolean {
                    if (callback == null || fileChooserParams == null) return false
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    return try {
                        val picker = fileChooserParams.createIntent()
                        val host = Intent(ctx, FileChooserActivity::class.java)
                        host.putExtra(Intent.EXTRA_INTENT, picker)
                        host.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(host)
                        true
                    } catch (e: Exception) {
                        filePathCallback = null
                        Toast.makeText(ctx, "File picker open nahi ho paya: " + e.message, Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }
        }
    }
}

const val BOOKMARK_MAX = 40

data class Bookmark(val title: String, val url: String)
