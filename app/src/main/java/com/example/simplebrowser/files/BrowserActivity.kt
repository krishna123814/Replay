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
    private val storeLabel: String get() = if (localRoot != null) "SD Card" else store.label
    private val storeIcon: String get() = if (localRoot != null) "💾" else store.icon
    private var currentUrl = ""
    private val stack = ArrayList<String>()
    private var entries: List<Entry> = emptyList()
    private var loadToken = 0

    private var gridMode = false
    private var sortMode = 0
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
        val lr = localRoot
        rootUrl = if (lr != null) Dav.withSlash(LocalFs.urlOf(File(lr), true)) else Dav.withSlash(Session.rootFor(store))
        gridMode = Session.bool(this, "grid", false)
        sortMode = Session.int(this, "sort", 0).coerceIn(0, sortNames.size - 1)
        videoThumbs = Session.bool(this, "vthumbs", true)

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

    override fun onResume() {
        super.onResume()
        if (::pasteBar.isInitialized) refreshPasteBar()
        if (Queue.dirty && ::swipe.isInitialized) {
            Queue.dirty = false
            reload()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("cur", currentUrl)
        outState.putStringArrayList("stack", ArrayList(stack))
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
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
        badge.text = storeIcon + " " + (if (localRoot != null) "SD" else store.short)
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

        col.addView(titleTv)
        col.addView(subTv)

        viewBtn = iconBtn(if (gridMode) "☰" else "▦") { toggleView() }
        val more = iconBtn("⋮") { showMenu() }

        header.addView(back)
        header.addView(badge)
        header.addView(col)
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

        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(selBar, LinearLayout.LayoutParams(MATCH, WRAP))
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
        adapter.videoThumbs = videoThumbs && store != Store.HF
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
        val folders = entries.count { it.isDir }
        subTv.text = "$storeIcon $storeLabel · $folders folders · ${entries.size - folders} files"
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
        return if (store == Store.HF && m.contains("503")) "HF Bucket server par band / configured nahi hai (503)" else m
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
                    adapter.items = entries
                    adapter.notifyDataSetChanged()
                    if (changed) rv.scrollToPosition(0)
                    swipe.isRefreshing = false
                    updateHeader()
                    if (entries.isEmpty()) {
                        emptyTv.text = "Folder khaali hai"
                        emptyTv.visibility = View.VISIBLE
                    }
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
                val imgs = entries.filter { it.kind == Kind.IMAGE }
                Queue.images = imgs
                val i = imgs.indexOfFirst { it.url == e.url }.coerceAtLeast(0)
                startActivity(Intent(this, ImageViewerActivity::class.java).putExtra("index", i))
            }
            Kind.VIDEO, Kind.AUDIO -> {
                val same = entries.filter { it.kind == e.kind }
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
        for (e in entries) if (!(pickMode && e.isDir)) selected[e.url] = e
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
            val i = entries.indexOfFirst { it.url == changedUrl }
            if (i >= 0) adapter.notifyItemChanged(i) else adapter.notifyDataSetChanged()
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    private fun selMore() {
        if (selected.size != 1) {
            Toast.makeText(this, "Rename / Download / Info ke liye ek hi item chuno", Toast.LENGTH_SHORT).show()
            return
        }
        val e = selected.values.first()
        val opts = ArrayList<String>()
        if (!e.isDir && store != Store.LOCAL) opts.add(optDownload)
        if (!e.isDir) opts.add(optOpenWith)
        opts.add(optRename)
        opts.add(optInfo)
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(opts.toTypedArray()) { _, i ->
                when (opts[i]) {
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
            if (store == Store.HF) {
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
        if (store == Store.HF && e.isDir) {
            // /xfer folder ko usi naam se hi move karta hai, naya naam nahi de sakta
            AlertDialog.Builder(this)
                .setTitle("HF folder rename")
                .setMessage("HF Bucket me folder ka naam badalna abhi supported nahi hai.\n\nFolder ko doosre folder me bhejna ho to Move (✂) use karo.")
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
        } else if (store == Store.HF) {
            title = "⚠ HF Bucket se delete?"
            msg = what + "\n\n⚠ HF Bucket se PERMANENTLY delete hoga. Trash nahi hai, wapas nahi aayega." +
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

    // ───────────────────────── menu / storage / update / logout ─────────────────────────

    private fun switchStorage() {
        val all = Store.values()
        val sd = LocalFs.sdRoot(this)
        val labels = (all.map { it.icon + " " + it.label + (if (it == store && !(it == Store.LOCAL && localRoot != null)) "  (abhi yahan)" else "") } +
            (if (sd != null) listOf("💾 SD Card" + (if (localRoot != null) "  (abhi yahan)" else "")) else emptyList())).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Storage chuno")
            .setItems(labels) { _, i ->
                if (i >= all.size) {
                    if (sd != null && localRoot == null) {
                        if (!LocalFs.hasAccess(this)) {
                            Toast.makeText(this, "Pehle Home se Phone Storage kholo (permission ke liye)", Toast.LENGTH_LONG).show()
                        } else {
                            startActivity(Intent(this, BrowserActivity::class.java)
                                .putExtra("store", Store.LOCAL.id).putExtra("local_root", sd.absolutePath))
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
        if (store != Store.LOCAL) acts.add("⬆ Upload (files chuno)" to { pickFiles.launch(arrayOf("*/*")) })
        acts.add("🔁 Storage badlo" to { switchStorage() })
        acts.add("🎬 Kholne ka tarika: ${Opener.labels[Opener.mode(this)]}" to { pickOpenMode() })
        acts.add("📊 Transfers" to { startActivity(Intent(this, TransfersActivity::class.java)) })
        acts.add("↕ Sort: ${sortNames[sortMode]}" to { showSort() })
        acts.add(
            ("🎞 Video thumbnails: " + (if (videoThumbs) "ON" else "OFF") +
                (if (store == Store.HF) " (HF par hamesha OFF)" else "")) to {
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
                adapter.items = entries
                adapter.notifyDataSetChanged()
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
