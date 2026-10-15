package com.example.simplebrowser

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DlSpec(
    val url: String,
    val cookie: String,
    val ua: String,
    val referer: String,
    val name: String,
    val mime: String
)

data class Scheduled(val id: Int, val spec: DlSpec, val at: Long, val wifiOnly: Boolean)

/**
 * Downloader: normal download (Android DownloadManager, notification + resume ke saath)
 * aur schedule (AlarmManager, reboot/app-update ke baad bhi wapas set).
 * Files: Downloads/Lion/ me (Android 9 ya kam par app ke Downloads folder me).
 */
object Downloader {

    const val ACTION_FIRE = "com.example.simplebrowser.DOWNLOAD_FIRE"
    private const val PREF_LIST = "scheduled_downloads"
    private const val PREF_WIFI = "dl_wifi_only"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FloatingService.PREFS, Context.MODE_PRIVATE)

    // ── storage ───────────────────────────────────────────────────────────
    fun loadScheduled(ctx: Context): MutableList<Scheduled> {
        val list = ArrayList<Scheduled>()
        val raw = prefs(ctx).getString(PREF_LIST, null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Scheduled(
                        o.getInt("id"),
                        DlSpec(
                            o.getString("url"), o.optString("cookie"), o.optString("ua"),
                            o.optString("referer"), o.optString("name", "download"), o.optString("mime")
                        ),
                        o.getLong("at"), o.optBoolean("wifi", false)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun saveScheduled(ctx: Context, list: List<Scheduled>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id).put("url", s.spec.url).put("cookie", s.spec.cookie)
                    .put("ua", s.spec.ua).put("referer", s.spec.referer)
                    .put("name", s.spec.name).put("mime", s.spec.mime)
                    .put("at", s.at).put("wifi", s.wifiOnly)
            )
        }
        prefs(ctx).edit().putString(PREF_LIST, arr.toString()).apply()
    }

    // ── normal download ───────────────────────────────────────────────────
    private fun sanitizeName(raw: String): String {
        val n = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        return if (n.isEmpty()) "download" else n.take(120)
    }

    /** DownloadManager me daalo. true = enqueue ho gaya. */
    fun enqueueNow(ctx: Context, spec: DlSpec, wifiOnly: Boolean): Boolean {
        try {
            val u = Uri.parse(spec.url)
            val scheme = u.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            val name = sanitizeName(spec.name)
            val req = DownloadManager.Request(u)
            req.setTitle(name)
            req.setDescription("Lion")
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (spec.mime.isNotEmpty()) req.setMimeType(spec.mime)

            // login wali sites ke liye taaza cookie, warna schedule ke waqt wali
            var cookie: String? = null
            try {
                cookie = CookieManager.getInstance().getCookie(spec.url)
            } catch (e: Exception) {
            }
            if (cookie.isNullOrEmpty()) cookie = spec.cookie
            if (!cookie.isNullOrEmpty()) req.addRequestHeader("Cookie", cookie)
            if (spec.ua.isNotEmpty()) req.addRequestHeader("User-Agent", spec.ua)
            if (spec.referer.isNotEmpty()) req.addRequestHeader("Referer", spec.referer)
            if (wifiOnly) req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Lion/$name")
            } else {
                req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
            }
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ── schedule ──────────────────────────────────────────────────────────
    private fun alarmIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, DownloadAlarmReceiver::class.java)
            .setAction(ACTION_FIRE).putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setAlarm(ctx: Context, item: Scheduled) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // exact permission ki zaroorat nahi: Doze me kuch minute upar-neeche ho sakta hai
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, alarmIntent(ctx, item.id))
        } catch (e: Exception) {
        }
    }

    fun schedule(ctx: Context, spec: DlSpec, at: Long, wifiOnly: Boolean): Scheduled {
        val list = loadScheduled(ctx)
        var id = ((System.currentTimeMillis() / 1000L) % 1_000_000_000L).toInt()
        while (list.any { it.id == id }) id++
        val item = Scheduled(id, spec.copy(name = sanitizeName(spec.name)), at, wifiOnly)
        list.add(item)
        saveScheduled(ctx, list)
        setAlarm(ctx, item)
        return item
    }

    fun cancel(ctx: Context, id: Int) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmIntent(ctx, id))
        } catch (e: Exception) {
        }
        val list = loadScheduled(ctx)
        list.removeAll { it.id == id }
        saveScheduled(ctx, list)
    }

    /** Alarm bajne par: list se hatao aur download shuru karo. */
    fun fire(ctx: Context, id: Int) {
        val list = loadScheduled(ctx)
        val item = list.firstOrNull { it.id == id } ?: return
        list.remove(item)
        saveScheduled(ctx, list)
        val ok = enqueueNow(ctx, item.spec, item.wifiOnly)
        Toast.makeText(
            ctx.applicationContext,
            if (ok) "Scheduled download shuru: ${item.spec.name}"
            else "Scheduled download nahi ho paya: ${item.spec.name}",
            Toast.LENGTH_LONG
        ).show()
    }

    /** Reboot / app update ke baad alarms dobara lagao; nikla hua waqt ho to turant shuru. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for (item in loadScheduled(ctx)) {
            if (item.at <= now + 5000L) fire(ctx, item.id) else setAlarm(ctx, item)
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun themed(ctx: Context) =
        ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun show(b: AlertDialog.Builder, overlay: Boolean) {
        val d = b.create()
        if (overlay) d.window?.setType(overlayType())
        d.show()
    }

    private fun fmtTime(at: Long): String =
        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(at))

    private fun fmtSize(bytes: Long): String {
        if (bytes <= 0) return "size pata nahi"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis() + 30_000L) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun doSchedule(ctx: Context, spec: DlSpec, at: Long, wifi: Boolean) {
        val item = schedule(ctx, spec, at, wifi)
        Toast.makeText(
            ctx, "Schedule ho gaya: ${item.spec.name}\n${fmtTime(item.at)}", Toast.LENGTH_LONG
        ).show()
    }

    // ── entry: WebView ka download listener yahan aata hai ────────────────
    fun offer(
        ctx: Context, overlay: Boolean, url: String, ua: String?, contentDisposition: String?,
        mime: String?, length: Long, referer: String?
    ) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Toast.makeText(ctx, "Is type ka download (blob/data) abhi support nahi", Toast.LENGTH_LONG).show()
            return
        }
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        var cookie = ""
        try {
            cookie = CookieManager.getInstance().getCookie(url) ?: ""
        } catch (e: Exception) {
        }
        chooser(ctx, overlay, DlSpec(url, cookie, ua ?: "", referer ?: "", name, mime ?: ""), length)
    }

    /** "Abhi download" / "Schedule" / "Cancel" */
    private fun chooser(ctx: Context, overlay: Boolean, spec: DlSpec, length: Long) {
        try {
            val t = themed(ctx)
            val cb = CheckBox(t)
            cb.text = "Sirf Wi-Fi par"
            cb.isChecked = prefs(ctx).getBoolean(PREF_WIFI, false)
            val box = LinearLayout(t)
            box.orientation = LinearLayout.VERTICAL
            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
            box.setPadding(pad, 0, pad, 0)
            box.addView(cb)

            show(
                AlertDialog.Builder(t)
                    .setTitle("Download")
                    .setMessage(spec.name + "\n" + fmtSize(length))
                    .setView(box)
                    .setPositiveButton("Abhi") { _, _ ->
                        prefs(ctx).edit().putBoolean(PREF_WIFI, cb.isChecked).apply()
                        val ok = enqueueNow(ctx, spec, cb.isChecked)
                        Toast.makeText(
                            ctx,
                            if (ok) "Download shuru – notification dekho" else "Download nahi ho paya",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNeutralButton("Schedule…") { _, _ ->
                        prefs(ctx).edit().putBoolean(PREF_WIFI, cb.isChecked).apply()
                        pickTime(ctx, overlay, spec, cb.isChecked)
                    }
                    .setNegativeButton("Cancel", null),
                overlay
            )
        } catch (e: Exception) {
            enqueueNow(ctx, spec, false)
        }
    }

    private fun pickTime(ctx: Context, overlay: Boolean, spec: DlSpec, wifi: Boolean) {
        val items = arrayOf(
            "15 minute baad", "1 ghante baad", "3 ghante baad", "Raat 2:00 baje", "Time chuno…"
        )
        try {
            show(
                AlertDialog.Builder(themed(ctx))
                    .setTitle("Kab download kare?")
                    .setItems(items) { _, which ->
                        val now = System.currentTimeMillis()
                        when (which) {
                            0 -> doSchedule(ctx, spec, now + 15 * 60_000L, wifi)
                            1 -> doSchedule(ctx, spec, now + 60 * 60_000L, wifi)
                            2 -> doSchedule(ctx, spec, now + 3 * 60 * 60_000L, wifi)
                            3 -> doSchedule(ctx, spec, nextOccurrence(2, 0), wifi)
                            else -> {
                                val c = Calendar.getInstance()
                                val d = TimePickerDialog(
                                    themed(ctx),
                                    { _, h, m -> doSchedule(ctx, spec, nextOccurrence(h, m), wifi) },
                                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false
                                )
                                if (overlay) d.window?.setType(overlayType())
                                d.show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null),
                overlay
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Schedule dialog nahi khul paya", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Downloads manager (⚙ settings se) ─────────────────────────────────
    fun manager(ctx: Context, overlay: Boolean) {
        try {
            val list = loadScheduled(ctx).sortedBy { it.at }
            val b = AlertDialog.Builder(themed(ctx)).setTitle("Downloads")
            if (list.isEmpty()) {
                b.setMessage(
                    "Koi scheduled download nahi.\n\n\"Naya URL\" se link daalke abhi ya baad me download kar sakte ho."
                )
            } else {
                val names = Array(list.size) { fmtTime(list[it].at) + " · " + list[it].spec.name }
                b.setItems(names) { _, which -> confirmCancel(ctx, overlay, list[which]) }
            }
            b.setPositiveButton("Naya URL") { _, _ -> askUrl(ctx, overlay) }
            b.setNeutralButton("Downloads dekho") { _, _ -> openSystemDownloads(ctx) }
            b.setNegativeButton("Band", null)
            show(b, overlay)
        } catch (e: Exception) {
        }
    }

    private fun confirmCancel(ctx: Context, overlay: Boolean, item: Scheduled) {
        try {
            show(
                AlertDialog.Builder(themed(ctx))
                    .setTitle("Schedule hataye?")
                    .setMessage(item.spec.name + "\n" + fmtTime(item.at))
                    .setPositiveButton("Hatao") { _, _ ->
                        cancel(ctx, item.id)
                        Toast.makeText(ctx, "Schedule hata diya", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Rehne do", null),
                overlay
            )
        } catch (e: Exception) {
        }
    }

    private fun askUrl(ctx: Context, overlay: Boolean) {
        try {
            val t = themed(ctx)
            val input = EditText(t)
            input.setSingleLine(true)
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            input.hint = "https://…/file.zip"
            show(
                AlertDialog.Builder(t)
                    .setTitle("Download URL")
                    .setView(input)
                    .setPositiveButton("Aage") { _, _ ->
                        var u = input.text.toString().trim()
                        if (u.isNotEmpty()) {
                            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                            offer(ctx, overlay, u, WebSettings.getDefaultUserAgent(ctx), null, null, -1L, null)
                        }
                    }
                    .setNegativeButton("Cancel", null),
                overlay
            )
        } catch (e: Exception) {
        }
    }

    private fun openSystemDownloads(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Downloads app nahi khul payi", Toast.LENGTH_SHORT).show()
        }
    }
}

class DownloadAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Downloader.ACTION_FIRE) {
            Downloader.fire(context, intent.getIntExtra("id", -1))
        }
    }
}

class DownloadBootReceiver : BroadcastReceiver() {
    // BOOT_COMPLETED / MY_PACKAGE_REPLACED: alarms dobara lagao
    override fun onReceive(context: Context, intent: Intent) {
        Downloader.rescheduleAll(context)
    }
}
