package com.example.simplebrowser.files

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Video + music player. Video: brightness / volume / seek gestures, double-tap seek, rotate, lock, speed,
 * aspect ratio, audio + subtitle tracks, PiP. Music: shuffle / repeat, album art, sleep timer, queue.
 * Action row (top bar ke neeche): prev / -10s / +10s / next, repeat, A-B loop, favourite, rename, info,
 * screenshot, mute, night dim, delete.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var audioIcon: TextView
    private lateinit var hud: TextView
    private lateinit var lockBtn: TextView
    private lateinit var dimView: View
    private lateinit var repeatBtn: TextView
    private lateinit var abBtn: TextView
    private lateinit var favBtn: TextView
    private lateinit var shotBtn: TextView
    private lateinit var muteBtn: TextView
    private lateinit var dimBtn: TextView
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var isVideo = true
    private val handler = Handler(Looper.getMainLooper())
    private val artPool: ExecutorService = Executors.newSingleThreadExecutor()
    private var artGen = 0

    private var locked = false
    private var orient = 0 // 0 auto, 1 landscape, 2 portrait
    private var resizeIdx = 0
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val resizeNames = arrayOf("Fit (poori video)", "Zoom (crop)", "Stretch (bhar do)")
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    // gesture state
    private val am by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var gMode = 0 // 0 none, 1 brightness, 2 volume, 3 seek
    private var bright = 0.5f
    private var volAcc = 0f
    private var seekStart = 0L
    private var seekTarget = 0L

    private val ticker = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5000L)
        }
    }

    // A-B repeat / mute / night dim
    private var abA = -1L
    private var abB = -1L
    private var muted = false
    private var dimIdx = 0
    private val dimAlphas = floatArrayOf(0f, 0.35f, 0.6f, 0.8f)

    private val abTick = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && abA >= 0 && abB > abA) {
                if (c.currentPosition >= abB) c.seekTo(abA)
                handler.postDelayed(this, 200L)
            }
        }
    }

    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideLock = Runnable { if (locked) lockBtn.visibility = View.GONE }

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            titleView.text = mediaMetadata.title ?: ""
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isVideo) loadArt(mediaItem)
            // agli / pichli file par jaane par A-B loop band (rename / delete ka apna handling hai)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) clearAb(false)
            refreshFav()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncRepeat()
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity, "Play nahi hua: ${error.errorCodeName}", Toast.LENGTH_LONG
            ).show()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // file poori chal gayi -> agli baar shuru se
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                val id = oldPosition.mediaItem?.mediaId
                if (id != null) Session.setPos(this@PlayerActivity, id, 0L)
            }
        }
    }

    // ───────────────────────── UI ─────────────────────────

    private fun iconBtn(t: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = t
        b.textSize = 20f
        b.setTextColor(Color.WHITE)
        b.gravity = Gravity.CENTER
        b.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isVideo = intent.getBooleanExtra("video", true)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        root.addView(playerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // night dim overlay (video ke upar, controls ke neeche; touch nahi rokta)
        dimView = View(this)
        dimView.setBackgroundColor(Color.BLACK)
        dimView.alpha = 0f
        root.addView(dimView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        audioIcon = TextView(this)
        audioIcon.text = "🎵"
        audioIcon.textSize = 96f
        audioIcon.alpha = 0.35f
        audioIcon.gravity = Gravity.CENTER
        root.addView(audioIcon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // top bar: (← title 🔒 ⟳ ⋮) + neeche action row
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.VERTICAL
        topBar.setBackgroundColor(0x88000000.toInt())
        topBar.setPadding(dp(6), dp(8), dp(6), dp(4))
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL
        titleView = TextView(this)
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 15f
        titleView.maxLines = 2
        titleView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        topRow.addView(iconBtn("←") { finish() })
        topRow.addView(titleView)
        topRow.addView(iconBtn("🔒") { setLocked(true) })
        topRow.addView(iconBtn("⟳") { cycleOrientation() })
        topRow.addView(iconBtn("⋮") { showMenu() })
        topBar.addView(topRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        topBar.addView(buildActionRow(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        // brightness / volume / seek ka overlay text
        hud = TextView(this)
        hud.setTextColor(Color.WHITE)
        hud.textSize = 20f
        hud.gravity = Gravity.CENTER
        hud.setPadding(dp(20), dp(12), dp(20), dp(12))
        val hbg = GradientDrawable()
        hbg.setColor(0xAA000000.toInt())
        hbg.cornerRadius = dp(12).toFloat()
        hud.background = hbg
        hud.visibility = View.GONE
        root.addView(hud, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        // lock hone par unlock button
        lockBtn = TextView(this)
        lockBtn.text = "🔓"
        lockBtn.textSize = 26f
        lockBtn.gravity = Gravity.CENTER
        val lbg = GradientDrawable()
        lbg.setColor(0xAA000000.toInt())
        lbg.shape = GradientDrawable.OVAL
        lockBtn.background = lbg
        lockBtn.visibility = View.GONE
        lockBtn.setOnClickListener { setLocked(false) }
        val llp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.START)
        llp.leftMargin = dp(24)
        root.addView(lockBtn, llp)

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v -> topBar.visibility = if (locked) View.GONE else v }
        )

        setContentView(root)
        applyMode()
    }

    private fun applyMode() {
        audioIcon.visibility = if (isVideo) View.GONE else View.VISIBLE
        shotBtn.visibility = if (isVideo) View.VISIBLE else View.GONE
        dimBtn.visibility = if (isVideo) View.VISIBLE else View.GONE
        if (isVideo) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            playerView.controllerShowTimeoutMs = 3000
            playerView.controllerHideOnTouch = true
            playerView.setShowShuffleButton(false)
            playerView.setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE)
            installGestures()
        } else {
            playerView.controllerShowTimeoutMs = 0
            playerView.controllerHideOnTouch = false
            playerView.setShowShuffleButton(true)
            playerView.setRepeatToggleModes(
                RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
            )
            playerView.setOnTouchListener(null)
            playerView.showController()
        }
    }

    // ───────────────────────── gestures (video) ─────────────────────────

    private fun installGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    lockBtn.visibility = View.VISIBLE
                    handler.removeCallbacks(hideLock)
                    handler.postDelayed(hideLock, 2000)
                } else if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val c = controller ?: return true
                val third = playerView.width / 3f
                when {
                    e.x < third -> seekBy(c, -10_000L)
                    e.x > third * 2 -> seekBy(c, 10_000L)
                    else -> if (c.isPlaying) c.pause() else c.play()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
                if (e1 == null || locked) return false
                val c = controller ?: return false
                if (gMode == 0) {
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) < dp(12) && abs(dy) < dp(12)) return true
                    gMode = if (abs(dx) > abs(dy)) 3 else if (e1.x < playerView.width / 2f) 1 else 2
                    when (gMode) {
                        1 -> bright = currentBrightness()
                        2 -> volAcc = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        else -> {
                            seekStart = c.currentPosition
                            seekTarget = seekStart
                        }
                    }
                }
                val h = playerView.height.coerceAtLeast(1)
                when (gMode) {
                    1 -> {
                        bright = (bright + dY / h * 1.3f).coerceIn(0.01f, 1f)
                        val lp = window.attributes
                        lp.screenBrightness = bright
                        window.attributes = lp
                        showHud("☀ " + (bright * 100).roundToInt() + "%")
                    }
                    2 -> {
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        volAcc = (volAcc + dY / h * max * 1.3f).coerceIn(0f, max.toFloat())
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, volAcc.roundToInt(), 0)
                        showHud("🔊 " + (volAcc / max * 100).roundToInt() + "%")
                    }
                    3 -> {
                        val dur = c.duration
                        val raw = seekStart + ((e2.x - e1.x) / playerView.width.coerceAtLeast(1) * 90_000f).toLong()
                        seekTarget = raw.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                        val d = (seekTarget - seekStart) / 1000
                        showHud(
                            (if (d >= 0) "⏩ +" else "⏪ ") + d + "s\n" + fmtTime(seekTarget) +
                                (if (dur > 0) " / " + fmtTime(dur) else "")
                        )
                    }
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (gMode == 3) controller?.seekTo(seekTarget)
                gMode = 0
            }
            true
        }
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun showHud(t: String) {
        hud.text = t
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 800)
    }

    private fun seekBy(c: MediaController, ms: Long) {
        val dur = c.duration
        val t = (c.currentPosition + ms).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        c.seekTo(t)
        showHud((if (ms < 0) "⏪ " else "⏩ ") + abs(ms / 1000) + "s")
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    // ───────────────────────── lock / rotate / PiP ─────────────────────────

    private fun setLocked(on: Boolean) {
        locked = on
        handler.removeCallbacks(hideLock)
        if (on) {
            playerView.useController = false
            playerView.hideController()
            topBar.visibility = View.GONE
            lockBtn.visibility = View.VISIBLE
            handler.postDelayed(hideLock, 2000)
            Toast.makeText(this, "Screen lock: unlock ke liye screen dabao, phir 🔓", Toast.LENGTH_SHORT).show()
        } else {
            lockBtn.visibility = View.GONE
            playerView.useController = true
            playerView.showController()
        }
    }

    private fun cycleOrientation() {
        orient = (orient + 1) % 3
        requestedOrientation = when (orient) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        Toast.makeText(
            this,
            arrayOf("Rotate: Auto", "Rotate: Landscape", "Rotate: Portrait")[orient],
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || !isVideo) {
            Toast.makeText(this, "PiP is phone / file par nahi chalega", Toast.LENGTH_SHORT).show()
            return
        }
        val vs = controller?.videoSize
        var w = vs?.width ?: 16
        var h = vs?.height ?: 9
        if (w <= 0 || h <= 0) {
            w = 16
            h = 9
        }
        val ratio = w.toFloat() / h
        val r = when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 0.42f -> Rational(42, 100)
            else -> Rational(w, h)
        }
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(r).build())
        } catch (e: Exception) {
            Toast.makeText(this, "PiP nahi khul paya", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // home dabane par video chalti ho to chhoti window me chalne do
        if (isVideo && !locked && controller?.isPlaying == true && Build.VERSION.SDK_INT >= 26) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerView.useController = false
            topBar.visibility = View.GONE
            hud.visibility = View.GONE
        } else if (!locked) {
            playerView.useController = true
        }
    }

    // ───────────────────────── action row (delete / rename / info / ...) ─────────────────────────

    private fun actBtn(t: String, sizeSp: Float = 18f, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = t
        b.textSize = sizeSp
        b.setTextColor(Color.WHITE)
        b.gravity = Gravity.CENTER
        b.minimumWidth = dp(44)
        b.setPadding(dp(8), 0, dp(8), 0)
        b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        b.setOnClickListener { onClick() }
        return b
    }

    /** Button "on" ho to halka gola background. */
    private fun mark(b: TextView, on: Boolean) {
        if (on) {
            val g = GradientDrawable()
            g.setColor(0x55FFFFFF)
            g.cornerRadius = dp(10).toFloat()
            b.background = g
        } else {
            b.background = null
        }
    }

    private fun buildActionRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(actBtn("⏮") { prevItem() })
        row.addView(actBtn("⏪10", 14f) { controller?.let { seekBy(it, -10_000L) } })
        row.addView(actBtn("10⏩", 14f) { controller?.let { seekBy(it, 10_000L) } })
        row.addView(actBtn("⏭") { nextItem() })
        repeatBtn = actBtn("🔁") { cycleRepeat() }
        row.addView(repeatBtn)
        abBtn = actBtn("A-B", 14f) { cycleAb() }
        row.addView(abBtn)
        favBtn = actBtn("☆") { toggleFav() }
        row.addView(favBtn)
        row.addView(actBtn("✏️") { renameCurrent() })
        row.addView(actBtn("ℹ️") { showInfo() })
        shotBtn = actBtn("📸") { screenshot() }
        row.addView(shotBtn)
        muteBtn = actBtn("🔊") { toggleMute() }
        row.addView(muteBtn)
        dimBtn = actBtn("🌙") { cycleDim() }
        row.addView(dimBtn)
        row.addView(actBtn("🗑") { confirmDeleteCurrent() })
        syncRepeat()
        syncAb()

        val sv = HorizontalScrollView(this)
        sv.isHorizontalScrollBarEnabled = false
        sv.addView(row)
        // row par haath rakhte hi controls 3 sec ke liye wapas taaza
        sv.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && isVideo && !locked) playerView.showController()
            false
        }
        return sv
    }

    private fun storeOf(url: String): Store = Storages.storeOfUrl(url)

    private fun entryFor(url: String, name: String): Entry =
        Queue.media.firstOrNull { it.url == url } ?: Entry(name, url, false, 0L, 0L)

    private fun indexOfUrl(c: MediaController, url: String): Int {
        for (i in 0 until c.mediaItemCount) if (c.getMediaItemAt(i).mediaId == url) return i
        return -1
    }

    // ── prev / next
    private fun prevItem() {
        val c = controller ?: return
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else showHud("Pichla koi nahi")
    }

    private fun nextItem() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else showHud("Aage koi nahi")
    }

    // ── repeat
    private fun cycleRepeat() {
        val c = controller ?: return
        val m = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = m
        showHud(
            when (m) {
                Player.REPEAT_MODE_ONE -> "🔂 Ye file repeat"
                Player.REPEAT_MODE_ALL -> "🔁 Poori queue repeat"
                else -> "Repeat band"
            }
        )
    }

    private fun syncRepeat() {
        val m = controller?.repeatMode ?: Player.REPEAT_MODE_OFF
        repeatBtn.text = if (m == Player.REPEAT_MODE_ONE) "🔂" else "🔁"
        mark(repeatBtn, m != Player.REPEAT_MODE_OFF)
    }

    // ── A-B repeat: 1st tap = A, 2nd tap = B (loop shuru), 3rd tap = band
    private fun cycleAb() {
        val c = controller ?: return
        when {
            abA < 0 -> {
                abA = c.currentPosition
                showHud("🅰 Start: ${fmtTime(abA)}\nAb end point lagao")
            }
            abB < 0 -> {
                val p = c.currentPosition
                if (p <= abA + 500L) {
                    showHud("End point start se aage hona chahiye")
                    return
                }
                abB = p
                handler.removeCallbacks(abTick)
                handler.post(abTick)
                showHud("🔁 A-B loop\n${fmtTime(abA)} → ${fmtTime(abB)}")
            }
            else -> {
                clearAb(true)
                return
            }
        }
        syncAb()
    }

    private fun clearAb(msg: Boolean) {
        val was = abA >= 0
        abA = -1L
        abB = -1L
        handler.removeCallbacks(abTick)
        if (msg && was) showHud("A-B loop band")
        syncAb()
    }

    private fun syncAb() {
        abBtn.text = when {
            abA < 0 -> "A-B"
            abB < 0 -> "A-?"
            else -> "A⇄B"
        }
        mark(abBtn, abA >= 0)
    }

    // ── favourite
    private fun toggleFav() {
        val item = controller?.currentMediaItem ?: return
        val url = item.mediaId
        val name = item.mediaMetadata.title?.toString() ?: url.substringAfterLast('/')
        try {
            val s = storeOf(url)
            val on = Favs.toggle(this, s, Storages.pathOf(s, url), name, false)
            showHud(if (on) "⭐ Favourite me joda" else "☆ Favourite se hata diya")
        } catch (e: Exception) {
            Toast.makeText(this, "Favourite nahi hua: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        refreshFav()
    }

    private fun refreshFav() {
        if (!::favBtn.isInitialized) return
        val url = controller?.currentMediaItem?.mediaId
        val on = try {
            if (url == null) false else {
                val s = storeOf(url)
                Favs.has(this, s, Storages.pathOf(s, url))
            }
        } catch (e: Exception) {
            false
        }
        favBtn.text = if (on) "⭐" else "☆"
        mark(favBtn, on)
    }

    // ── mute / night dim
    private fun toggleMute() {
        val c = controller ?: return
        muted = !muted
        c.volume = if (muted) 0f else 1f
        syncMute()
        showHud(if (muted) "🔇 Mute" else "🔊 Awaaz on")
    }

    private fun syncMute() {
        muteBtn.text = if (muted) "🔇" else "🔊"
        mark(muteBtn, muted)
    }

    private fun cycleDim() {
        dimIdx = (dimIdx + 1) % dimAlphas.size
        dimView.alpha = dimAlphas[dimIdx]
        mark(dimBtn, dimIdx != 0)
        showHud(if (dimIdx == 0) "🌙 Night dim band" else "🌙 Night dim ${(dimAlphas[dimIdx] * 100).roundToInt()}%")
    }

    // ── info
    private fun showInfo() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val url = item.mediaId
        val name = item.mediaMetadata.title?.toString() ?: url.substringAfterLast('/')
        val e = entryFor(url, name)
        val local = LocalFs.isLocal(url)
        var size = e.size
        if (size <= 0L && local) size = localInfo(url, true)
        val where = try {
            val s = storeOf(url)
            s.short + ": " + Storages.pathOf(s, url)
        } catch (x: Exception) {
            url
        }
        val sb = StringBuilder()
        sb.append("Naam: ").append(name).append('\n')
        if (size > 0) sb.append("Size: ").append(fmtSize(size)).append('\n')
        if (c.duration > 0) sb.append("Length: ").append(fmtTime(c.duration)).append('\n')
        val vs = c.videoSize
        if (isVideo && vs.width > 0 && vs.height > 0) sb.append("Resolution: ${vs.width}×${vs.height}\n")
        var mod = e.modified
        if (mod <= 0L && local) mod = localInfo(url, false)
        if (mod > 0) sb.append("Badla: ").append(fmtDate(mod)).append('\n')
        sb.append("\n").append(where)
        AlertDialog.Builder(this).setTitle("Info").setMessage(sb.toString()).setPositiveButton("OK", null).show()
    }

    private fun localInfo(url: String, size: Boolean): Long = try {
        val f = LocalFs.fileOf(url)
        if (size) f.length() else f.lastModified()
    } catch (x: Exception) {
        0L
    }

    // ── rename
    private fun renameCurrent() {
        val item = controller?.currentMediaItem ?: return
        val url = item.mediaId
        val oldName = item.mediaMetadata.title?.toString() ?: url.substringAfterLast('/')
        val et = EditText(this)
        et.setSingleLine()
        et.setText(oldName)
        val dot = oldName.lastIndexOf('.')
        et.setSelection(0, if (dot > 0) dot else oldName.length)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = et.text.toString().trim()
                when {
                    n.isEmpty() || n == oldName -> {}
                    n.contains('/') -> Toast.makeText(this, "Naam me / nahi chalega", Toast.LENGTH_SHORT).show()
                    else -> doRename(url, oldName, n)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doRename(url: String, oldName: String, newName: String) {
        val e = entryFor(url, oldName)
        val dirUrl = url.substringBeforeLast('/')
        val newUrl = if (LocalFs.isLocal(url)) {
            LocalFs.urlOf(File(LocalFs.fileOf(url).parentFile, newName), false)
        } else {
            Dav.withSlash(dirUrl) + Uri.encode(newName)
        }
        Thread {
            val err: String? = try {
                Fs.rename(e, dirUrl, newName)
                null
            } catch (ex: Exception) {
                ex.message ?: "error"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (err != null) {
                    Toast.makeText(this, "❌ $err", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                applyRenamed(url, newUrl, newName, e)
            }
        }.start()
    }

    private fun applyRenamed(url: String, newUrl: String, newName: String, e: Entry) {
        // favourite ka path badla: purana hata kar naya jodo
        try {
            val s = storeOf(url)
            val oldPath = Storages.pathOf(s, url)
            if (Favs.has(this, s, oldPath)) {
                Favs.remove(this, s, oldPath)
                Favs.add(this, storeOf(newUrl), Storages.pathOf(storeOf(newUrl), newUrl), newName, false)
            }
        } catch (x: Exception) {
        }
        // memory queue / subtitle map
        Queue.media = Queue.media.map { if (it.url == url) Entry(newName, newUrl, false, it.size, it.modified) else it }
        val subs = Queue.subs.toMutableMap()
        subs.remove(url)?.let { subs[newUrl] = it }
        Queue.subs = subs
        Queue.dirty = true

        val c = controller
        if (c != null) {
            val i = indexOfUrl(c, url)
            if (i >= 0) {
                val old = c.getMediaItemAt(i)
                val nu = old.buildUpon()
                    .setUri(newUrl)
                    .setMediaId(newUrl)
                    .setMediaMetadata(old.mediaMetadata.buildUpon().setTitle(newName).build())
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(newUrl)).build())
                    .build()
                val isCur = i == c.currentMediaItemIndex
                val pos = c.currentPosition
                val playing = c.playWhenReady
                c.replaceMediaItem(i, nu)
                if (isCur) {
                    c.seekTo(i, pos)
                    c.playWhenReady = playing
                    Session.setPos(this, newUrl, pos)
                }
                Session.setPos(this, url, 0L)
            }
        }
        titleView.text = newName
        refreshFav()
        Toast.makeText(this, "Rename ho gaya", Toast.LENGTH_SHORT).show()
    }

    // ── delete
    private fun confirmDeleteCurrent() {
        val item = controller?.currentMediaItem ?: return
        val url = item.mediaId
        val name = item.mediaMetadata.title?.toString() ?: url.substringAfterLast('/')
        val warn = when (storeOf(url)) {
            Store.LOCAL -> "\n\n⚠ Phone se PERMANENTLY delete hoga (recycle bin nahi hai)."
            Store.HF -> "\n\n⚠ HF Bucket se PERMANENTLY delete hoga. Trash nahi hai, wapas nahi aayega."
            Store.HF2 -> "\n\n⚠ HF2 Bucket se PERMANENTLY delete hoga. Trash nahi hai, wapas nahi aayega."
            else -> "\n\nWapas nahi aayegi."
        }
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage(name + warn)
            .setPositiveButton("Delete") { _, _ -> doDelete(url) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete(url: String) {
        Thread {
            val err: String? = try {
                Fs.delete(url)
                null
            } catch (ex: Exception) {
                ex.message ?: "error"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (err != null) {
                    Toast.makeText(this, "❌ $err", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                applyDeleted(url)
            }
        }.start()
    }

    private fun applyDeleted(url: String) {
        try {
            val s = storeOf(url)
            Favs.remove(this, s, Storages.pathOf(s, url))
        } catch (x: Exception) {
        }
        Session.setPos(this, url, 0L)
        Queue.media = Queue.media.filter { it.url != url }
        Queue.dirty = true
        Toast.makeText(this, "Delete ho gayi", Toast.LENGTH_SHORT).show()

        val c = controller
        if (c == null) {
            finish()
            return
        }
        val i = indexOfUrl(c, url)
        if (i < 0) return
        val wasCurrent = i == c.currentMediaItemIndex
        if (c.mediaItemCount <= 1) {
            c.stop()
            c.clearMediaItems()
            finish()
            return
        }
        if (wasCurrent) clearAb(false)
        c.removeMediaItem(i)
        if (wasCurrent) {
            // agla item i par aa jata hai; delete hua item aakhri tha to ab ka aakhri item chalao
            val n = c.mediaItemCount
            c.seekTo(if (i < n) i else n - 1, 0L)
            c.play()
        }
        refreshFav()
    }

    // ── screenshot (jo frame abhi dikh raha hai)
    private fun screenshot() {
        if (!isVideo) return
        val v = playerView.videoSurfaceView
        if (v is SurfaceView && v.width > 0 && v.height > 0) {
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(v, bmp, { r ->
                    if (r == PixelCopy.SUCCESS) saveShot(bmp) else shotFallback()
                }, handler)
                return
            } catch (e: Throwable) {
            }
        } else if (v is TextureView) {
            val b = v.bitmap
            if (b != null) {
                saveShot(b)
                return
            }
        }
        shotFallback()
    }

    /** PixelCopy na chale to MediaMetadataRetriever se isi position ka frame. */
    private fun shotFallback() {
        val c = controller ?: return
        val url = c.currentMediaItem?.mediaId ?: return
        val posUs = c.currentPosition * 1000L
        artPool.execute {
            var bmp: Bitmap? = null
            val mmr = MediaMetadataRetriever()
            try {
                if (LocalFs.isLocal(url)) mmr.setDataSource(LocalFs.fileOf(url).path)
                else mmr.setDataSource(url, hashMapOf("Authorization" to Session.auth()))
                bmp = mmr.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Throwable) {
            } finally {
                try {
                    mmr.release()
                } catch (e: Throwable) {
                }
            }
            val got = bmp
            if (got != null) saveShot(got)
            else handler.post { Toast.makeText(this, "Screenshot nahi ho paya", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun saveShot(bmp: Bitmap) {
        artPool.execute {
            val name = "Lion_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            var msg = ""
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues()
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lion")
                    cv.put(MediaStore.Images.Media.IS_PENDING, 1)
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        ?: throw java.io.IOException("MediaStore insert fail")
                    contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    cv.clear()
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, cv, null, null)
                    msg = "📸 Gallery me save: Pictures/Lion"
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                    dir.mkdirs()
                    val f = File(dir, name)
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    msg = "📸 Save: ${f.absolutePath}"
                }
            } catch (e: Throwable) {
                msg = "Screenshot save nahi hua: ${e.message}"
            }
            handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // ───────────────────────── menu ─────────────────────────

    private fun showMenu() {
        val c = controller ?: return
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("⏩ Speed: ${c.playbackParameters.speed}x" to { pickSpeed() })
        if (isVideo) {
            acts.add("🔲 Screen: ${resizeNames[resizeIdx]}" to { cycleResize() })
            acts.add("🔊 Audio track" to { pickTrack(C.TRACK_TYPE_AUDIO, "Audio track", false) })
            acts.add("💬 Subtitles" to { pickTrack(C.TRACK_TYPE_TEXT, "Subtitles", true) })
            if (Build.VERSION.SDK_INT >= 26) acts.add("⧉ Chhoti window (PiP)" to { enterPip() })
        }
        val left = SleepTimer.remainingMin()
        acts.add("😴 Sleep timer" + (if (left > 0) " ($left min bachi)" else "") to { pickSleep() })
        if (c.mediaItemCount > 1) acts.add("📃 Queue (${c.mediaItemCount})" to { showQueue() })
        acts.add("↗ Dusre app me kholo" to { openExternal() })
        AlertDialog.Builder(this)
            .setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }
            .show()
    }

    private fun pickSpeed() {
        val c = controller ?: return
        val cur = c.playbackParameters.speed
        val labels = speeds.map { it.toString() + "x" }.toTypedArray()
        val idx = speeds.indexOfFirst { abs(it - cur) < 0.01f }
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, idx) { d, w ->
                c.setPlaybackSpeed(speeds[w])
                d.dismiss()
            }
            .show()
    }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIdx]
        showHud(resizeNames[resizeIdx])
    }

    private fun trackName(f: Format, n: Int): String {
        val base = f.label
            ?: f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage }
            ?: "Track $n"
        return if (f.channelCount > 0 && f.sampleMimeType?.startsWith("audio") == true) "$base · ${f.channelCount}ch" else base
    }

    private fun pickTrack(type: Int, title: String, allowOff: Boolean) {
        val c = controller ?: return
        val groups = c.currentTracks.groups.filter { it.type == type && it.length > 0 }
        if (groups.isEmpty()) {
            Toast.makeText(this, "Koi $title nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        var checked = -1
        if (allowOff) {
            labels.add("Off")
            actions.add {
                c.trackSelectionParameters =
                    c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
            }
        }
        var n = 0
        for (g in groups) {
            for (i in 0 until g.length) {
                n++
                labels.add(trackName(g.getTrackFormat(i), n))
                if (g.isTrackSelected(i)) checked = labels.size - 1
                val ov = TrackSelectionOverride(g.mediaTrackGroup, i)
                actions.add {
                    c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(ov)
                        .build()
                }
            }
        }
        if (allowOff && checked < 0) checked = 0
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, w ->
                actions[w]()
                d.dismiss()
            }
            .show()
    }

    private fun pickSleep() {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = arrayOf("Off", "15 min", "30 min", "45 min", "1 ghanta", "1.5 ghante")
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(labels) { _, w ->
                SleepTimer.set(mins[w])
                Toast.makeText(
                    this,
                    if (mins[w] == 0) "Sleep timer band" else "${labels[w]} baad pause ho jayega",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun showQueue() {
        val c = controller ?: return
        val names = Array(c.mediaItemCount) { i ->
            (if (i == c.currentMediaItemIndex) "▶ " else "") +
                (c.getMediaItemAt(i).mediaMetadata.title?.toString() ?: "Item ${i + 1}")
        }
        AlertDialog.Builder(this)
            .setTitle("Queue")
            .setItems(names) { _, i ->
                c.seekTo(i, 0L)
                c.play()
            }
            .show()
    }

    private fun openExternal() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val name = item.mediaMetadata.title?.toString() ?: "file"
        val e = Entry(name, item.mediaId, false, 0L, 0L)
        c.pause()
        Opener.external(this, e)
    }

    // ───────────────────────── album art (music) ─────────────────────────

    private fun loadArt(item: MediaItem?) {
        val id = item?.mediaId
        val gen = ++artGen
        playerView.defaultArtwork = null
        if (id == null) return
        artPool.execute {
            var bmp: Bitmap? = null
            val mmr = MediaMetadataRetriever()
            try {
                if (LocalFs.isLocal(id)) {
                    mmr.setDataSource(LocalFs.fileOf(id).path)
                } else {
                    mmr.setDataSource(id, hashMapOf("Authorization" to Session.auth()))
                }
                val pic = mmr.embeddedPicture
                if (pic != null) {
                    val o = BitmapFactory.Options()
                    o.inSampleSize = 2
                    bmp = BitmapFactory.decodeByteArray(pic, 0, pic.size, o)
                }
            } catch (e: Throwable) {
            } finally {
                try {
                    mmr.release()
                } catch (e: Throwable) {
                }
            }
            val got = bmp
            if (got != null) {
                handler.post {
                    if (gen == artGen && !isFinishing) {
                        playerView.defaultArtwork = BitmapDrawable(resources, got)
                        audioIcon.visibility = View.GONE
                    }
                }
            } else {
                handler.post { if (gen == artGen && !isVideo) audioIcon.visibility = View.VISIBLE }
            }
        }
    }

    // ───────────────────────── lifecycle / queue ─────────────────────────

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = try {
                f.get()
            } catch (e: Exception) {
                Toast.makeText(this, "Player connect nahi hua", Toast.LENGTH_LONG).show()
                return@addListener
            }
            controller = c
            playerView.player = c
            c.addListener(listener)
            applyPending(c)
            titleView.text = c.mediaMetadata.title ?: ""
            if (!isVideo) loadArt(c.currentMediaItem)
            muted = c.volume == 0f
            syncMute()
            syncRepeat()
            syncAb()
            refreshFav()
            handler.postDelayed(ticker, 5000L)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyPending(c: MediaController) {
        if (!Queue.pending) {
            // notification se aaye ho: jo chal raha hai wahi dikhao
            val name = c.mediaMetadata.title?.toString() ?: ""
            if (name.isNotEmpty()) {
                isVideo = Kinds.of(name, false) == Kind.VIDEO
                applyMode()
            }
            return
        }
        Queue.pending = false
        val list = Queue.media
        if (list.isEmpty()) return
        val idx = Queue.mediaIndex.coerceIn(0, list.size - 1)
        isVideo = list[idx].kind == Kind.VIDEO
        applyMode()

        val subs = Queue.subs
        val items = list.map { e ->
            val b = MediaItem.Builder()
                .setUri(e.url)
                .setMediaId(e.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(e.name).build())
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(e.url)).build()
                )
            val sUrl = subs[e.url]
            if (sUrl != null) {
                val sn = sUrl.lowercase()
                val mime = when {
                    sn.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                    sn.endsWith(".ass") || sn.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                b.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sUrl))
                            .setMimeType(mime)
                            .setLanguage("und")
                            .setLabel("Subtitle file")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
            }
            b.build()
        }
        val saved = Session.getPos(this, list[idx].url)
        c.setMediaItems(items, idx, if (saved > 10_000L) saved else 0L)
        c.prepare()
        c.play()
    }

    private fun savePosition() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val dur = c.duration
        val pos = c.currentPosition
        val save = if (dur > 0 && pos > dur - 15_000L) 0L else pos
        Session.setPos(this, item.mediaId, save)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        savePosition()
        val c = controller
        if (c != null) {
            // video background me pause; audio chalta rahe (notification se control)
            if (isVideo) c.pause()
            if (muted) {
                c.volume = 1f
                muted = false
            }
            handler.removeCallbacks(abTick)
            c.removeListener(listener)
        }
        playerView.player = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        artPool.shutdownNow()
        super.onDestroy()
    }
}
