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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Login ke baad ka pehla screen: do storage (AIC Disk, HF Bucket) + Transfers. */
class HomeActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var aicSub: TextView
    private lateinit var hfSub: TextView
    private lateinit var hfCard: LinearLayout
    private lateinit var hf2Sub: TextView
    private lateinit var hf2Card: LinearLayout
    private lateinit var clipTv: TextView
    private lateinit var localSub: TextView
    private lateinit var volBox: LinearLayout
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val volCb: () -> Unit = { refreshSd() }

    // live capacity (har card par % khaali + free + patti)
    private lateinit var capAic: CapHolder
    private lateinit var capHf: CapHolder
    private lateinit var capHf2: CapHolder
    private lateinit var capLocal: CapHolder
    private val volCaps = HashMap<String, CapHolder>()
    private lateinit var homeSwipe: SwipeRefreshLayout
    private var hfProbed = false
    private var hf2Probed = false
    private var tickN = 0
    private val liveTask = object : Runnable {
        override fun run() {
            updateCaps(false)
            uiHandler.postDelayed(this, 3000)
        }
    }

    private inner class CapHolder(val tv: TextView, val barBox: LinearLayout, val headTv: TextView?) {
        fun set(c: CapLive.Cap?, extra: String = "") {
            barBox.removeAllViews()
            if (c == null) {
                headTv?.visibility = View.GONE
                tv.text = extra
                tv.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
                return
            }
            if (headTv != null) {
                headTv.text = c.head()
                headTv.visibility = View.VISIBLE
                tv.text = c.detail() + extra
            } else {
                tv.text = c.head() + " · " + c.detail() + extra
            }
            tv.visibility = View.VISIBLE
            barBox.addView(usageBar(c.usedFrac))
        }
    }

    private fun addCap(card: LinearLayout): CapHolder {
        val col = card.getChildAt(1) as LinearLayout
        val tv = TextView(this)
        tv.setTextColor(0xFF444444.toInt())
        tv.textSize = 10.5f
        tv.maxLines = 2
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setPadding(0, dp(1), 0, dp(2))
        tv.visibility = View.GONE
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        col.addView(tv)
        col.addView(box, LinearLayout.LayoutParams(MATCH, WRAP))
        return CapHolder(tv, box, card.tag as? TextView)
    }

    private val storagePerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            refreshLocal()
            if (ok) open(Store.LOCAL)
        }

    /** null = HF theek (ya abhi pata nahi); String = HF band hai, ye sabab. */
    private var hfReason: String? = null

    /** HF2 ke liye bhi wahi: null = theek ya pata nahi; String = band hai, ye sabab. */
    private var hf2Reason: String? = null

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
        probeHf2()
        updateHfCap()
        VolumeWatcher.addListener(volCb)
        VolumeWatcher.rescanSoon()
        tickN = 0
        uiHandler.removeCallbacks(liveTask)
        uiHandler.post(liveTask)
    }

    override fun onPause() {
        super.onPause()
        VolumeWatcher.removeListener(volCb)
        uiHandler.removeCallbacksAndMessages(null)
        CapLive.cancelScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun buildUi() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(4), dp(12), dp(8))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = if (intent.getBooleanExtra("pick", false)) "📎 File chuno" else "📁 File Manager"
        title.textSize = 20f
        title.setTextColor(Color.BLACK)
        title.typeface = Typeface.DEFAULT_BOLD
        title.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val more = TextView(this)
        more.text = "⋮"
        more.textSize = 24f
        more.setTextColor(Color.BLACK)
        more.gravity = Gravity.CENTER
        more.layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
        more.setOnClickListener { showMenu() }
        top.addView(title)
        top.addView(more)
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val host = Session.baseUrl.toHttpUrlOrNull()?.host ?: ""

        aicSub = TextView(this)
        val aicCard = card(Store.AIC, aicSub) { open(Store.AIC) }
        aicSub.text = "VPS ki disk (/data) · $host"
        capAic = addCap(aicCard)

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
        capHf = addCap(hfCard)

        hf2Sub = TextView(this)
        hf2Card = card(Store.HF2, hf2Sub) {
            val r = hf2Reason
            if (r != null) {
                Toast.makeText(this, "❌ $r", Toast.LENGTH_LONG).show()
            } else {
                open(Store.HF2)
            }
        }
        hf2Sub.text = "Check ho raha hai…"
        capHf2 = addCap(hf2Card)

        localSub = TextView(this)
        val localCard = card(Store.LOCAL, localSub) { openLocal() }
        capLocal = addCap(localCard)

        col.addView(aicCard)
        col.addView(hfCard)
        col.addView(hf2Card)
        col.addView(localCard)

        volBox = LinearLayout(this)                        // SD card / pendrive lagi ho tabhi cards dikhte hain
        volBox.orientation = LinearLayout.VERTICAL
        col.addView(volBox, LinearLayout.LayoutParams(MATCH, WRAP))

        clipTv = TextView(this)
        clipTv.setTextColor(0xFFE65100.toInt())
        clipTv.textSize = 13f
        clipTv.setPadding(dp(4), dp(8), dp(4), dp(8))
        col.addView(clipTv, LinearLayout.LayoutParams(MATCH, WRAP))

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        val tr = Button(this)
        tr.text = "📊 Transfers"
        tr.textSize = 12f
        tr.setOnClickListener { startActivity(Intent(this, TransfersActivity::class.java)) }
        btnRow.addView(tr, LinearLayout.LayoutParams(0, dp(44), 1f))
        val an = Button(this)
        an.text = "🔍 Analyze (kaun si folder kitni jagah)"
        an.textSize = 12f
        an.setOnClickListener { pickAnalyze() }
        btnRow.addView(an, LinearLayout.LayoutParams(0, dp(44), 1.6f))
        col.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val dupBtn = Button(this)
        dupBtn.text = "🧬 Duplicate dhundo (video / music / image)"
        dupBtn.textSize = 12f
        dupBtn.setOnClickListener { pickDup() }
        col.addView(dupBtn, LinearLayout.LayoutParams(MATCH, dp(44)))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.WHITE)
        scroll.isFillViewport = true
        scroll.addView(col)
        homeSwipe = SwipeRefreshLayout(this)
        homeSwipe.addView(scroll, ViewGroup.LayoutParams(MATCH, MATCH))
        homeSwipe.setOnRefreshListener { forceRefresh() }
        setContentView(homeSwipe)
    }

    private fun card(store: Store, sub: TextView, onClick: () -> Unit): LinearLayout =
        card(store.icon, store.label, sub, onClick)

    private fun card(iconText: String, labelText: String, sub: TextView, onClick: () -> Unit): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(12), dp(7), dp(12), dp(7))
        val bg = GradientDrawable()
        bg.setColor(0xFFF0F0F0.toInt())
        bg.cornerRadius = dp(12).toFloat()
        c.background = bg
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(5)
        c.layoutParams = lp

        val icon = TextView(this)
        icon.text = iconText
        icon.textSize = 26f
        icon.setPadding(0, 0, dp(10), 0)

        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val name = TextView(this)
        name.text = labelText
        name.textSize = 14.5f
        name.setTextColor(Color.BLACK)
        name.typeface = Typeface.DEFAULT_BOLD
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val headTv = TextView(this)
        headTv.textSize = 11f
        headTv.setTextColor(Color.BLACK)
        headTv.typeface = Typeface.DEFAULT_BOLD
        headTv.maxLines = 1
        headTv.gravity = Gravity.END
        headTv.setPadding(dp(6), 0, 0, 0)
        headTv.visibility = View.GONE
        c.tag = headTv
        val nameRow = LinearLayout(this)
        nameRow.orientation = LinearLayout.HORIZONTAL
        nameRow.gravity = Gravity.CENTER_VERTICAL
        nameRow.addView(name)
        nameRow.addView(headTv)
        sub.setTextColor(0xFF333333.toInt())
        sub.textSize = 10.5f
        sub.maxLines = 1
        sub.ellipsize = android.text.TextUtils.TruncateAt.END
        txt.addView(nameRow, LinearLayout.LayoutParams(MATCH, WRAP))
        txt.addView(sub)

        c.addView(icon)
        c.addView(txt)
        c.setOnClickListener { onClick() }
        return c
    }

    private fun refreshSd() {
        if (!::volBox.isInitialized) return
        volBox.removeAllViews()
        volCaps.clear()
        val vols = VolumeWatcher.snapshot
        val hasAccess = LocalFs.hasAccess(this)
        for (v in vols) {
            val sub = TextView(this)
            if (!v.mounted) {
                sub.text = v.kind + " · " + v.stateText()
            } else if (!hasAccess) {
                sub.text = "${v.kind} · permission chahiye (dabao)"
            } else {
                var line = v.kind + (if (v.fs == "?") "" else " · " + v.fs)
                if (v.readOnly) line += " · sirf padh sakte hain"
                else if (!v.writable) line += " · likhna band (Android restriction)"
                sub.text = line
            }
            val c = card(v.icon, v.label, sub) { openVol(v) }
            if (v.mounted && hasAccess) {
                val h = addCap(c)
                volCaps[v.root.absolutePath] = h
                h.set(CapLive.local(v.root))
            }
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
        val lp = LinearLayout.LayoutParams(MATCH, dp(4))
        lp.topMargin = dp(3)
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
        localSub.text = if (LocalFs.hasAccess(this)) "Phone ki files" else "Phone ki files · permission chahiye (dabao)"
        capLocal.set(CapLive.local(LocalFs.rootDir()))
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

    private fun open(store: Store, localRoot: String? = null, label: String? = null, icon: String? = null, start: String? = null) {
        val i = Intent(this, BrowserActivity::class.java).putExtra("store", store.id)
        if (localRoot != null) i.putExtra("local_root", localRoot)
        if (label != null) i.putExtra("local_label", label)
        if (icon != null) i.putExtra("local_icon", icon)
        if (start != null) i.putExtra("start", start)
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

    /** HF card ki capacity: server quota ya apne aap hua scan; kuch nahi to sirf 100 GB limit. */
    private fun updateHfCap() {
        if (!::capHf.isInitialized || hfReason != null) return
        val c = CapLive.hfCached(this)
        val scanning = CapLive.hfScanning(Store.HF)
        if (c == null) {
            capHf.set(null, "Capacity " + fmtSize(Analyzer.HF_CAPACITY) + (if (scanning) " · ⟳ use nikaal raha hu…" else ""))
        } else {
            capHf.set(c, if (scanning) " · ⟳ refresh ho raha hai…" else "")
        }
    }

    /** HF2 card ki capacity (HF jaisi hi). */
    private fun updateHf2Cap() {
        if (!::capHf2.isInitialized || hf2Reason != null) return
        val c = CapLive.hfCached(this, Store.HF2)
        val scanning = CapLive.hfScanning(Store.HF2)
        if (c == null) {
            capHf2.set(null, "Capacity " + fmtSize(Analyzer.HF_CAPACITY) + (if (scanning) " · ⟳ use nikaal raha hu…" else ""))
        } else {
            capHf2.set(c, if (scanning) " · ⟳ refresh ho raha hai…" else "")
        }
    }

    private fun refreshHfCap() { updateHfCap(); updateHf2Cap() }

    /** Har ~3 sec: phone + SD/USB seedha; AIC har ~9 sec; HF apne aap (quota ya background scan). */
    private fun updateCaps(force: Boolean) {
        if (!::capLocal.isInitialized) return
        capLocal.set(CapLive.local(LocalFs.rootDir()))
        for ((root, h) in volCaps) h.set(CapLive.local(File(root)))
        if (force || tickN % 3 == 0) {
            io.execute {
                val c = CapLive.aic()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && c != null) capAic.set(c)
                }
            }
        }
        if (hfProbed && hfReason == null) {
            updateHfCap()
            if (force || tickN % 3 == 0) {
                CapLive.hfTick(applicationContext, Store.HF, force) {
                    if (!isFinishing && !isDestroyed) updateHfCap()
                }
            }
        }
        if (hf2Probed && hf2Reason == null) {
            updateHf2Cap()
            if (force || tickN % 3 == 0) {
                CapLive.hfTick(applicationContext, Store.HF2, force) {
                    if (!isFinishing && !isDestroyed) updateHf2Cap()
                }
            }
        }
        tickN++
    }

    /** Neeche kheench kar refresh: sab kuch abhi dobara (HF ka scan bhi). */
    private fun forceRefresh() {
        VolumeWatcher.rescanSoon()
        refreshLocal()
        probeHf()
        probeHf2()
        updateCaps(true)
        uiHandler.postDelayed({ if (::homeSwipe.isInitialized) homeSwipe.isRefreshing = false }, 900)
    }

    // Favourites ab Home par nahi dikhte — sirf us folder ke andar, jahan se chune the
    // (folder me ⋮ → "Is folder ke Favourites").

    /** Kis storage ka analyze karna hai: AIC / HF / Phone / SD / USB. */
    private fun pickAnalyze() {
        class Opt(val label: String, val store: Store, val root: String)
        val opts = ArrayList<Opt>()
        opts.add(Opt(Store.AIC.icon + " " + Store.AIC.label, Store.AIC, Dav.withSlash(Storages.root(Store.AIC))))
        opts.add(Opt(Store.HF.icon + " " + Store.HF.label, Store.HF, Dav.withSlash(Storages.root(Store.HF))))
        opts.add(Opt(Store.HF2.icon + " " + Store.HF2.label, Store.HF2, Dav.withSlash(Storages.root(Store.HF2))))
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
                } else if (o.store == Store.HF2 && hf2Reason != null) {
                    Toast.makeText(this, "❌ " + hf2Reason, Toast.LENGTH_LONG).show()
                } else {
                    AnalyzeUi(this, o.store, o.root, o.label) { refreshHfCap() }.show()
                }
            }
            .show()
    }

    private fun pickDup() {
        class Opt(val label: String, val store: Store, val root: String)
        val opts = ArrayList<Opt>()
        opts.add(Opt(Store.AIC.icon + " " + Store.AIC.label, Store.AIC, Dav.withSlash(Storages.root(Store.AIC))))
        opts.add(Opt(Store.HF.icon + " " + Store.HF.label, Store.HF, Dav.withSlash(Storages.root(Store.HF))))
        opts.add(Opt(Store.HF2.icon + " " + Store.HF2.label, Store.HF2, Dav.withSlash(Storages.root(Store.HF2))))
        if (LocalFs.hasAccess(this)) {
            opts.add(Opt(Store.LOCAL.icon + " " + Store.LOCAL.label, Store.LOCAL, Dav.withSlash(LocalFs.rootUrl())))
            for (v in VolumeWatcher.snapshot) {
                if (v.mounted) opts.add(Opt(v.icon + " " + v.label, Store.LOCAL, LocalFs.urlOf(v.root, true)))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Kis storage me Duplicate dhundein?")
            .setItems(opts.map { it.label }.toTypedArray()) { _, i ->
                val o = opts[i]
                if (o.store == Store.HF && hfReason != null) {
                    Toast.makeText(this, "❌ " + hfReason, Toast.LENGTH_LONG).show()
                } else if (o.store == Store.HF2 && hf2Reason != null) {
                    Toast.makeText(this, "❌ " + hf2Reason, Toast.LENGTH_LONG).show()
                } else {
                    DupUi(this, o.store, o.label) { refreshHfCap() }.show(o.root)
                }
            }
            .show()
    }

    private fun probeHf2() {
        io.execute {
            val reason = Storages.hfDisabledReason(Store.HF2)
            runOnUiThread {
                if (isFinishing || isDestroyed || !::hf2Sub.isInitialized) return@runOnUiThread
                hf2Reason = reason
                hf2Probed = true
                if (reason == null) {
                    hf2Sub.text = "Hugging Face Storage Bucket 2 (dusra account)"
                    updateHf2Cap()
                    hf2Card.alpha = 1f
                } else {
                    hf2Sub.text = "❌ $reason"
                    capHf2.set(null)
                    hf2Card.alpha = 0.5f
                }
            }
        }
    }

    private fun probeHf() {
        io.execute {
            val reason = Storages.hfDisabledReason()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                hfReason = reason
                hfProbed = true
                if (reason == null) {
                    hfSub.text = "Hugging Face Storage Bucket"
                    updateHfCap()
                    hfCard.alpha = 1f
                } else {
                    hfSub.text = "❌ $reason"
                    capHf.set(null)
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
