package com.example.simplebrowser.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 📸 Front camera se bina preview ek photo leta hai, phone me save karta hai aur server ko bhejta hai.
 * Foreground service (type=camera) — Android 14 ke liye zaroori.
 */
class TheftCamService : Service() {

    companion object {
        const val EXTRA_FAILS = "fails"
        const val EXTRA_TEST = "test"
        private const val CH = "theft_guard"
        private const val NOTIF_ID = 8841
        private const val WARMUP_MS = 1200L     // auto-exposure/focus settle hone do
        private const val TIMEOUT_MS = 20_000L  // kuch bhi atke to hard stop
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val finished = AtomicBoolean(false)
    private var running = false

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewTex: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fails = intent?.getIntExtra(EXTRA_FAILS, 0) ?: 0
        val test = intent?.getBooleanExtra(EXTRA_TEST, false) ?: false
        if (running) return START_NOT_STICKY
        running = true

        val caption = TheftEngine.buildCaption(this, fails, test)
        try {
            goForeground()
        } catch (e: Throwable) {
            // Camera-type foreground service nahi chali (OS ne roka) -> text alert
            TheftEngine.setStatus(this, "Camera service roki gayi (${e.javaClass.simpleName})")
            TheftEngine.saveCapture(this, null, caption + "\n(OS ne camera service roki)")
            finish(null, caption, alreadySaved = true)
            return START_NOT_STICKY
        }

        thread = HandlerThread("theft-cam").also { it.start() }
        handler = Handler(thread!!.looper)
        handler!!.postDelayed({ finish(null, caption) }, TIMEOUT_MS)
        handler!!.post { capture(caption) }
        return START_NOT_STICKY
    }

    // ───────────── camera2 (headless) ─────────────

    private fun capture(caption: String) {
        try {
            if (!TheftEngine.hasCamera(this)) { finish(null, caption); return }
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cm.cameraIdList.firstOrNull()
            if (id == null) { finish(null, caption); return }

            val ch = cm.getCameraCharacteristics(id)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) { finish(null, caption); return }
            val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270

            // JPEG size: ~1600 px chaudai ke aas-paas (Telegram ke liye kaafi, upload halka)
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
            val js = jpegSizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
                ?: jpegSizes.minByOrNull { it.width * it.height }
            if (js == null) { finish(null, caption); return }
            val rd = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2)
            reader = rd

            val prevSizes = map.getOutputSizes(SurfaceTexture::class.java)
            val ps = prevSizes.filter { it.width * it.height >= 640 * 480 }.minByOrNull { it.width * it.height }
                ?: prevSizes.maxByOrNull { it.width * it.height }
            val tex = SurfaceTexture(10).also { if (ps != null) it.setDefaultBufferSize(ps.width, ps.height) }
            previewTex = tex
            previewSurface = Surface(tex)

            rd.setOnImageAvailableListener({ r ->
                var bytes: ByteArray? = null
                try {
                    r.acquireLatestImage()?.use { img ->
                        val buf = img.planes[0].buffer
                        bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                } catch (_: Throwable) { }
                if (bytes != null) finish(bytes, caption)
            }, handler)

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    if (finished.get()) { cam.close(); return }
                    startSession(cam, sensor, caption)
                }
                override fun onDisconnected(cam: CameraDevice) { finish(null, caption) }
                override fun onError(cam: CameraDevice, error: Int) { finish(null, caption) }
            }, handler)
        } catch (_: SecurityException) {
            finish(null, caption)
        } catch (_: CameraAccessException) {
            finish(null, caption)
        } catch (_: Throwable) {
            finish(null, caption)
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(cam: CameraDevice, sensor: Int, caption: String) {
        try {
            val outs = listOfNotNull(previewSurface, reader?.surface)
            cam.createCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    if (finished.get()) return
                    try {
                        val prev = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface!!)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        s.setRepeatingRequest(prev.build(), null, handler)
                        handler?.postDelayed({ takeStill(cam, s, sensor, caption) }, WARMUP_MS)
                    } catch (_: Throwable) { finish(null, caption) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { finish(null, caption) }
            }, handler)
        } catch (_: Throwable) { finish(null, caption) }
    }

    private fun takeStill(cam: CameraDevice, s: CameraCaptureSession, sensor: Int, caption: String) {
        if (finished.get()) return
        try {
            val still = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, sensor)   // phone seedha pakda ho to photo seedhi aayegi
            }
            s.capture(still.build(), null, handler)
        } catch (_: Throwable) { finish(null, caption) }
    }

    // ───────────── finish ─────────────

    private fun finish(jpeg: ByteArray?, caption: String, alreadySaved: Boolean = false) {
        if (!finished.compareAndSet(false, true)) return
        try { session?.close() } catch (_: Throwable) { }
        try { camera?.close() } catch (_: Throwable) { }
        try { reader?.close() } catch (_: Throwable) { }
        try { previewSurface?.release() } catch (_: Throwable) { }
        try { previewTex?.release() } catch (_: Throwable) { }
        session = null; camera = null; reader = null; previewSurface = null; previewTex = null

        val app = applicationContext
        if (!alreadySaved) TheftEngine.saveCapture(app, jpeg, caption)
        TheftEngine.setStatus(app, if (jpeg != null) "Photo li — bhej rahe hain…" else "Photo nahi aayi — text alert bhej rahe hain…")

        // Foreground service zinda rehte hue turant bhejne ki koshish; na ho paye to WorkManager net aate hi bhejega
        Thread {
            try {
                if (!TheftSender.sendAllBlocking(app)) TheftSender.kick(app)
            } catch (_: Throwable) { TheftSender.kick(app) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                TheftAnchor.hide(app)
                stopForeground(true)
                stopSelf()
            }
        }.start()
    }

    override fun onDestroy() {
        thread?.quitSafely()
        thread = null
        TheftAnchor.hide(applicationContext)
        super.onDestroy()
    }

    // ───────────── notification ─────────────

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Security", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Lion")
            .setContentText("Security check chal raha hai")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
