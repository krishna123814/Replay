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
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 📸 Front camera se bina preview 3 photo + saath me location, phone me save karta hai aur server ko bhejta hai.
 * Foreground service (type=camera) — Android 14 ke liye zaroori.
 */
class TheftCamService : Service() {

    companion object {
        const val EXTRA_FAILS = "fails"
        const val EXTRA_TEST = "test"
        const val EXTRA_REASON = "reason"
        private const val CH = "theft_guard"
        private const val NOTIF_ID = 8841
        private const val PHOTOS = 3            // itni photos
        private const val WARMUP_MS = 1200L     // auto-exposure/focus settle hone do
        private const val GAP_MS = 700L         // photos ke beech gap
        private const val LOC_TIMEOUT_MS = 10_000L
        private const val TIMEOUT_MS = 25_000L  // kuch bhi atke to hard stop
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
    private var sensorOri = 270

    private val photos = ArrayList<ByteArray>()
    private var photosDone = false
    private var locDone = false
    private var loc: Location? = null

    private var fails = 0
    private var test = false
    private var reason = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fails = intent?.getIntExtra(EXTRA_FAILS, 0) ?: 0
        test = intent?.getBooleanExtra(EXTRA_TEST, false) ?: false
        reason = intent?.getStringExtra(EXTRA_REASON) ?: ""
        if (running) return START_NOT_STICKY
        running = true

        try {
            goForeground()
        } catch (e: Throwable) {
            // Camera-type foreground service nahi chali (OS ne roka) -> text alert
            TheftEngine.setStatus(this, "Camera service roki gayi (${e.javaClass.simpleName})")
            TheftEngine.queueText(this, TheftEngine.buildCaption(this, fails, test, reason) + "\n⚠️ Photo nahi li ja saki (OS ne camera service roki).")
            TheftAnchor.hide(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }

        thread = HandlerThread("theft-cam").also { it.start() }
        handler = Handler(thread!!.looper)
        handler!!.postDelayed({ forceFinish() }, TIMEOUT_MS)
        handler!!.post {
            startLocation()
            capture()
        }
        return START_NOT_STICKY
    }

    // ───────────── location (saath-saath) ─────────────

    private fun startLocation() {
        val th = thread ?: return
        try {
            TheftLocation.fetch(applicationContext, th.looper, LOC_TIMEOUT_MS) { l ->
                handler?.post { loc = l; locDone = true; maybeFinish() }
            }
        } catch (_: Throwable) { locDone = true }
    }

    // ───────────── camera2 (headless) ─────────────

    private fun capture() {
        try {
            if (!TheftEngine.hasCamera(this)) { photosFailed(); return }
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cm.cameraIdList.firstOrNull()
            if (id == null) { photosFailed(); return }

            val ch = cm.getCameraCharacteristics(id)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) { photosFailed(); return }
            sensorOri = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270

            // JPEG size: ~1600 px chaudai ke aas-paas (Telegram ke liye kaafi, upload halka)
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
            val js = jpegSizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
                ?: jpegSizes.minByOrNull { it.width * it.height }
            if (js == null) { photosFailed(); return }
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
                val b = bytes
                if (b != null && !finished.get()) {
                    photos.add(b)
                    if (photos.size >= PHOTOS) {
                        photosDone = true
                        closeCamera()
                        maybeFinish()
                    } else {
                        handler?.postDelayed({ takeStill() }, GAP_MS)
                    }
                }
            }, handler)

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    if (finished.get()) { cam.close(); return }
                    startSession(cam)
                }
                override fun onDisconnected(cam: CameraDevice) { photosFailed() }
                override fun onError(cam: CameraDevice, error: Int) { photosFailed() }
            }, handler)
        } catch (_: Throwable) {
            photosFailed()
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(cam: CameraDevice) {
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
                        handler?.postDelayed({ takeStill() }, WARMUP_MS)
                    } catch (_: Throwable) { photosFailed() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { photosFailed() }
            }, handler)
        } catch (_: Throwable) { photosFailed() }
    }

    private fun takeStill() {
        if (finished.get() || photosDone) return
        val cam = camera ?: return
        val s = session ?: return
        val rd = reader ?: return
        try {
            val still = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rd.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, sensorOri)   // phone seedha pakda ho to photo seedhi aayegi
            }
            s.capture(still.build(), null, handler)
        } catch (_: Throwable) { photosFailed() }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Throwable) { }
        try { camera?.close() } catch (_: Throwable) { }
        try { reader?.close() } catch (_: Throwable) { }
        try { previewSurface?.release() } catch (_: Throwable) { }
        try { previewTex?.release() } catch (_: Throwable) { }
        session = null; camera = null; reader = null; previewSurface = null; previewTex = null
    }

    // ───────────── finish ─────────────

    /** Camera se kuch bhi nahi mila / ya jitni mili utni hi: photos wala hissa khatam. */
    private fun photosFailed() {
        photosDone = true
        closeCamera()
        maybeFinish()
    }

    private fun maybeFinish() { if (photosDone && locDone) finish() }

    private fun forceFinish() { photosDone = true; locDone = true; finish() }

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        closeCamera()
        val app = applicationContext
        val caption = TheftEngine.buildCaption(app, fails, test, reason) + "\n" + TheftLocation.line(loc, app)
        TheftEngine.saveCapture(app, photos.toList(), caption)
        TheftEngine.setStatus(app, if (photos.isNotEmpty()) "${photos.size} photo li — bhej rahe hain…" else "Photo nahi aayi — text alert bhej rahe hain…")

        // Foreground service zinda rehte hue turant bhejne ki koshish; na ho paye to WorkManager net aate hi bhejega
        Thread {
            try {
                if (!TheftSender.sendAllBlocking(app)) TheftSender.kick(app)
            } catch (_: Throwable) { TheftSender.kick(app) }
            Handler(android.os.Looper.getMainLooper()).post {
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
