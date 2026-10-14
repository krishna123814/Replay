package com.example.simplebrowser

import android.app.AlertDialog
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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Message
import android.provider.Settings
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Floating browser: kisi bhi app ke upar tairti window.
 * - drag (✥), resize (◢ corner), maximize (⛶), minimize -> bubble (—)
 * - bubble ko neeche ❌ zone me kheench kar band karo
 * - ⚙ settings: URL, menu left/right, size, opacity, distance
 * - <input type=file> ke liye file picker, JS prompt, fullscreen video
 */
class FloatingService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.simplebrowser.STOP_FLOATING"
        const val CHANNEL_ID = "floating_browser"
        const val NOTIF_ID = 1101
        const val PREFS = "FloatingAppPrefs"

        // File picker (input type=file) ka result yahan hold hota hai
        var filePathCallback: ValueCallback<Array<Uri>>? = null
        var instance: FloatingService? = null
    }

    private var floatWindow: FloatWindow? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        createWindowIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        floatWindow?.destroy()
        floatWindow = null
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createWindowIfNeeded() {
        if (floatWindow != null) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString("custom_url", AppConfig.DEFAULT_URL) ?: AppConfig.DEFAULT_URL
        val w = FloatWindow(this, url)
        floatWindow = w
        w.show()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Floating Browser", NotificationManager.IMPORTANCE_LOW)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Floating Browser chal raha hai")
            .setContentText("Band karne ke liye \"Band karo\" dabao")
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

    // ═════════════════════════════════════════════════════════════════════
    inner class FloatWindow(private val ctx: Context, private var url: String) {

        private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val density = ctx.resources.displayMetrics.density

        private lateinit var root: FrameLayout
        private lateinit var layoutFull: FrameLayout
        private lateinit var content: LinearLayout
        private lateinit var leftPanel: LinearLayout
        private lateinit var webView: WebView
        private lateinit var layoutBubble: FrameLayout
        private lateinit var layoutSettings: ScrollView
        private lateinit var closeZone: FrameLayout
        private lateinit var params: WindowManager.LayoutParams
        private lateinit var closeParams: WindowManager.LayoutParams

        private lateinit var btnDrag: TextView
        private lateinit var btnBack: TextView
        private lateinit var btnRefresh: TextView
        private lateinit var btnMaximize: TextView
        private lateinit var btnMinimize: TextView
        private lateinit var btnSettings: TextView
        private lateinit var btnClose: TextView
        private lateinit var resizeCorner: TextView

        private lateinit var inputUrl: EditText
        private lateinit var sbSize: SeekBar
        private lateinit var sbOpacity: SeekBar
        private lateinit var sbDistance: SeekBar

        private val menuButtons = ArrayList<TextView>()

        private var isMaximized = false
        private var wasMaximizedBeforeMinimize = false
        private var isCloseZoneVisible = false

        private var customView: View? = null
        private var customViewCallback: WebChromeClient.CustomViewCallback? = null
        private var wasMaximizedByJS = false

        private var lastWindowX = 0
        private var lastWindowY = 0
        private var lastBubbleX = 0
        private var lastBubbleY = 0
        private var normalW = 0
        private var normalH = 0

        private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        @Suppress("DEPRECATION")
        private fun overlayType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        // ── entry ─────────────────────────────────────────────────────────
        fun show() {
            val m = ctx.resources.displayMetrics
            lastWindowX = dp(8)
            lastWindowY = dp(60)
            lastBubbleX = m.widthPixels - dp(70)
            lastBubbleY = m.heightPixels / 4

            buildUi()
            setupWindowParams()
            setupControls()
            setupWebView()
            wm.addView(root, params)
            loadSavedSettings()
            webView.loadUrl(url)
        }

        fun destroy() {
            try { CookieManager.getInstance().flush() } catch (e: Exception) { }
            if (isCloseZoneVisible) {
                try { wm.removeView(closeZone) } catch (e: Exception) { }
                isCloseZoneVisible = false
            }
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
            t.layoutParams = LinearLayout.LayoutParams(dp(36), dp(34))
            menuButtons.add(t)
            return t
        }

        private fun buildUi() {
            root = FrameLayout(ctx)

            layoutFull = FrameLayout(ctx)
            layoutFull.setBackgroundColor(Color.parseColor("#131722"))

            content = LinearLayout(ctx)
            content.orientation = LinearLayout.HORIZONTAL

            leftPanel = LinearLayout(ctx)
            leftPanel.orientation = LinearLayout.VERTICAL
            leftPanel.gravity = Gravity.CENTER_HORIZONTAL
            leftPanel.setBackgroundColor(Color.parseColor("#1e222d"))

            btnDrag = mkBtn("✥")
            btnBack = mkBtn("◀")
            btnRefresh = mkBtn("⟳")
            btnMaximize = mkBtn("⛶")
            btnMinimize = mkBtn("—")
            btnSettings = mkBtn("⚙")
            btnClose = mkBtn("✕")
            for (b in menuButtons) leftPanel.addView(b)

            webView = WebView(ctx)

            content.addView(leftPanel, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT))
            content.addView(webView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
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

            buildSettings()
            layoutFull.addView(
                layoutSettings,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            root.addView(
                layoutFull,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            // bubble
            layoutBubble = FrameLayout(ctx)
            layoutBubble.visibility = View.GONE
            layoutBubble.alpha = 0.6f
            val bubble = TextView(ctx)
            bubble.text = "🌐"
            bubble.textSize = 24f
            bubble.gravity = Gravity.CENTER
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.OVAL
            shape.setColor(Color.BLACK)
            bubble.background = shape
            layoutBubble.addView(bubble, FrameLayout.LayoutParams(dp(56), dp(56)))
            root.addView(
                layoutBubble,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            // close zone (alag overlay window, bubble drag ke waqt dikhta hai)
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
                var newUrl = inputUrl.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                        val ipPort = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")
                        newUrl = if (ipPort.matches(newUrl)) "http://$newUrl" else "https://$newUrl"
                    }
                    prefs.edit().putString("custom_url", newUrl).apply()
                    url = newUrl
                    webView.loadUrl(url)
                    layoutSettings.visibility = View.GONE
                }
            }
            col.addView(saveBtn, lp)

            val shiftBtn = blueButton("↔ Menu Left / Right")
            shiftBtn.setOnClickListener {
                val newShift = !prefs.getBoolean("menu_shift_right", false)
                prefs.edit().putBoolean("menu_shift_right", newShift).apply()
                applyMenuShift(newShift)
                Toast.makeText(ctx, "Menu position badli", Toast.LENGTH_SHORT).show()
            }
            col.addView(shiftBtn, lp)

            col.addView(label("Icon size"), lp)
            sbSize = SeekBar(ctx)
            sbSize.max = 10
            col.addView(sbSize, lp)

            col.addView(label("Bubble opacity"), lp)
            sbOpacity = SeekBar(ctx)
            sbOpacity.max = 100
            col.addView(sbOpacity, lp)

            col.addView(label("Menu ke upar/neeche ki doori"), lp)
            sbDistance = SeekBar(ctx)
            sbDistance.max = 60
            col.addView(sbDistance, lp)

            val closeBtn = blueButton("Settings band karo")
            closeBtn.setOnClickListener { layoutSettings.visibility = View.GONE }
            col.addView(closeBtn, lp)

            layoutSettings.addView(col)
        }

        // ── window params ─────────────────────────────────────────────────
        private fun setupWindowParams() {
            val m = ctx.resources.displayMetrics
            normalW = minOf(dp(360), m.widthPixels - dp(16))
            normalH = minOf(dp(500), m.heightPixels - dp(80))
            params = WindowManager.LayoutParams(
                normalW, normalH, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = lastWindowX
            params.y = lastWindowY
        }

        private fun updateLayout() {
            try { wm.updateViewLayout(root, params) } catch (e: Exception) { }
        }

        // full window me keyboard chahiye (focusable), bubble me nahi
        private fun updateWindowFocusable(isFull: Boolean) {
            params.flags = if (isFull) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            updateLayout()
        }

        private fun applyMenuShift(shiftRight: Boolean) {
            content.removeView(leftPanel)
            val lp = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT)
            if (shiftRight) content.addView(leftPanel, lp) else content.addView(leftPanel, 0, lp)
        }

        private fun applyTextSize(size: Float) {
            for (b in menuButtons) b.textSize = size
        }

        private fun loadSavedSettings() {
            val size = prefs.getInt("theme_size", 2)
            applyTextSize(12f + size)
            sbSize.progress = size

            val opacity = prefs.getInt("theme_opacity", 60)
            layoutBubble.alpha = maxOf(opacity, 10) / 100f
            sbOpacity.progress = opacity

            val dist = prefs.getInt("theme_distance", 0)
            leftPanel.setPadding(0, dp(dist), 0, dp(dist))
            sbDistance.progress = dist

            applyMenuShift(prefs.getBoolean("menu_shift_right", false))
            inputUrl.setText(prefs.getString("custom_url", AppConfig.DEFAULT_URL))
        }

        // ── controls ──────────────────────────────────────────────────────
        private fun setupControls() {
            btnRefresh.setOnClickListener { webView.reload() }
            btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
            btnClose.setOnClickListener { this@FloatingService.stopSelf() }
            btnMaximize.setOnClickListener { toggleMaximize() }
            btnMinimize.setOnClickListener { minimizeToBubble() }
            btnSettings.setOnClickListener { layoutSettings.visibility = View.VISIBLE }

            // window drag (✥)
            var dX = 0; var dY = 0; var dTX = 0f; var dTY = 0f
            btnDrag.setOnTouchListener { _, e ->
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
                        updateLayout()
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
                        updateLayout()
                        true
                    }
                    else -> false
                }
            }

            // bubble drag + ❌ zone + tap=restore
            var bX = 0; var bY = 0; var bTX = 0f; var bTY = 0f; var bDragging = false
            layoutBubble.setOnTouchListener { _, e ->
                val m = ctx.resources.displayMetrics
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        bX = params.x; bY = params.y; bTX = e.rawX; bTY = e.rawY; bDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.rawX - bTX) > 10 || abs(e.rawY - bTY) > 10) {
                            if (!bDragging) {
                                bDragging = true
                                showCloseZone()
                            }
                            params.x = bX + (e.rawX - bTX).toInt()
                            params.y = bY + (e.rawY - bTY).toInt()
                            lastBubbleX = params.x
                            lastBubbleY = params.y
                            updateLayout()
                            val over = isOverCloseZone(e.rawX, e.rawY, m.widthPixels, m.heightPixels)
                            closeZone.scaleX = if (over) 1.3f else 1.0f
                            closeZone.scaleY = if (over) 1.3f else 1.0f
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (bDragging) {
                            hideCloseZone()
                            if (isOverCloseZone(e.rawX, e.rawY, m.widthPixels, m.heightPixels)) {
                                this@FloatingService.stopSelf()
                            }
                        } else {
                            restoreFromBubble()
                        }
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
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putInt("theme_opacity", progress).apply()
                    layoutBubble.alpha = maxOf(progress, 10) / 100f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            sbDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putInt("theme_distance", progress).apply()
                    leftPanel.setPadding(0, dp(progress), 0, dp(progress))
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

        // ── maximize / minimize ───────────────────────────────────────────
        private fun toggleMaximize() {
            val m = ctx.resources.displayMetrics
            if (!isMaximized) {
                lastWindowX = params.x
                lastWindowY = params.y
                normalW = params.width
                normalH = params.height
                params.x = 0
                params.y = 0
                params.width = m.widthPixels
                params.height = m.heightPixels
                isMaximized = true
            } else {
                params.x = lastWindowX
                params.y = lastWindowY
                params.width = normalW
                params.height = normalH
                isMaximized = false
            }
            updateWindowFocusable(true)
        }

        private fun minimizeToBubble() {
            wasMaximizedBeforeMinimize = isMaximized
            if (!isMaximized) {
                lastWindowX = params.x
                lastWindowY = params.y
                normalW = params.width
                normalH = params.height
            }
            isMaximized = false
            layoutSettings.visibility = View.GONE
            layoutFull.visibility = View.GONE
            layoutBubble.visibility = View.VISIBLE
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = lastBubbleX
            params.y = lastBubbleY
            updateWindowFocusable(false)
        }

        private fun restoreFromBubble() {
            layoutBubble.visibility = View.GONE
            layoutFull.visibility = View.VISIBLE
            lastBubbleX = params.x
            lastBubbleY = params.y
            if (wasMaximizedBeforeMinimize) {
                val m = ctx.resources.displayMetrics
                params.x = 0
                params.y = 0
                params.width = m.widthPixels
                params.height = m.heightPixels
                isMaximized = true
            } else {
                params.x = lastWindowX
                params.y = lastWindowY
                params.width = normalW
                params.height = normalH
            }
            updateWindowFocusable(true)
        }

        // ── WebView ───────────────────────────────────────────────────────
        @Suppress("SetJavaScriptEnabled")
        private fun setupWebView() {
            val s = webView.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.allowContentAccess = true
            s.setSupportMultipleWindows(true)
            s.javaScriptCanOpenWindowsAutomatically = true
            s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Streamlit image/canvas OOM fix: hardware layer nahi
            webView.setLayerType(View.LAYER_TYPE_NONE, null)

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, true)

            webView.isFocusable = true
            webView.isFocusableInTouchMode = true

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    CookieManager.getInstance().flush()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return false
                    val scheme = target.scheme ?: return false
                    val full = target.toString()
                    val homeHost = Uri.parse(url).host
                    val isAuth = full.contains("fyers.in") || full.contains("oauth2") || full.contains("google.com")
                    val isExternalHost = (scheme == "http" || scheme == "https") && target.host != homeHost
                    val isOtherScheme = scheme != "http" && scheme != "https"
                    if (isAuth || isExternalHost || isOtherScheme) {
                        // login/popup/external link: system browser me kholo
                        val intent = Intent(Intent.ACTION_VIEW, target)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctx.startActivity(intent) } catch (e: Exception) { }
                        return true
                    }
                    return false
                }
            }

            webView.webChromeClient = object : WebChromeClient() {

                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                ): Boolean {
                    val data = view?.hitTestResult?.extra
                    if (data != null) {
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(data))
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctx.startActivity(i) } catch (e: Exception) { }
                    }
                    return false
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
                    val parent = webView.parent as ViewGroup
                    val index = parent.indexOfChild(webView)
                    view.setBackgroundColor(Color.BLACK)
                    parent.addView(view, index, webView.layoutParams)
                    webView.visibility = View.GONE
                    if (!isMaximized) {
                        wasMaximizedByJS = true
                        toggleMaximize()
                    }
                }

                override fun onHideCustomView() {
                    val v = customView ?: return
                    (v.parent as? ViewGroup)?.removeView(v)
                    webView.visibility = View.VISIBLE
                    if (wasMaximizedByJS) {
                        if (isMaximized) toggleMaximize()
                        wasMaximizedByJS = false
                    }
                    customView = null
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
