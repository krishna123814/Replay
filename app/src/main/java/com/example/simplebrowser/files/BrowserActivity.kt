package com.example.simplebrowser.files

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.IOException
import java.io.File
import android.os.FileObserver
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class BrowserActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var store: Store

    // pick mode: dusri jagah (browser upload) ke liye file chunna; result = FileProvider Uri(s)
    private var pickMode = false
    private var pickMulti = false

    private lateinit var adapter: FileAdapter
    private lateinit var gridLm: GridLayoutManager
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var emptyTv: TextView
    private lateinit var viewBtn: TextView
    private lateinit var header: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteInfo: TextView

    private var rootUrl = ""
    private var localRoot: String? = null        // SD card: Store.LOCAL ka alag root folder
    private var localLabel: String? = null
    private var localIcon: String? = null
    private val storeLabel: String get() = if (localRoot != null) (localLabel ?: "SD Card") else store.label
    private val storeIcon: String get() = if (localRoot != null) (localIcon ?: "💾") else store.icon
    private var currentUrl = ""
    private val stack = ArrayList<String>()
    private var entries: List<Entry> = emptyList()      // folder ki poori listing
    private var shown: List<Entry> = emptyList()        // jo abhi screen par hai (search ke baad)

    // search
    private lateinit var searchBar: LinearLayout
    private lateinit var searchEt: EditText
    private lateinit var deepCb: CheckBox
    private lateinit var searchBtn: TextView
    private var searching = false
    private var query = ""
    private var deep = false
    @Volatile private var deepResults: List<Entry> = emptyList()
    private var deepRunning = false
    private var deepCancel = AtomicBoolean(false)
    private val searchTask = Runnable { onQueryChanged() }

    // favourites
    private lateinit var favBtn: TextView
    private var favSet: Set<String> = emptySet()

    // live capacity + live refresh
    private lateinit var capTv: TextView
    private var observer: FileObserver? = null
    private val obsTask = Runnable { silentRefresh() }
    private var lastSig = 0L
    private val liveTask = object : Runnable {
        override fun run() {
            silentRefresh()
            refreshCap()
            ui.postDelayed(this, livePeriod())
        }
    }
    private var loadToken = 0

    private var gridMode = false
    private var sortMode = 0
    private var hideDots = false
    private var videoThumbs = true

    // multi-select
    private var selecting = false
    private val selected = LinkedHashMap<String, Entry>()

    // transfer progress dialog
    private var progDlg: AlertDialog? = null
    private var progMsg: TextView? = null
    private var progBar: ProgressBar? = null

    private val sortNames = arrayOf("Naam (A→Z)", "Naam (Z→A)", "Naya pehle", "Purana pehle", "Bada pehle")

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) upload(uris)
        }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Session.hasCreds()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        pickMode = intent.getBooleanExtra("pick", false)
        pickMulti = intent.getBooleanExtra("pick_multi", false)
        store = Store.from(intent.getStringExtra("store"))
        if (store == Store.LOCAL && !LocalFs.hasAccess(this)) {
            Toast.makeText(this, "Phone storage ki permission nahi hai — Home se Phone Storage kholo", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        localRoot = intent.getStringExtra("local_root")?.takeIf { it.isNotEmpty() && store == Store.LOCAL }
        localLabel = intent.getStringExtra("local_label")
        localIcon = intent.getStringExtra("local_icon")
        val lr = localRoot
        rootUrl = if (lr != null) Dav.withSlash(LocalFs.urlOf(File(lr), true)) else Dav.withSlash(Session.rootFor(store))
        gridMode = Session.bool(this, "grid", false)
        sortMode = Session.int(this, "sort", 0).coerceIn(0, sortNames.size - 1)
        videoThumbs = Session.bool(this, "vthumbs", true)
        hideDots = Session.bool(this, "hidedots", false)

        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) exitSelect() else goBack()
            }
        })

        val saved = savedInstanceState?.getString("cur")
        val savedStack = savedInstanceState?.getStringArrayList("stack")
        if (savedStack != null) stack.addAll(savedStack)
        val start = intent.getStringExtra("start")?.takeIf { it.isNotEmpty() }
        load(saved ?: start ?: rootUrl, false)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

    }

    /** Ye File Manager kisi SD / USB drive par khula hai: drive nikal jaye to band karo. */
    private val volCb: () -> Unit = {
        val lr = localRoot
        if (lr != null && !isFinishing) {
            val cur = VolumeWatcher.snapshot.firstOrNull { it.root.absolutePath == lr }
            if (cur == null || !cur.mounted) {
                Toast.makeText(this, "⏏ ${localLabel ?: "Drive"} nikal gayi / unmount ho gayi", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (localRoot != null) {
            VolumeWatcher.addListener(volCb)
            VolumeWatcher.rescanSoon()
        }
        if (::pasteBar.isInitialized) refreshPasteBar()
        if (Queue.dirty && ::swipe.isInitialized) {
            Queue.dirty = false
            reload()
        }
        if (::swipe.isInitialized) {
            favSet = Favs.keys(this)
            adapter.notifyDataSetChanged()
            updateFavBtn()
            startObserver()
            ui.removeCallbacks(liveTask)
            ui.postDelayed(liveTask, 800)
        }
    }

    override fun onPause() {
        super.onPause()
        VolumeWatcher.removeListener(volCb)
        ui.removeCallbacks(liveTask)
        stopObserver()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("cur", currentUrl)
        outState.putStringArrayList("stack", ArrayList(stack))
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        deepCancel.set(true)
        stopObserver()
        closeProgress()
        io.shutdown()
    }

    // ───────────────────────── UI ─────────────────────────

    private fun iconBtn(t: String, size: Int = 48, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = t
        b.textSize = 22f
        b.setTextColor(Color.BLACK)
        b.gravity = Gravity.CENTER
        b.layoutParams = LinearLayout.LayoutParams(dp(size), dp(48))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun storeColor(): Int = when (store) {
        Store.HF -> 0xFFEF6C00.toInt()
        Store.HF2 -> 0xFF6A1B9A.toInt()
        Store.LOCAL -> 0xFF2E7D32.toInt()
        else -> 0xFF1565C0.toInt()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(0xFFF5F5F5.toInt())
        header.setPadding(dp(4), 0, dp(4), 0)

        val back = iconBtn("←") { goBack() }

        // storage badge: dabane par doosre storage me jaa sakte ho (clipboard saath rehta hai)
        val badge = TextView(this)
        badge.text = storeIcon + " " + (if (localRoot != null) (if (localIcon == "🔌") "USB" else "SD") else store.short)
        badge.textSize = 12f
        badge.setTextColor(Color.WHITE)
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.gravity = Gravity.CENTER
        badge.setPadding(dp(8), dp(3), dp(8), dp(3))
        val bbg = GradientDrawable()
        bbg.setColor(storeColor())
        bbg.cornerRadius = dp(10).toFloat()
        badge.background = bbg
        val blp = LinearLayout.LayoutParams(WRAP, WRAP)
        blp.leftMargin = dp(2)
        badge.layoutParams = blp
        badge.setOnClickListener { switchStorage() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        col.setPadding(dp(8), dp(8), dp(4), dp(8))

        titleTv = TextView(this)
        titleTv.setTextColor(Color.BLACK)
        titleTv.textSize = 17f
        titleTv.typeface = Typeface.DEFAULT_BOLD
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END

        subTv = TextView(this)
        subTv.setTextColor(Color.BLACK)
        subTv.textSize = 12f
        subTv.maxLines = 1
        subTv.ellipsize = TextUtils.TruncateAt.END

        capTv = TextView(this)
        capTv.setTextColor(Color.BLACK)
        capTv.textSize = 11f
        capTv.maxLines = 1
        capTv.ellipsize = TextUtils.TruncateAt.END
        capTv.visibility = View.GONE

        col.addView(titleTv)
        col.addView(subTv)
        col.addView(capTv)

        viewBtn = iconBtn(if (gridMode) "☰" else "▦", 40) { toggleView() }
        val more = iconBtn("⋮", 40) { showMenu() }
        searchBtn = iconBtn("🔍", 40) { if (searching) closeSearch() else openSearch() }
        favBtn = iconBtn("☆", 40) { toggleCurrentFav() }

        header.addView(back)
        header.addView(badge)
        header.addView(col)
        header.addView(searchBtn)
        header.addView(viewBtn)
        header.addView(more)

        // selection bar (long-press se khulta hai, header ki jagah)
        selBar = LinearLayout(this)
        selBar.orientation = LinearLayout.HORIZONTAL
        selBar.gravity = Gravity.CENTER_VERTICAL
        selBar.setBackgroundColor(0xFFFFE082.toInt())
        selBar.setPadding(dp(4), 0, dp(4), 0)
        selBar.visibility = View.GONE
        selCount = TextView(this)
        selCount.setTextColor(Color.BLACK)
        selCount.textSize = 16f
        selCount.typeface = Typeface.DEFAULT_BOLD
        selCount.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        selBar.addView(iconBtn("✕", 44) { exitSelect() })
        selBar.addView(selCount)
        selBar.addView(iconBtn("☑", 44) { selectAll() })
        if (pickMode) {
            selBar.addView(iconBtn("✔ Bhejo", 90) { finishPick(selected.values.filter { !it.isDir }) }.also { it.textSize = 14f })
        } else {
            selBar.addView(iconBtn("📋", 44) { doClip(false) })
            selBar.addView(iconBtn("✂", 44) { doClip(true) })
            selBar.addView(iconBtn("🗑", 44) { confirmDelete(selected.values.toList()) })
            selBar.addView(iconBtn("⋮", 44) { selMore() })
        }

        adapter = FileAdapter(this, { e -> onOpen(e) }, { e -> onLongPress(e) })
        applyThumbFlag()
        gridLm = GridLayoutManager(this, 1)
        rv = RecyclerView(this)
        rv.layoutManager = gridLm
        rv.adapter = adapter
        adapter.selected = selected.keys
        favSet = Favs.keys(this)
        adapter.isFav = { e -> favSet.isNotEmpty() && pathKey(e) in favSet }

        swipe = SwipeRefreshLayout(this)
        swipe.setColorSchemeColors(0xFFEF6C00.toInt())
        swipe.setProgressBackgroundColorSchemeColor(Color.WHITE)
        swipe.setOnRefreshListener { load(if (currentUrl.isEmpty()) rootUrl else currentUrl, false) }
        swipe.addView(rv, ViewGroup.LayoutParams(MATCH, MATCH))

        emptyTv = TextView(this)
        emptyTv.setTextColor(Color.BLACK)
        emptyTv.gravity = Gravity.CENTER
        emptyTv.setPadding(dp(24), dp(24), dp(24), dp(24))
        emptyTv.visibility = View.GONE

        val body = FrameLayout(this)
        body.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        body.addView(swipe, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(emptyTv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // paste bar (clipboard me kuch ho to neeche hamesha dikhta hai)
        pasteBar = LinearLayout(this)
        pasteBar.orientation = LinearLayout.HORIZONTAL
        pasteBar.gravity = Gravity.CENTER_VERTICAL
        pasteBar.setBackgroundColor(0xFFEEEEEE.toInt())
        pasteBar.setPadding(dp(12), dp(6), dp(8), dp(6))
        pasteBar.visibility = View.GONE
        pasteInfo = TextView(this)
        pasteInfo.setTextColor(Color.BLACK)
        pasteInfo.textSize = 13f
        pasteInfo.maxLines = 2
        pasteInfo.ellipsize = TextUtils.TruncateAt.END
        pasteInfo.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val pasteBtn = Button(this)
        pasteBtn.text = "Paste here"
        pasteBtn.setOnClickListener { paste() }
        val cancelBtn = Button(this)
        cancelBtn.text = "Cancel"
        cancelBtn.setOnClickListener {
            Session.clearClip()
            refreshPasteBar()
        }
        pasteBar.addView(pasteInfo)
        pasteBar.addView(pasteBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        pasteBar.addView(cancelBtn, LinearLayout.LayoutParams(WRAP, WRAP))

        buildSearchBar()
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(selBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(searchBar, LinearLayout.LayoutParams(MATCH, WRAP))
        if (pickMode) {
            val banner = TextView(this)
            banner.text = if (pickMulti) "📎 Upload ke liye files chuno (ek par tap, phir aur tap karke ✔ Bhejo)"
            else "📎 Upload ke liye file par tap karo"
            banner.setTextColor(Color.BLACK)
            banner.textSize = 13f
            banner.setBackgroundColor(0xFFFFE082.toInt())
            banner.setPadding(dp(12), dp(8), dp(12), dp(8))
            root.addView(banner, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(body)
        root.addView(pasteBar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        applyViewMode()
        refreshPasteBar()
    }

    /** HF par video thumbnails band: MediaMetadataRetriever Authorization header redirect ke baad CDN par bhi bhej sakta hai. */
    private fun applyThumbFlag() {
        adapter.videoThumbs = videoThumbs && !store.isHf
    }

    private fun spanCount(): Int =
        if (gridMode) max(3, resources.configuration.screenWidthDp / 120) else 1

    private fun applyViewMode() {
        adapter.grid = gridMode
        gridLm.spanCount = spanCount()
        adapter.thumbPx = if (gridMode) resources.displayMetrics.widthPixels / spanCount() else dp(56)
        viewBtn.text = if (gridMode) "☰" else "▦"
        adapter.notifyDataSetChanged()
    }

    private fun toggleView() {
        gridMode = !gridMode
        Session.setBool(this, "grid", gridMode)
        applyViewMode()
    }

    private fun updateHeader() {
        titleTv.text = if (currentUrl == rootUrl) storeLabel
        else Uri.decode(currentUrl.trimEnd('/').substringAfterLast('/'))
        val src = shown
        val folders = src.count { it.isDir }
        subTv.text = if (searching && query.trim().isNotEmpty())
            "🔍 ${src.size} mile" + (if (deepRunning) " · dhundh raha hu…" else "")
        else "$storeIcon $storeLabel · $folders folders · ${src.size - folders} files"
        updateFavBtn()
    }

    // ───────────────────────── loading ─────────────────────────

    private fun sorted(l: List<Entry>): List<Entry> {
        val cmp: Comparator<Entry> = when (sortMode) {
            1 -> Comparator { a, b -> natural(b.name, a.name) }
            2 -> Comparator { a, b -> b.modified.compareTo(a.modified) }
            3 -> Comparator { a, b -> a.modified.compareTo(b.modified) }
            4 -> Comparator { a, b -> b.size.compareTo(a.size) }
            else -> Comparator { a, b -> natural(a.name, b.name) }
        }
        return l.sortedWith(compareBy<Entry> { !it.isDir }.then(cmp))
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: "error"
        return if (store.isHf && m.contains("503")) store.label + " server par band / configured nahi hai (503)" else m
    }

    private fun load(url: String, push: Boolean) {
        val token = ++loadToken
        swipe.isRefreshing = true
        emptyTv.visibility = View.GONE
        io.execute {
            try {
                val list = Fs.list(url)
                runOnUiThread {
                    if (token != loadToken || isFinishing) return@runOnUiThread
                    val changed = url != currentUrl
                    if (push && currentUrl.isNotEmpty() && changed) stack.add(currentUrl)
                    currentUrl = url
                    if (selecting) exitSelect(false)
                    entries = sorted(list)
                    lastSig = sigOf(entries)
                    if (changed && searching) resetSearch()
                    applyFilter()
                    if (changed) rv.scrollToPosition(0)
                    swipe.isRefreshing = false
                    updateHeader()
                    startObserver()
                    refreshCap()
                }
            } catch (e: DavAuthException) {
                runOnUiThread {
                    Toast.makeText(this, "Login galat / expire — dobara login karo", Toast.LENGTH_LONG).show()
                    Session.clearPass(this)
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (token != loadToken || isFinishing) return@runOnUiThread
                    swipe.isRefreshing = false
                    val msg = friendly(e)
                    Toast.makeText(this, "Error: $msg", Toast.LENGTH_LONG).show()
                    if (entries.isEmpty()) {
                        emptyTv.text = "Load nahi hua\n$msg\n\nNeeche kheench kar refresh karo"
                        emptyTv.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun goBack() {
        if (searching) {
            closeSearch()
            return
        }
        if (stack.isNotEmpty()) {
            val prev = stack.removeAt(stack.size - 1)
            load(prev, false)
        } else {
            finish()
        }
    }

    private fun reload() = load(if (currentUrl.isEmpty()) rootUrl else currentUrl, false)

    // ───────────────────────── open / play ─────────────────────────

    private fun onOpen(e: Entry) {
        if (pickMode) {
            when {
                e.isDir -> load(e.url, true)
                pickMulti -> if (selecting) toggle(e) else enterSelect(e)
                else -> finishPick(listOf(e))
            }
            return
        }
        if (selecting) {
            toggle(e)
            return
        }
        when (e.kind) {
            Kind.DIR -> load(e.url, true)
            Kind.IMAGE, Kind.VIDEO, Kind.AUDIO -> when (Opener.mode(this)) {
                Opener.MODE_EXTERNAL -> Opener.external(this, e)
                Opener.MODE_ASK -> {
                    val what = when (e.kind) {
                        Kind.IMAGE -> "viewer"
                        else -> "player"
                    }
                    AlertDialog.Builder(this)
                        .setTitle(e.name)
                        .setItems(arrayOf("📱 MyFiles ka $what", "↗ Dusre app me kholo")) { _, i ->
                            if (i == 0) openInApp(e) else Opener.external(this, e)
                        }
                        .show()
                }
                else -> openInApp(e)
            }
            Kind.OTHER -> {
                if (store == Store.LOCAL) {
                    Opener.external(this, e)
                    return
                }
                AlertDialog.Builder(this)
                    .setTitle(e.name)
                    .setItems(arrayOf("⬇ Download", "↗ Dusre app me kholo")) { _, i ->
                        if (i == 0) download(e) else Opener.external(this, e)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** MyFiles ka apna viewer / player. */
    private fun openInApp(e: Entry) {
        when (e.kind) {
            Kind.IMAGE -> {
                val imgs = shown.filter { it.kind == Kind.IMAGE }
                Queue.images = imgs
                val i = imgs.indexOfFirst { it.url == e.url }.coerceAtLeast(0)
                startActivity(Intent(this, ImageViewerActivity::class.java).putExtra("index", i))
            }
            Kind.VIDEO, Kind.AUDIO -> {
                val same = shown.filter { it.kind == e.kind }
                Queue.media = same
                Queue.mediaIndex = same.indexOfFirst { it.url == e.url }.coerceAtLeast(0)
                Queue.subs = if (e.kind == Kind.VIDEO) findSubtitles(same) else emptyMap()
                Queue.pending = true
                startActivity(
                    Intent(this, PlayerActivity::class.java).putExtra("video", e.kind == Kind.VIDEO)
                )
            }
            else -> {}
        }
    }

    /** Video ke naam wali .srt / .vtt / .ass usi folder me ho to jod do. */
    private fun findSubtitles(videos: List<Entry>): Map<String, String> {
        val exts = arrayOf("srt", "vtt", "ass", "ssa")
        val byName = HashMap<String, Entry>()
        for (x in entries) if (!x.isDir) byName[x.name.lowercase()] = x
        val out = HashMap<String, String>()
        for (v in videos) {
            val base = v.name.substringBeforeLast('.').lowercase()
            for (ext in exts) {
                val sub = byName["$base.$ext"]
                if (sub != null) {
                    out[v.url] = sub.url
                    break
                }
            }
        }
        return out
    }

    // ───────────────────────── multi-select ─────────────────────────

    private fun onLongPress(e: Entry) {
        if (pickMode && (!pickMulti || e.isDir)) return
        if (selecting) toggle(e) else enterSelect(e)
    }

    private fun enterSelect(e: Entry) {
        selecting = true
        selected.clear()
        selected[e.url] = e
        refreshSelUi(null)
    }

    private fun toggle(e: Entry) {
        if (selected.remove(e.url) == null) selected[e.url] = e
        if (selected.isEmpty()) exitSelect() else refreshSelUi(e.url)
    }

    private fun exitSelect(redraw: Boolean = true) {
        selecting = false
        selected.clear()
        if (redraw) refreshSelUi(null) else applySelVisibility()
    }

    private fun selectAll() {
        for (e in shown) if (!(pickMode && e.isDir)) selected[e.url] = e
        refreshSelUi(null)
    }

    private fun applySelVisibility() {
        header.visibility = if (selecting) View.GONE else View.VISIBLE
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        swipe.isEnabled = !selecting
        adapter.selecting = selecting
    }

    private fun refreshSelUi(changedUrl: String?) {
        applySelVisibility()
        selCount.text = "${selected.size} chune"
        if (changedUrl != null) {
            val i = shown.indexOfFirst { it.url == changedUrl }
            if (i >= 0) adapter.notifyItemChanged(i) else adapter.notifyDataSetChanged()
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    private fun selMore() {
        if (selected.isEmpty()) return
        val list = selected.values.toList()
        val allFav = list.all { pathKey(it) in favSet }
        val favLabel = if (allFav) "☆ Favourite se hatao" else "⭐ Favourite me jodo"
        if (list.size != 1) {
            AlertDialog.Builder(this)
                .setTitle("${list.size} item chune")
                .setItems(arrayOf("$favLabel (${list.size})")) { _, _ -> setFavs(list, !allFav) }
                .show()
            return
        }
        val e = list[0]
        val opts = ArrayList<String>()
        opts.add(favLabel)
        if (!e.isDir && store != Store.LOCAL) opts.add(optDownload)
        if (!e.isDir) opts.add(optOpenWith)
        opts.add(optRename)
        opts.add(optInfo)
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(opts.toTypedArray()) { _, i ->
                when (opts[i]) {
                    favLabel -> setFavs(list, !allFav)
                    optDownload -> download(e)
                    optOpenWith -> Opener.external(this, e)
                    optRename -> rename(e)
                    optInfo -> info(e)
                }
            }
            .show()
    }

    private val optDownload = "⬇ Download"
    private val optOpenWith = "📂 Dusre app me kholo"
    private val optRename = "✏ Rename"
    private val optInfo = "ℹ Info"

    private fun info(e: Entry) {
        val msg = "Naam: ${e.name}\n" +
            "Storage: ${storeLabel}\n" +
            (if (e.isDir) "Type: Folder\n" else "Size: ${fmtSize(e.size)}\n") +
            "Badla: ${fmtDate(e.modified)}\n\n${Storages.pathOf(store, e.url)}"
        AlertDialog.Builder(this).setTitle("Info").setMessage(msg).setPositiveButton("OK", null).show()
    }

    /** Chuni hui file(s) ko FileProvider Uri bana ke caller ko lauta do. Server ki file pehle cache me utarti hai. */
    private fun finishPick(list: List<Entry>) {
        if (list.isEmpty()) return
        var cancelled = false
        val dlg = AlertDialog.Builder(this)
            .setTitle("File taiyar ho rahi hai")
            .setMessage(list[0].name)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
            .create()
        dlg.show()
        io.execute {
            try {
                val root = File(cacheDir, "pick")
                val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
                root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
                val stamp = System.currentTimeMillis()
                val uris = ArrayList<Uri>()
                var lastUi = 0L
                for ((i, e) in list.withIndex()) {
                    if (cancelled) throw java.io.IOException("cancel")
                    val f: File = if (LocalFs.isLocal(e.url)) {
                        LocalFs.fileOf(e.url)
                    } else {
                        Opener.downloadToCache(this, e, "pick/${stamp}_$i", { done ->
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 400) {
                                lastUi = now
                                val pct = if (e.size > 0) " " + (done * 100 / e.size) + "%" else " " + (done / 1048576) + " MB"
                                ui.post { dlg.setMessage("${i + 1}/${list.size}  ${e.name}$pct") }
                            }
                        }, { cancelled })
                    }
                    uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
                }
                runOnUiThread {
                    dlg.dismiss()
                    val data = Intent()
                    data.data = uris[0]
                    val cd = ClipData.newRawUri("files", uris[0])
                    for (k in 1 until uris.size) cd.addItem(ClipData.Item(uris[k]))
                    data.clipData = cd
                    data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setResult(RESULT_OK, data)
                    finish()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    if (!cancelled) Toast.makeText(this, "File nahi mili: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun download(e: Entry) {
        Toast.makeText(this, "Download shuru ho raha hai…", Toast.LENGTH_SHORT).show()
        io.execute {
            var url = e.url
            var withAuth = true
            if (store.isHf) {
                // CDN link pehle nikaalo: apna Authorization header CDN par nahi jana chahiye
                val cdn = Storages.cdnTarget(e.url)
                if (cdn != null) {
                    url = cdn
                    withAuth = false
                }
            }
            runOnUiThread {
                try {
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle(e.name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MyFiles/" + e.name)
                    if (withAuth) req.addRequestHeader("Authorization", Session.auth())
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this, "Download shuru → Downloads/MyFiles", Toast.LENGTH_SHORT).show()
                } catch (ex: Exception) {
                    Toast.makeText(this, "Download nahi hua: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ───────────────────────── copy / move / paste ─────────────────────────

    private fun doClip(move: Boolean) {
        if (selected.isEmpty()) return
        val items = selected.values.map { ClipItem(store, Storages.pathOf(store, it.url), it.name, it.isDir, it.size) }
        Session.setClip(items, move)
        val n = items.size
        exitSelect()
        refreshPasteBar()
        Toast.makeText(
            this,
            "$n item ${if (move) "move" else "copy"} ke liye chune — ab destination folder / storage me jaake 'Paste here' dabao",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshPasteBar() {
        val c = Session.clip
        if (c.isEmpty()) {
            pasteBar.visibility = View.GONE
            return
        }
        val mode = if (Session.clipMove) "✂ Move" else "📋 Copy"
        pasteInfo.text = "$mode · ${c.size} item · ${c[0].store.label} se"
        pasteBar.visibility = View.VISIBLE
    }

    private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

    private fun paste() {
        val items = Session.clip
        if (items.isEmpty()) return
        val move = Session.clipMove
        val dest = Storages.pathOf(store, if (currentUrl.isEmpty()) rootUrl else currentUrl)
        if (move && items.all { it.store == store && parentOf(it.path) == dest.trimEnd('/') }) {
            Toast.makeText(this, "Ye items pehle se isi folder me hain", Toast.LENGTH_SHORT).show()
            return
        }
        if (store == Store.LOCAL) {
            val d = dest.trimEnd('/')
            val inside = items.any {
                it.store == Store.LOCAL && it.isDir && (d == it.path.trimEnd('/') || d.startsWith(it.path.trimEnd('/') + "/"))
            }
            if (inside) {
                Toast.makeText(this, "Folder ko usi ke andar paste nahi kar sakte", Toast.LENGTH_LONG).show()
                return
            }
        }
        // Phone <-> server: pehle SAB files compare (folder ke andar tak), phir ek saath faisla.
        if (!LocalJobs.involvesLocal(items, store)) {
            pasteOldWay(items, move, dest)
            return
        }
        swipe.isRefreshing = true
        io.execute {
            val res: ScanResult? = try {
                scanConflicts(items, dest)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                swipe.isRefreshing = false
                when {
                    res == null -> {
                        Toast.makeText(this, "Compare nahi ho paya — purane tareeke se", Toast.LENGTH_SHORT).show()
                        pasteOldWay(items, move, dest)
                    }
                    res.conflicts.isEmpty() -> startXfer(items, move, "skip", dest, null)
                    else -> showConflicts(items, move, dest, res)
                }
            }
        }
    }

    private fun pasteOldWay(items: List<ClipItem>, move: Boolean, dest: String) {
        val names = entries.map { it.name }.toHashSet()
        if (items.any { it.name in names }) {
            askConflict { oc -> startXfer(items, move, oc, dest, null) }
        } else {
            startXfer(items, move, "skip", dest, null)
        }
    }

    // ───────── paste se pehle compare ─────────

    /** Ek pehle se maujood file: rel = "Folder/a.mp4". forceRename = naam ka type alag (file vs folder). */
    private class Conf(val rel: String, val srcSize: Long, val dstSize: Long, val forceRename: Boolean)

    private class ScanResult {
        val conflicts = ArrayList<Conf>()
        var newFiles = 0
        var newBytes = 0L
    }

    private fun scanConflicts(items: List<ClipItem>, dest: String): ScanResult {
        val res = ScanResult()
        val destPath = if (dest.endsWith("/")) dest else "$dest/"
        val destKids = Fs.list(Storages.urlOf(store, destPath, true)).associateBy { it.name }
        for (ci in items) {
            scanItem(res, Storages.urlOf(ci.store, ci.path, ci.isDir), ci.isDir, ci.size, ci.name, destKids[ci.name])
        }
        return res
    }

    private fun scanItem(res: ScanResult, srcUrl: String, isDir: Boolean, size: Long, rel: String, existing: Entry?) {
        if (!isDir) {
            if (existing == null) {
                res.newFiles++
                res.newBytes += size
            } else {
                res.conflicts.add(Conf(rel, size, existing.size, existing.isDir))
            }
            return
        }
        if (existing == null) {
            countNew(res, srcUrl)
            return
        }
        if (!existing.isDir) {
            res.conflicts.add(Conf(rel, 0L, existing.size, true))
            return
        }
        val destKids = Fs.list(existing.url).associateBy { it.name }
        for (k in Fs.list(srcUrl)) {
            scanItem(res, k.url, k.isDir, k.size, rel + "/" + k.name, destKids[k.name])
        }
    }

    private fun countNew(res: ScanResult, srcUrl: String) {
        for (k in Fs.list(srcUrl)) {
            if (res.newFiles > 100000) return
            if (k.isDir) {
                countNew(res, k.url)
            } else {
                res.newFiles++
                res.newBytes += k.size
            }
        }
    }

    private fun showConflicts(items: List<ClipItem>, move: Boolean, dest: String, res: ScanResult) {
        val c = res.conflicts
        val same = c.count { !it.forceRename && it.srcSize == it.dstSize }
        val diff = c.size - same
        val head = TextView(this)
        head.text = "⚠ ${c.size} files pehle se maujood ($same same size, $diff alag size)\n" +
            "${res.newFiles} naye files (${fmtSize(res.newBytes)}) seedha copy honge"
        head.textSize = 15f
        head.setTypeface(null, android.graphics.Typeface.BOLD)
        head.setPadding(dp(20), dp(16), dp(20), dp(8))
        val labels = arrayOf(
            "⏭ Sab skip — sirf naye copy honge",
            "🧠 Same size skip, alag size replace",
            "♻️ Sab replace",
            "📄 Dono rakho (naya naam: name (1))",
            "☑ Ek-ek karke chuno…"
        )
        fun go(f: (Conf) -> String) = startXfer(items, move, "skip", dest, c.associate { it.rel to f(it) })
        AlertDialog.Builder(this)
            .setCustomTitle(head)
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> go { if (it.forceRename) "rename" else "skip" }
                    1 -> go { if (it.forceRename) "rename" else if (it.srcSize == it.dstSize) "skip" else "overwrite" }
                    2 -> go { if (it.forceRename) "rename" else "overwrite" }
                    3 -> go { "rename" }
                    else -> pickConflicts(items, move, dest, res)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickConflicts(items: List<ClipItem>, move: Boolean, dest: String, res: ScanResult) {
        val c = res.conflicts
        val labels = Array(c.size) { i ->
            val x = c[i]
            x.rel + "\n" + if (x.forceRename) "⚠ file / folder ka naam takra raha hai — naya naam milega"
            else "naya: ${fmtSize(x.srcSize)} · yahan pehle se: ${fmtSize(x.dstSize)}" + (if (x.srcSize == x.dstSize) "  ✓ same size" else "  ≠ alag size")
        }
        val checked = BooleanArray(c.size) { !c[it].forceRename && c[it].srcSize != c[it].dstSize }
        val d = AlertDialog.Builder(this)
            .setTitle("✔ = replace, bina ✔ = skip")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Paste") { _, _ ->
                val dec = HashMap<String, String>()
                for (i in c.indices) {
                    dec[c[i].rel] = if (c[i].forceRename) "rename" else if (checked[i]) "overwrite" else "skip"
                }
                startXfer(items, move, "skip", dest, dec)
            }
            .setNegativeButton("Peeche") { _, _ -> showConflicts(items, move, dest, res) }
            .setNeutralButton("Sab ✔ / ✘", null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val on = checked.any { !it }
            for (i in c.indices) {
                if (c[i].forceRename) continue
                checked[i] = on
                d.listView.setItemChecked(i, on)
            }
        }
    }

    private fun askConflict(onChoice: (String) -> Unit) {
        val labels = arrayOf(
            "Skip: jo pehle se hai use rehne do",
            "Rename: naya naam do (name (1))",
            "Overwrite: purani file badal do"
        )
        val values = arrayOf("skip", "rename", "overwrite")
        AlertDialog.Builder(this)
            .setTitle("Is naam ki file / folder yahan pehle se hai")
            .setItems(labels) { _, i -> onChoice(values[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startXfer(items: List<ClipItem>, move: Boolean, onConflict: String, dest: String, decisions: Map<String, String>?) {
        val destPath = if (dest.endsWith("/")) dest else "$dest/"
        swipe.isRefreshing = true
        io.execute {
            try {
                val id = if (LocalJobs.involvesLocal(items, store))
                    LocalJobs.start(applicationContext, items, store, destPath, move, onConflict, decisions)
                else
                    Xfer.start(items, store, destPath, move, onConflict)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    swipe.isRefreshing = false
                    if (move) {
                        Session.clearClip()
                        refreshPasteBar()
                    }
                    showProgress(id, (if (move) "Move" else "Copy") + " → " + storeLabel + ":" + destPath)
                }
            } catch (e: DavAuthException) {
                runOnUiThread {
                    swipe.isRefreshing = false
                    Toast.makeText(this, "Login galat / expire — dobara login karo", Toast.LENGTH_LONG).show()
                    Session.clearPass(this)
                    startActivity(
                        Intent(this, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    swipe.isRefreshing = false
                    Toast.makeText(this, "❌ " + (e.message ?: "transfer shuru nahi hua"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showProgress(id: String, title: String) {
        closeProgress()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(12), dp(20), dp(4))
        val msg = TextView(this)
        msg.text = "Shuru ho raha hai…"
        msg.textSize = 14f
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.isIndeterminate = true
        val blp = LinearLayout.LayoutParams(MATCH, WRAP)
        blp.topMargin = dp(12)
        box.addView(msg, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(bar, blp)

        val d = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Background me chalne do", null)
            .setNegativeButton("Cancel job", null)
            .setNeutralButton("Transfers", null)
            .create()
        d.setCanceledOnTouchOutside(false)
        d.setOnDismissListener {
            if (progDlg === d) {
                progDlg = null
                progMsg = null
                progBar = null
            }
        }
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { d.dismiss() }
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            startActivity(Intent(this, TransfersActivity::class.java))
        }
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            msg.text = "Cancel ho raha hai…"
            io.execute {
                try {
                    Xfers.cancel(id)
                } catch (e: Exception) {
                }
            }
        }
        progDlg = d
        progMsg = msg
        progBar = bar
        watch(id, 0)
    }

    private fun closeProgress() {
        progDlg?.dismiss()
        progDlg = null
        progMsg = null
        progBar = null
    }

    /** Job ka status ~1.5 s par poll. Dialog band ho to bhi chalta rahta hai (khatam hone par listing refresh). */
    private fun watch(id: String, fails: Int) {
        if (isFinishing || isDestroyed) return
        io.execute {
            var job: XferJob? = null
            var lost = false
            try {
                job = Xfers.status(id)
            } catch (e: XferException) {
                if (e.code == 404) lost = true
            } catch (e: Exception) {
            }
            val got: XferJob? = job
            val gone: Boolean = lost
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (gone) {
                    closeProgress()
                    Toast.makeText(this, "Job nahi mili (server restart hua?) — listing refresh karo", Toast.LENGTH_LONG).show()
                    reload()
                    return@runOnUiThread
                }
                if (got == null) {
                    if (fails >= 5) {
                        closeProgress()
                        Toast.makeText(
                            this,
                            "Progress nahi mil raha. Job server par chal rahi hai, Transfers screen dekho",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        ui.postDelayed({ watch(id, fails + 1) }, 3000)
                    }
                    return@runOnUiThread
                }
                val j: XferJob = got
                val pct = j.pct
                progBar?.let { b ->
                    if (pct < 0) {
                        b.isIndeterminate = true
                    } else {
                        b.isIndeterminate = false
                        b.progress = pct
                    }
                }
                progMsg?.text = j.stateLabel() + (if (pct >= 0) " · $pct%" else "") + "\n" + j.line() +
                    (if (j.current.isNotEmpty()) "\n" + j.current else "")
                if (j.active) {
                    ui.postDelayed({ watch(id, 0) }, 1500)
                } else {
                    finishJob(j)
                }
            }
        }
    }

    private fun finishJob(j: XferJob) {
        closeProgress()
        when (j.state) {
            "done" -> Toast.makeText(
                this,
                "✅ Ho gaya: ${j.filesDone} file" + (if (j.filesSkipped > 0) " (${j.filesSkipped} skip)" else ""),
                Toast.LENGTH_LONG
            ).show()
            "cancelled" -> Toast.makeText(this, "⛔ Transfer cancel hua", Toast.LENGTH_LONG).show()
            else -> {
                val txt = if (j.errors.isEmpty()) "Kuch files nahi ho payin" else j.errors.take(8).joinToString("\n• ", "• ")
                AlertDialog.Builder(this)
                    .setTitle("⚠ Transfer me dikkat (${j.filesDone}/${j.filesTotal} ho gayi)")
                    .setMessage(txt)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        if (!selecting) reload()
    }

    // ───────────────────────── file operations ─────────────────────────

    private fun runOp(okMsg: String, op: () -> Unit) {
        swipe.isRefreshing = true
        io.execute {
            val err: String? = try {
                op()
                null
            } catch (e: DavAuthException) {
                "Login galat"
            } catch (e: Exception) {
                e.message ?: "error"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, err?.let { "❌ $it" } ?: okMsg, Toast.LENGTH_LONG).show()
                if (selecting) exitSelect()
                reload()
            }
        }
    }

    private fun askText(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.setSingleLine()
        et.setText(initial)
        et.setSelection(et.text.length)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty() && !t.contains('/')) onOk(t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newFolder() {
        val dir = currentUrl
        askText("Naya folder", "") { name ->
            runOp("Folder ban gaya") { Fs.mkdir(dir, name) }
        }
    }

    private fun rename(e: Entry) {
        if (store.isHf && e.isDir) {
            // /xfer folder ko usi naam se hi move karta hai, naya naam nahi de sakta
            AlertDialog.Builder(this)
                .setTitle(store.short + " folder rename")
                .setMessage(store.label + " me folder ka naam badalna abhi supported nahi hai.\n\nFolder ko doosre folder me bhejna ho to Move (✂) use karo.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val dir = currentUrl
        askText("Rename", e.name) { name ->
            if (name != e.name) {
                runOp("Rename ho gaya") { Fs.rename(e, dir, name) }
            }
        }
    }

    private fun confirmDelete(list: List<Entry>) {
        if (list.isEmpty()) return
        val hasDir = list.any { it.isDir }
        val what = if (list.size == 1) list[0].name else "${list.size} items"
        val title: String
        val msg: String
        if (store == Store.LOCAL) {
            title = "Phone se delete?"
            msg = what + "\n\n⚠ Phone se PERMANENTLY delete hoga (recycle bin nahi hai)." +
                (if (hasDir) "\nFolder ho to uske andar ki saari files bhi jayengi." else "")
        } else if (store.isHf) {
            title = "⚠ " + store.label + " se delete?"
            msg = what + "\n\n⚠ " + store.label + " se PERMANENTLY delete hoga. Trash nahi hai, wapas nahi aayega." +
                (if (hasDir) "\nFolder ho to uske andar ki saari files bhi jayengi." else "") +
                "\n\nDhyan: is bucket me Trading / app ka data bhi ho sakta hai."
        } else {
            title = "Delete?"
            msg = what + (if (hasDir) "\n\n⚠ Poora folder aur uske andar ki saari files hat jayengi." else "")
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
                runOp("Delete ho gaya") {
                    var fail = 0
                    var last = ""
                    for (e in list) {
                        try {
                            Fs.delete(e.url)
                        } catch (ex: DavAuthException) {
                            throw ex
                        } catch (ex: Exception) {
                            fail++
                            last = ex.message ?: ""
                        }
                    }
                    if (fail > 0) throw IOException("$fail item delete nahi hue ($last)")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun upload(uris: List<Uri>) {
        val dir = currentUrl
        swipe.isRefreshing = true
        io.execute {
            var ok = 0
            for ((i, u) in uris.withIndex()) {
                var name = "file_" + System.currentTimeMillis()
                var size = -1L
                try {
                    contentResolver.query(u, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (ni >= 0) name = c.getString(ni) ?: name
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                } catch (e: Exception) {
                }
                val label = "⬆ ${i + 1}/${uris.size}: $name"
                runOnUiThread { subTv.text = label }
                try {
                    Dav.put(dir + Uri.encode(name), UriBody(this, u, size, contentResolver.getType(u)))
                    ok++
                } catch (e: Exception) {
                    val fn = name
                    runOnUiThread {
                        Toast.makeText(this, "Upload fail: $fn (${e.message})", Toast.LENGTH_LONG).show()
                    }
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, "Upload: $ok/${uris.size} ho gaye", Toast.LENGTH_LONG).show()
                reload()
            }
        }
    }

    // ───────────────────────── search ─────────────────────────

    private fun buildSearchBar() {
        searchBar = LinearLayout(this)
        searchBar.orientation = LinearLayout.VERTICAL
        searchBar.setBackgroundColor(0xFFFFF8E1.toInt())
        searchBar.setPadding(dp(8), dp(4), dp(8), dp(4))
        searchBar.visibility = View.GONE

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        searchEt = EditText(this)
        searchEt.hint = "Is folder me naam se dhundo…"
        searchEt.setSingleLine()
        searchEt.textSize = 15f
        searchEt.setTextColor(Color.BLACK)
        searchEt.setHintTextColor(0xFF777777.toInt())
        searchEt.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchEt.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                ui.removeCallbacks(searchTask)
                ui.postDelayed(searchTask, 250)
            }
        })
        searchEt.setOnEditorActionListener { _, _, _ ->
            ui.removeCallbacks(searchTask)
            onQueryChanged()
            hideKeyboard()
            true
        }
        row.addView(searchEt)
        row.addView(iconBtn("✕", 44) { closeSearch() })

        deepCb = CheckBox(this)
        deepCb.text = "Andar ke saare folders me bhi dhundo"
        deepCb.textSize = 13f
        deepCb.setTextColor(Color.BLACK)
        deepCb.setOnCheckedChangeListener { _, c ->
            deep = c
            onQueryChanged()
        }

        searchBar.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        searchBar.addView(deepCb, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun hideKeyboard() {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchEt.windowToken, 0)
        } catch (e: Exception) { }
    }

    private fun openSearch() {
        searching = true
        searchBar.visibility = View.VISIBLE
        searchBtn.setTextColor(0xFFEF6C00.toInt())
        searchEt.requestFocus()
        searchEt.post {
            try {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(searchEt, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) { }
        }
    }

    /** Search band, bina list dobara banaye (folder badalte waqt use hota hai). */
    private fun resetSearch() {
        deepCancel.set(true)
        deepRunning = false
        deepResults = emptyList()
        searching = false
        query = ""
        searchBtn.setTextColor(Color.BLACK)
        ui.removeCallbacks(searchTask)
        searchEt.setText("")
        searchBar.visibility = View.GONE
        hideKeyboard()
    }

    private fun closeSearch() {
        resetSearch()
        applyFilter()
        updateHeader()
    }

    private fun onQueryChanged() {
        deepCancel.set(true)
        deepRunning = false
        val q = query.trim()
        if (searching && deep && q.isNotEmpty()) {
            startDeep(q)
        } else {
            deepResults = emptyList()
            applyFilter()
            updateHeader()
        }
    }

    /** Screen par kya dikhana hai: poori listing, naam-filter, ya andar-tak ke search results. */
    private fun applyFilter() {
        val q = query.trim()
        val filtering = searching && q.isNotEmpty()
        val base = if (hideDots) entries.filter { !it.name.startsWith(".") } else entries
        shown = when {
            !filtering -> base
            deep -> deepResults
            else -> base.filter { it.name.contains(q, ignoreCase = true) }
        }
        adapter.items = shown
        adapter.notifyDataSetChanged()
        if (shown.isEmpty()) {
            emptyTv.text = when {
                filtering && deep && deepRunning -> "🔍 Dhundh raha hu…"
                filtering -> "Kuch nahi mila: \"$q\""
                else -> "Folder khaali hai"
            }
            emptyTv.visibility = View.VISIBLE
        } else {
            emptyTv.visibility = View.GONE
        }
        updateHeader()
    }

    /** Current folder ke andar saare sub-folders me naam se dhundo (BFS; results aate hi dikhte hain). */
    private fun startDeep(q: String) {
        val cancel = AtomicBoolean(false)
        deepCancel = cancel
        deepResults = emptyList()
        deepRunning = true
        applyFilter()
        val base = if (currentUrl.isEmpty()) rootUrl else currentUrl
        io.execute {
            val found = ArrayList<Entry>()
            val dq = java.util.ArrayDeque<String>()
            dq.add(base)
            var last = 0L
            val deadline = System.currentTimeMillis() + 90_000L
            while (dq.isNotEmpty() && !cancel.get() && found.size < 500 && System.currentTimeMillis() < deadline) {
                val d = dq.poll() ?: break
                val kids = try { Fs.list(d) } catch (e: Exception) { continue }
                val rel = Uri.decode(d.removePrefix(base)).trim('/')
                for (k in kids) {
                    if (hideDots && k.name.startsWith(".")) continue
                    if (k.isDir) dq.add(k.url)
                    if (k.name.contains(q, ignoreCase = true)) found.add(k.copy(sub = rel))
                }
                val now = System.currentTimeMillis()
                if (now - last > 400) {
                    last = now
                    val snap = sorted(ArrayList(found))
                    runOnUiThread {
                        if (!cancel.get() && !isFinishing) {
                            deepResults = snap
                            applyFilter()
                        }
                    }
                }
            }
            val snap = sorted(found)
            runOnUiThread {
                if (!cancel.get() && !isFinishing) {
                    deepResults = snap
                    deepRunning = false
                    applyFilter()
                }
            }
        }
    }

    // ───────────────────────── favourites ─────────────────────────

    private fun pathKey(e: Entry): String = try {
        Favs.key(store, Storages.pathOf(store, e.url))
    } catch (x: Exception) {
        ""
    }

    private fun curPath(): String = Storages.pathOf(store, currentUrl)

    private fun updateFavBtn() {
        if (!::favBtn.isInitialized || currentUrl.isEmpty()) return
        val on = try { Favs.key(store, curPath()) in favSet } catch (e: Exception) { false }
        favBtn.text = if (on) "★" else "☆"
        favBtn.setTextColor(if (on) 0xFFF9A825.toInt() else Color.BLACK)
    }

    /** Header ka ☆: abhi khula folder favourite karo / hatao. */
    private fun toggleCurrentFav() {
        if (currentUrl.isEmpty() || currentUrl == rootUrl) {
            Toast.makeText(this, "Poora storage favourite karne ki zarurat nahi — koi folder kholo", Toast.LENGTH_SHORT).show()
            return
        }
        val name = Uri.decode(currentUrl.trimEnd('/').substringAfterLast('/'))
        val now = Favs.toggle(this, store, curPath(), name, true)
        favSet = Favs.keys(this)
        updateFavBtn()
        Toast.makeText(this, if (now) "⭐ Favourite me jodi: $name" else "Favourite se hata di", Toast.LENGTH_SHORT).show()
    }

    private fun setFavs(list: List<Entry>, add: Boolean) {
        for (e in list) {
            val p = Storages.pathOf(store, e.url)
            if (add) Favs.add(this, store, p, e.name, e.isDir) else Favs.remove(this, store, p)
        }
        favSet = Favs.keys(this)
        exitSelect()
        adapter.notifyDataSetChanged()
        updateFavBtn()
        Toast.makeText(
            this,
            if (add) "⭐ ${list.size} item favourite me jode" else "${list.size} item favourite se hataye",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Sirf abhi khule folder ke favourites (is folder se ⋮ → Favourite se chune hue). */
    private fun folderFavs(): List<Favs.Fav> = try {
        if (currentUrl.isEmpty()) emptyList() else Favs.inFolder(this, store, curPath())
    } catch (e: Exception) {
        emptyList()
    }

    private fun openFolderFav(f: Favs.Fav) {
        val k = Favs.key(f.store, f.path)
        val e = shown.firstOrNull { pathKey(it) == k }
        if (e != null) { onOpen(e); return }
        if (f.isDir) openFav(f)
        else Toast.makeText(this, "Ye file abhi list me nahi dikh rahi (search/filter band karo)", Toast.LENGTH_SHORT).show()
    }

    private fun showFavs() {
        val list = folderFavs()
        if (list.isEmpty()) {
            Toast.makeText(
                this,
                "Is folder me koi favourite nahi. Item chuno → ⋮ → Favourite me jodo",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = list.map {
            (if (it.isDir) "📁 " else Kinds.emoji(Kinds.of(it.name, false)) + " ") + it.name
        }.toTypedArray()
        val dlg = AlertDialog.Builder(this)
            .setTitle("⭐ Is folder ke Favourites (lamba dabao = hatao)")
            .setItems(labels) { _, i -> openFolderFav(list[i]) }
            .setNegativeButton("Band", null)
            .create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
            val f = list[pos]
            Favs.remove(this, f.store, f.path)
            favSet = Favs.keys(this)
            adapter.notifyDataSetChanged()
            updateFavBtn()
            dlg.dismiss()
            Toast.makeText(this, "Favourite se hata di: ${f.name}", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun openFav(f: Favs.Fav) {
        val dirPath = if (f.isDir) f.path else f.path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val url = Storages.urlOf(f.store, dirPath, true)
        if (url.startsWith(rootUrl)) {            // isi storage ke andar: yahin khol do
            load(url, true)
            return
        }
        if (pickMode) {
            Toast.makeText(this, "File chunte waqt dusre storage ki favourite nahi khulegi", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, BrowserActivity::class.java).putExtra("store", f.store.id).putExtra("start", url)
        if (f.store == Store.LOCAL) {
            if (!LocalFs.hasAccess(this)) {
                Toast.makeText(this, "Pehle Home se Phone Storage kholo (permission ke liye)", Toast.LENGTH_LONG).show()
                return
            }
            val file = File(f.path)
            if (LocalFs.underMissingVolume(this, file)) {
                Toast.makeText(this, "💾 Ye drive abhi lagi nahi hai", Toast.LENGTH_LONG).show()
                return
            }
            val vol = LocalFs.volumeFor(this, file)
            if (vol != null) {
                i.putExtra("local_root", vol.root.absolutePath).putExtra("local_label", vol.label).putExtra("local_icon", vol.icon)
            }
        }
        startActivity(i)
    }

    // ───────────────────────── live refresh + live capacity ─────────────────────────

    private fun livePeriod(): Long = when (store) {
        Store.LOCAL -> 3000L
        Store.HF, Store.HF2 -> 10000L
        else -> 6000L
    }

    private fun sigOf(l: List<Entry>): Long {
        var h = 17L
        for (e in l) {
            h = h * 31 + e.url.hashCode()
            h = h * 31 + e.size
            h = h * 31 + e.modified
        }
        return h
    }

    /** Folder me kuch badla ho to chupchap list taaza karo (spinner nahi, scroll wahin rehta hai). */
    private fun silentRefresh() {
        if (!::swipe.isInitialized || selecting || swipe.isRefreshing || currentUrl.isEmpty() || progDlg != null) return
        val url = currentUrl
        val token = loadToken
        io.execute {
            val list = try { Fs.list(url) } catch (e: Exception) { return@execute }
            runOnUiThread {
                if (isFinishing || token != loadToken || url != currentUrl || selecting) return@runOnUiThread
                val s = sorted(list)
                val sig = sigOf(s)
                if (sig == lastSig) return@runOnUiThread
                lastSig = sig
                entries = s
                applyFilter()
            }
        }
    }

    /** Phone / SD par folder me file aate-jaate hi turant pata chal jata hai. */
    private fun startObserver() {
        stopObserver()
        if (store != Store.LOCAL || currentUrl.isEmpty()) return
        val path = try { LocalFs.fileOf(currentUrl).absolutePath } catch (e: Exception) { return }
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF
        @Suppress("DEPRECATION")
        val o = object : FileObserver(path, mask) {
            override fun onEvent(event: Int, p: String?) {
                ui.removeCallbacks(obsTask)
                ui.postDelayed(obsTask, 500)
            }
        }
        try {
            o.startWatching()
            observer = o
        } catch (e: Exception) { }
    }

    private fun stopObserver() {
        try { observer?.stopWatching() } catch (e: Exception) { }
        observer = null
    }

    private fun setCap(c: CapLive.Cap?) {
        if (!::capTv.isInitialized) return
        if (c == null) {
            capTv.visibility = View.GONE
            return
        }
        capTv.visibility = View.VISIBLE
        capTv.text = "💽 " + c.oneLine()
        capTv.setTextColor(if (c.usedFrac > 0.92f) 0xFFC62828.toInt() else Color.BLACK)
    }

    /** Header me is storage ki capacity (kitna % khaali / kitna free), live. */
    private fun refreshCap() {
        when (store) {
            Store.LOCAL -> setCap(CapLive.local(localRoot?.let { File(it) } ?: LocalFs.rootDir()))
            Store.HF, Store.HF2 -> {
                val c = CapLive.hfCached(this, store)
                if (c != null) setCap(c)
                else capTv.apply {
                    visibility = View.VISIBLE
                    text = "☁️ capacity " + fmtSize(Analyzer.HF_CAPACITY) + " (Home par use nikalta hai)"
                }
            }
            else -> io.execute {
                val c = CapLive.aic()
                runOnUiThread { if (!isFinishing && c != null) setCap(c) }
            }
        }
    }

    // ───────────────────────── menu / storage / update / logout ─────────────────────────

    private fun switchStorage() {
        val all = Store.values()
        val vols = LocalFs.removableVolumes(this)
        val labels = (all.map { it.icon + " " + it.label + (if (it == store && !(it == Store.LOCAL && localRoot != null)) "  (abhi yahan)" else "") } +
            vols.map { it.icon + " " + it.label + (if (localRoot == it.root.absolutePath) "  (abhi yahan)" else "") }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Storage chuno")
            .setItems(labels) { _, i ->
                if (i >= all.size) {
                    val v = vols[i - all.size]
                    if (localRoot != v.root.absolutePath) {
                        if (!LocalFs.hasAccess(this)) {
                            Toast.makeText(this, "Pehle Home se Phone Storage kholo (permission ke liye)", Toast.LENGTH_LONG).show()
                        } else {
                            startActivity(Intent(this, BrowserActivity::class.java)
                                .putExtra("store", Store.LOCAL.id).putExtra("local_root", v.root.absolutePath)
                                .putExtra("local_label", v.label).putExtra("local_icon", v.icon))
                            finish()
                        }
                    }
                    return@setItems
                }
                val s = all[i]
                if (s == Store.LOCAL && !LocalFs.hasAccess(this)) {
                    Toast.makeText(this, "Pehle Home se Phone Storage kholo (permission ke liye)", Toast.LENGTH_LONG).show()
                } else if (s != store || (s == Store.LOCAL && localRoot != null)) {
                    startActivity(Intent(this, BrowserActivity::class.java).putExtra("store", s.id))
                    finish()
                }
            }
            .show()
    }

    private fun showMenu() {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("📁 Naya folder" to { newFolder() })
        acts.add("🔍 Search (is folder me)" to { openSearch() })
        acts.add("⭐ Is folder ke Favourites (${folderFavs().size})" to { showFavs() })
        if (store != Store.LOCAL) acts.add("⬆ Upload (files chuno)" to { pickFiles.launch(arrayOf("*/*")) })
        acts.add("🔁 Storage badlo" to { switchStorage() })
        val drive = localRoot?.let { lr -> VolumeWatcher.snapshot.firstOrNull { it.root.absolutePath == lr } }
        if (drive != null) {
            acts.add("ℹ Drive ki jaankari (${drive.fs}, " + (if (drive.canWrite) "likh sakte hain" else "sirf padh") + ")" to { DriveUi.info(this, drive) })
            acts.add("⏏ Safe eject" to { DriveUi.safeEject(this, drive) })
        }
        acts.add("🎬 Kholne ka tarika: ${Opener.labels[Opener.mode(this)]}" to { pickOpenMode() })
        acts.add("📊 Transfers" to { startActivity(Intent(this, TransfersActivity::class.java)) })
        acts.add("🔍 Analyze: kaun si folder kitni jagah le rahi" to {
            AnalyzeUi(this, store, rootUrl, storeLabel).show(if (currentUrl.isEmpty()) rootUrl else currentUrl)
        })
        acts.add("🧬 Duplicate dhundo (video / music / image)" to {
            DupUi(this, store, storeLabel) { reload() }.show(if (currentUrl.isEmpty()) rootUrl else currentUrl)
        })
        acts.add("↕ Sort: ${sortNames[sortMode]}" to { showSort() })
        acts.add("👁 Hidden (. wale) folders: " + (if (hideDots) "CHHUPE HAIN → dikhao" else "DIKH RAHE HAIN → chhupao") to {
            hideDots = !hideDots
            Session.setBool(this, "hidedots", hideDots)
            applyFilter()
        })
        acts.add(
            ("🎞 Video thumbnails: " + (if (videoThumbs) "ON" else "OFF") +
                (if (store.isHf) " (" + store.short + " par hamesha OFF)" else "")) to {
                videoThumbs = !videoThumbs
                Session.setBool(this, "vthumbs", videoThumbs)
                applyThumbFlag()
                adapter.notifyDataSetChanged()
            }
        )
        acts.add("🧹 Thumbnail cache saaf karo" to {
            Thumbs.clear()
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Cache saaf", Toast.LENGTH_SHORT).show()
        })
        acts.add("🚪 Logout / server badlo" to { logout() })
        AlertDialog.Builder(this)
            .setItems(acts.map { it.first }.toTypedArray()) { _, which -> acts[which].second() }
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

    private fun showSort() {
        AlertDialog.Builder(this)
            .setTitle("Sort")
            .setSingleChoiceItems(sortNames, sortMode) { d, which ->
                sortMode = which
                Session.setInt(this, "sort", which)
                entries = sorted(entries)
                deepResults = sorted(deepResults)
                applyFilter()
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
