package com.example.simplebrowser.files

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Login ke baad ka pehla screen: do storage (AIC Disk, HF Bucket) + Transfers. */
class HomeActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var aicSub: TextView
    private lateinit var hfSub: TextView
    private lateinit var hfCard: LinearLayout
    private lateinit var hfBarBox: LinearLayout
    private lateinit var clipTv: TextView
    private lateinit var localSub: TextView
    private lateinit var volBox: LinearLayout
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val volCb: () -> Unit = { refreshSd() }

    private val storagePerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            refreshLocal()
            if (ok) open(Store.LOCAL)
        }

    /** null = HF theek (ya abhi pata nahi); String = HF band hai, ye sabab. */
    private var hfReason: String? = null

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)

        if (!Session.hasCreds()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (!::hfSub.isInitialized) return
        refreshClip()
        refreshLocal()
        probeHf()
        refreshHfCap()
        VolumeWatcher.addListener(volCb)
        VolumeWatcher.rescanSoon()
    }

    override fun onPause() {
        super.onPause()
        VolumeWatcher.removeListener(volCb)
        uiHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun buildUi() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = if (intent.getBooleanExtra("pick", false)) "📎 File chuno" else "📁 File Manager"
        title.textSize = 26f
        title.setTextColor(Color.BLACK)
        title.typeface = Typeface.DEFAULT_BOLD
        title.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val more = TextView(this)
        more.text = "⋮"
        more.textSize = 24f
        more.setTextColor(Color.BLACK)
        more.gravity = Gravity.CENTER
        more.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        more.setOnClickListener { showMenu() }
        top.addView(title)
        top.addView(more)
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val host = Session.baseUrl.toHttpUrlOrNull()?.host ?: ""

        aicSub = TextView(this)
        val aicCard = card(Store.AIC, aicSub) { open(Store.AIC) }
        aicSub.text = "VPS ki disk (/data) · $host"

        hfSub = TextView(this)
        hfCard = card(Store.HF, hfSub) {
            val r = hfReason
            if (r != null) {
                Toast.makeText(this, "❌ $r", Toast.LENGTH_LONG).show()
            } else {
                open(Store.HF)
            }
        }
        hfSub.text = "Check ho raha hai…"
        hfBarBox = LinearLayout(this)                      // HF capacity ki patti (pichle Analyze ke hisaab se)
        hfBarBox.orientation = LinearLayout.VERTICAL
        (hfCard.getChildAt(1) as LinearLayout).addView(hfBarBox, LinearLayout.LayoutParams(MATCH, WRAP))

        localSub = TextView(this)
        val localCard = card(Store.LOCAL, localSub) { openLocal() }

        col.addView(aicCard)
        col.addView(hfCard)
        col.addView(localCard)

        volBox = LinearLayout(this)                        // SD card / pendrive lagi ho tabhi cards dikhte hain
        volBox.orientation = LinearLayout.VERTICAL
        col.addView(volBox, LinearLayout.LayoutParams(MATCH, WRAP))

        clipTv = TextView(this)
        clipTv.setTextColor(0xFFE65100.toInt())
        clipTv.textSize = 13f
        clipTv.setPadding(dp(4), dp(8), dp(4), dp(8))
        col.addView(clipTv, LinearLayout.LayoutParams(MATCH, WRAP))

        val tr = Button(this)
        tr.text = "📊 Transfers"
        tr.setOnClickListener { startActivity(Intent(this, TransfersActivity::class.java)) }
        col.addView(tr, LinearLayout.LayoutParams(MATCH, WRAP))

        val an = Button(this)
        an.text = "🔍 Analyze: kaun si folder kitni jagah le rahi"
        an.setOnClickListener { pickAnalyze() }
        col.addView(an, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.WHITE)
        scroll.isFillViewport = true
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun card(store: Store, sub: TextView, onClick: () -> Unit): LinearLayout =
        card(store.icon, store.label, sub, onClick)

    private fun card(iconText: String, labelText: String, sub: TextView, onClick: () -> Unit): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(20), dp(24), dp(20), dp(24))
        val bg = GradientDrawable()
        bg.setColor(0xFFF0F0F0.toInt())
        bg.cornerRadius = dp(16).toFloat()
        c.background = bg
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.layoutParams = lp

        val icon = TextView(this)
        icon.text = iconText
        icon.textSize = 40f
        icon.setPadding(0, 0, dp(16), 0)

        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val name = TextView(this)
        name.text = labelText
        name.textSize = 20f
        name.setTextColor(Color.BLACK)
        name.typeface = Typeface.DEFAULT_BOLD
        sub.setTextColor(Color.BLACK)
        sub.textSize = 13f
        txt.addView(name)
        txt.addView(sub)

        c.addView(icon)
        c.addView(txt)
        c.setOnClickListener { onClick() }
        return c
    }

    private fun refreshSd() {
        if (!::volBox.isInitialized) return
        volBox.removeAllViews()
        val vols = VolumeWatcher.snapshot
        for (v in vols) {
            val sub = TextView(this)
            val hasAccess = LocalFs.hasAccess(this)
            var bar: View? = null
            if (!v.mounted) {
                sub.text = v.kind + " · " + v.stateText()
            } else if (!hasAccess) {
                sub.text = "${v.kind} · permission chahiye (dabao)"
            } else {
                var line = v.kind + (if (v.fs == "?") "" else " · " + v.fs)
                try {
                    val sf = StatFs(v.root.path)
                    val total = sf.totalBytes
                    val free = sf.availableBytes
                    line += " · " + fmtSize(free) + " khaali / " + fmtSize(total)
                    if (total > 0) bar = usageBar(((total - free).toFloat() / total.toFloat()).coerceIn(0f, 1f))
                } catch (e: Exception) { }
                if (v.readOnly) line += " · sirf padh sakte hain"
                else if (!v.writable) line += " · likhna band (Android restriction)"
                sub.text = line
            }
            val c = card(v.icon, v.label, sub) { openVol(v) }
            if (bar != null) (c.getChildAt(1) as LinearLayout).addView(bar)
            c.setOnLongClickListener { volMenu(v); true }
            volBox.addView(c)
        }
        // USB device lagi hai par Android ne storage banayi hi nahi
        if (LocalFs.usbWithoutVolume(this, vols)) {
            val sub = TextView(this)
            sub.text = "Mount nahi hui: ya abhi ho rahi hai, ya filesystem (NTFS / RAW) Android nahi padh pa raha. Dabao: Settings"
            volBox.addView(card("🔌", "USB device mili", sub) {
                Toast.makeText(this, "Computer par drive ko exFAT / FAT32 me format karke dekho", Toast.LENGTH_LONG).show()
                LocalFs.openStorageSettings(this)
            })
        }
    }

    private fun usageBar(used: Float): View {
        val color = when {
            used < 0.8f -> 0xFF2E7D32.toInt()
            used < 0.92f -> 0xFFEF6C00.toInt()
            else -> 0xFFC62828.toInt()
        }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0xFFD0D0D0.toInt())
        val u = View(this)
        u.setBackgroundColor(color)
        bar.addView(u, LinearLayout.LayoutParams(0, MATCH, used.coerceAtLeast(0.01f)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, MATCH, (1f - used).coerceAtLeast(0.01f)))
        val lp = LinearLayout.LayoutParams(MATCH, dp(6))
        lp.topMargin = dp(8)
        bar.layoutParams = lp
        return bar
    }

    /** Card ko lamba dabane par: jaankari, eject, rescan. */
    private fun volMenu(v: LocalFs.Vol) {
        AlertDialog.Builder(this)
            .setTitle(v.icon + " " + v.label)
            .setItems(arrayOf("ℹ Drive ki jaankari", "⏏ Safe eject", "🔄 Dobara scan karo")) { _, i ->
                when (i) {
                    0 -> DriveUi.info(this, v)
                    1 -> DriveUi.safeEject(this, v)
                    else -> {
                        VolumeWatcher.rescanSoon()
                        Toast.makeText(this, "Scan ho raha hai…", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun openVol(v: LocalFs.Vol) {
        if (!v.mounted) {
            Toast.makeText(this, v.stateText(), Toast.LENGTH_LONG).show()
            if (v.state == android.os.Environment.MEDIA_UNMOUNTED || v.state == android.os.Environment.MEDIA_UNMOUNTABLE ||
                v.state == android.os.Environment.MEDIA_NOFS) LocalFs.openStorageSettings(this)
            return
        }
        if (!LocalFs.hasAccess(this)) {
            openLocal()                       // same permission dialog
            return
        }
        open(Store.LOCAL, v.root.absolutePath, v.label, v.icon)
    }

    private fun refreshLocal() {
        if (!::localSub.isInitialized) return
        refreshSd()
        localSub.text = if (LocalFs.hasAccess(this)) {
            try {
                val sf = StatFs(LocalFs.rootDir().path)
                "Phone ki files · " + fmtSize(sf.availableBytes) + " khaali"
            } catch (e: Exception) {
                "Phone ki files"
            }
        } else {
            "Phone ki files · permission chahiye (dabao)"
        }
    }

    private fun openLocal() {
        if (LocalFs.hasAccess(this)) {
            open(Store.LOCAL)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Phone storage ki permission")
            .setMessage(
                if (Build.VERSION.SDK_INT >= 30)
                    "Phone ki saari files dikhane ke liye 'All files access' chahiye.\n\nSettings khulegi: MyFiles ke aage switch ON karo, phir wapas aao."
                else
                    "Phone ki files dikhane ke liye storage permission chahiye."
            )
            .setPositiveButton("Allow") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val pickMode: Boolean get() = intent.getBooleanExtra("pick", false)

    private val pickBrowser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                setResult(RESULT_OK, r.data)
                finish()
            }
        }

    private fun open(store: Store, localRoot: String? = null, label: String? = null, icon: String? = null) {
        val i = Intent(this, BrowserActivity::class.java).putExtra("store", store.id)
        if (localRoot != null) i.putExtra("local_root", localRoot)
        if (label != null) i.putExtra("local_label", label)
        if (icon != null) i.putExtra("local_icon", icon)
        if (pickMode) {
            i.putExtra("pick", true).putExtra("pick_multi", intent.getBooleanExtra("pick_multi", false))
            pickBrowser.launch(i)
        } else {
            startActivity(i)
        }
    }

    private fun refreshClip() {
        val c = Session.clip
        clipTv.text = if (c.isEmpty()) "" else
            "📋 ${c.size} item clipboard me (${if (Session.clipMove) "Move" else "Copy"}) — storage kholo, folder me jao aur 'Paste here' dabao"
    }

    /** HF card ki line: 100 GB capacity + (pichla Analyze ho chuka ho to) kitna use hua. */
    private fun hfCapText(): String {
        val cap = fmtSize(Analyzer.HF_CAPACITY)
        val c = Analyzer.cachedUsed(this, "hf")
        return if (c == null) {
            "Hugging Face Storage Bucket · $cap capacity · 🔍 Analyze se use dekho"
        } else {
            val pct = c.first.toDouble() * 100 / Analyzer.HF_CAPACITY
            "Hugging Face Storage Bucket · " + fmtSize(c.first) + " / " + cap +
                String.format(java.util.Locale.US, " (%.1f%%)", pct)
        }
    }

    private fun refreshHfCap() {
        if (!::hfBarBox.isInitialized) return
        hfBarBox.removeAllViews()
        val c = Analyzer.cachedUsed(this, "hf") ?: return
        hfBarBox.addView(usageBar((c.first.toDouble() / Analyzer.HF_CAPACITY).toFloat().coerceIn(0f, 1f)))
        if (hfReason == null) hfSub.text = hfCapText()
    }

    /** Kis storage ka analyze karna hai: AIC / HF / Phone / SD / USB. */
    private fun pickAnalyze() {
        class Opt(val label: String, val store: Store, val root: String)
        val opts = ArrayList<Opt>()
        opts.add(Opt(Store.AIC.icon + " " + Store.AIC.label, Store.AIC, Dav.withSlash(Storages.root(Store.AIC))))
        opts.add(Opt(Store.HF.icon + " " + Store.HF.label, Store.HF, Dav.withSlash(Storages.root(Store.HF))))
        if (LocalFs.hasAccess(this)) {
            opts.add(Opt(Store.LOCAL.icon + " " + Store.LOCAL.label, Store.LOCAL, Dav.withSlash(LocalFs.rootUrl())))
            for (v in VolumeWatcher.snapshot) {
                if (v.mounted) opts.add(Opt(v.icon + " " + v.label, Store.LOCAL, LocalFs.urlOf(v.root, true)))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Kis storage ka Analyze?")
            .setItems(opts.map { it.label }.toTypedArray()) { _, i ->
                val o = opts[i]
                if (o.store == Store.HF && hfReason != null) {
                    Toast.makeText(this, "❌ " + hfReason, Toast.LENGTH_LONG).show()
                } else {
                    AnalyzeUi(this, o.store, o.root, o.label) { refreshHfCap() }.show()
                }
            }
            .show()
    }

    private fun probeHf() {
        io.execute {
            val reason = Storages.hfDisabledReason()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                hfReason = reason
                if (reason == null) {
                    hfSub.text = hfCapText()
                    refreshHfCap()
                    hfCard.alpha = 1f
                } else {
                    hfSub.text = "❌ $reason"
                    hfCard.alpha = 0.5f
                }
            }
        }
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(
                    "🎬 Kholne ka tarika: " + Opener.labels[Opener.mode(this)],
                    "🔄 USB / SD card dobara scan karo",
                    "🚪 Logout / server badlo"
                )
            ) { _, i ->
                when (i) {
                    0 -> pickOpenMode()
                    1 -> {
                        VolumeWatcher.rescanSoon()
                        Toast.makeText(this, "Scan ho raha hai…", Toast.LENGTH_SHORT).show()
                    }
                    else -> logout()
                }
            }
            .show()
    }

    private fun pickOpenMode() {
        AlertDialog.Builder(this)
            .setTitle("Photo / video / music kaise khole?")
            .setSingleChoiceItems(Opener.labels, Opener.mode(this)) { d, which ->
                Opener.setMode(this, which)
                d.dismiss()
            }
            .show()
    }

    private fun logout() {
        stopService(Intent(this, PlaybackService::class.java))
        Session.clearPass(this)
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
