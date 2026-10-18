package com.example.simplebrowser.files

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.simplebrowser.R
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * SD card / USB pendrive ka live nazar rakhne wala (app process zinda ho tab tak, App.onCreate se shuru).
 *  - Android 11+ par StorageVolumeCallback, purane par broadcast receiver; dono ke saath 2s / 5s par dobara scan.
 *  - Drive lagte hi notification ("kholo"), bina eject nikalne par warning.
 *  - Screens [addListener] se jud kar apni list refresh karti hain.
 */
object VolumeWatcher {

    private const val CHANNEL = "usb_drives"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vol-scan").also { it.isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var started = false
    private var app: Context? = null
    private var last: Map<String, String> = emptyMap()

    /** Sabse naya scan (har halat ki drive). Main thread par update hota hai. */
    @Volatile var snapshot: List<LocalFs.Vol> = emptyList()
        private set

    private val rescanTask = Runnable { scanNow() }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = rescanSoon()
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val a = ctx.applicationContext
        app = a
        try {
            snapshot = LocalFs.allRemovable(a)
            last = keyMap(snapshot)          // pehle se lagi drive ke liye notification nahi
        } catch (e: Throwable) { }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val sm = a.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                sm.registerStorageVolumeCallback(a.mainExecutor, object : StorageManager.StorageVolumeCallback() {
                    override fun onStateChanged(volume: StorageVolume) {
                        rescanSoon()
                    }
                })
            } catch (e: Throwable) { }
        }
        try {
            val f1 = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addAction(Intent.ACTION_MEDIA_CHECKING)
                addAction(Intent.ACTION_MEDIA_UNMOUNTABLE)
                addAction(Intent.ACTION_MEDIA_NOFS)
                addDataScheme("file")
            }
            val f2 = IntentFilter().apply {
                addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
                addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
            }
            ContextCompat.registerReceiver(a, rx, f1, ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(a, rx, f2, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Throwable) { }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** USB lagte hi mount me kuch sec lagte hain: turant + 2s + 5s par dekho. */
    fun rescanSoon() {
        main.removeCallbacks(rescanTask)
        main.post(rescanTask)
        main.postDelayed(rescanTask, 2000)
        main.postDelayed(rescanTask, 5000)
    }

    private fun scanNow() {
        val a = app ?: return
        io.execute {
            val list = try { LocalFs.allRemovable(a) } catch (e: Throwable) { return@execute }
            main.post { applyScan(a, list) }
        }
    }

    private fun keyOf(v: LocalFs.Vol) = v.uuid ?: v.root.absolutePath
    private fun keyMap(l: List<LocalFs.Vol>): Map<String, String> = l.associate { keyOf(it) to it.state }

    private fun applyScan(a: Context, list: List<LocalFs.Vol>) {
        val prev = last
        for (v in list) {
            val before = prev[keyOf(v)]
            if (before == v.state) continue
            when {
                v.mounted && (before == null || !(before == Environment.MEDIA_MOUNTED || before == Environment.MEDIA_MOUNTED_READ_ONLY)) ->
                    postNote(a, v, "${v.icon} ${v.label} mil gayi", mountedLine(v))
                v.state == Environment.MEDIA_UNMOUNTABLE || v.state == Environment.MEDIA_NOFS ->
                    postNote(a, v, "❌ ${v.label} khul nahi rahi", v.stateText())
                v.state == Environment.MEDIA_BAD_REMOVAL ->
                    postNote(a, v, "⚠ ${v.label} bina eject kiye nikali gayi", "File copy chal rahi thi to check karo")
            }
        }
        // jo drive ab nahi hai, uski "mil gayi" notification hata do
        for (k in prev.keys) {
            if (list.none { keyOf(it) == k }) {
                try { NotificationManagerCompat.from(a).cancel(k.hashCode()) } catch (e: Throwable) { }
            }
        }
        val changed = prev != keyMap(list)
        snapshot = list
        last = keyMap(list)
        // sirf badlav par listeners ko batao (nahi to har 2s/5s scan par UI baar-baar bante)
        if (changed) for (l in listeners) try { l() } catch (e: Throwable) { }
    }

    private fun mountedLine(v: LocalFs.Vol): String {
        val fs = if (v.fs == "?") "" else " · ${v.fs}"
        val ro = if (v.readOnly || !v.writable) " · sirf padh sakte hain" else ""
        return v.kind + fs + ro + " · dabao aur kholo"
    }

    private fun postNote(a: Context, v: LocalFs.Vol, title: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(a, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL, "USB / SD card", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
            }
            val open = Intent(a, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                a, keyOf(v).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(a, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif_lion)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            NotificationManagerCompat.from(a).notify(keyOf(v).hashCode(), n)
        } catch (e: Throwable) { }
    }
}
