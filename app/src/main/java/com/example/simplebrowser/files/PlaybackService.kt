package com.example.simplebrowser.files

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Server se stream hone wala audio/video yahin chalta hai; app band hone par bhi music chalta rahe. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    companion object {
        @Volatile var inst: PlaybackService? = null
    }

    fun pauseNow() {
        session?.player?.pause()
    }

    override fun onCreate() {
        super.onCreate()
        inst = this

        val dsFactory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(Dav.client))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dsFactory))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setWakeMode(C.WAKE_MODE_NETWORK)

        val open = Intent(this, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(pi).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        inst = null
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}

/** Music / video band karne ka timer (app band ho to bhi chalta hai, jab tak service zinda ho). */
object SleepTimer {
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var endAt = 0L
    private val r = Runnable {
        endAt = 0L
        PlaybackService.inst?.pauseNow()
    }

    fun set(minutes: Int) {
        h.removeCallbacks(r)
        if (minutes <= 0) {
            endAt = 0L
            return
        }
        endAt = System.currentTimeMillis() + minutes * 60_000L
        h.postDelayed(r, minutes * 60_000L)
    }

    fun remainingMin(): Int =
        if (endAt == 0L) 0 else (((endAt - System.currentTimeMillis()) + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
}
