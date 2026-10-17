package com.example.simplebrowser.files

import android.app.PictureInPictureParams
import android.content.ComponentName
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
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Video + music player. Video: brightness / volume / seek gestures, double-tap seek, rotate, lock, speed,
 * aspect ratio, audio + subtitle tracks, PiP. Music: shuffle / repeat, album art, sleep timer, queue.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var audioIcon: TextView
    private lateinit var hud: TextView
    private lateinit var lockBtn: TextView
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

    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideLock = Runnable { if (locked) lockBtn.visibility = View.GONE }

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            titleView.text = mediaMetadata.title ?: ""
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isVideo) loadArt(mediaItem)
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

        audioIcon = TextView(this)
        audioIcon.text = "🎵"
        audioIcon.textSize = 96f
        audioIcon.alpha = 0.35f
        audioIcon.gravity = Gravity.CENTER
        root.addView(audioIcon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // top bar: ← title 🔒 ⟳ ⋮
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(0x88000000.toInt())
        topBar.setPadding(dp(6), dp(8), dp(6), dp(4))
        titleView = TextView(this)
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 15f
        titleView.maxLines = 2
        titleView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        topBar.addView(iconBtn("←") { finish() })
        topBar.addView(titleView)
        topBar.addView(iconBtn("🔒") { setLocked(true) })
        topBar.addView(iconBtn("⟳") { cycleOrientation() })
        topBar.addView(iconBtn("⋮") { showMenu() })
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
