package com.example.simplebrowser

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Download ke screens (sab ⚙ settings ke andar se khulte hain, main screen par kuch nahi):
 *  - settings(): fast engine, connections, parallel files, retry, folder by type
 *  - queue():    live progress, speed, ETA, pause/resume/cancel/open
 */
object DlUi {

    private const val LIGHT = "#d1d4dc"
    private const val DIM = "#9aa0ad"

    private fun themed(ctx: Context) =
        ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun show(b: AlertDialog.Builder, overlay: Boolean): AlertDialog {
        val d = b.create()
        if (overlay) d.window?.setType(overlayType())
        d.show()
        return d
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    // ═════════════════════════════════════════════════════════════════════
    // ⚡ Download settings
    // ═════════════════════════════════════════════════════════════════════
    fun settings(ctx: Context, overlay: Boolean) {
        try {
            val t = themed(ctx)
            val col = LinearLayout(t)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 4))

            fun check(text: String, key: String, def: Boolean) {
                val cb = CheckBox(t)
                cb.text = text
                cb.setTextColor(Color.parseColor(LIGHT))
                cb.isChecked = DlSettings.getBool(ctx, key, def)
                cb.setOnCheckedChangeListener { _, on -> DlSettings.setBool(ctx, key, on) }
                col.addView(cb)
            }

            fun seek(label: String, key: String, min: Int, max: Int, def: Int, fmt: (Int) -> String) {
                val tv = TextView(t)
                tv.setTextColor(Color.parseColor(LIGHT))
                tv.textSize = 14f
                tv.setPadding(0, dp(ctx, 10), 0, 0)
                val sb = SeekBar(t)
                sb.max = max - min
                val cur = DlSettings.getInt(ctx, key, def).coerceIn(min, max)
                sb.progress = cur - min
                tv.text = label + ": " + fmt(cur)
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = p + min
                        tv.text = label + ": " + fmt(v)
                        if (fromUser) DlSettings.setInt(ctx, key, v)
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        DlEngine.pokeQueue(ctx)
                    }
                })
                col.addView(tv)
                col.addView(sb)
            }

            check("⚡ Fast engine (multi-connection, speed/ETA, retry)", DlSettings.K_ENGINE, true)
            seek("Connections per file", DlSettings.K_CONNS, 1, 8, 4) { "$it" }
            seek("Ek saath kitni files", DlSettings.K_PARALLEL, 1, 4, 2) { "$it" }
            check("🔁 Auto-retry (net / server error par)", DlSettings.K_RETRY_ON, true)
            seek("Max retry", DlSettings.K_RETRY_MAX, 1, 10, 5) { "$it" }
            check("📁 File type ke hisaab se folder", DlSettings.K_BY_TYPE, true)
            check("⬇ Video par download icon dikhao", DlSettings.K_VID_ICON, true)

            val srv = Button(t)
            srv.text = "☁ Server par bhejo (AIC / HF folder)…"
            srv.isAllCaps = false
            srv.setOnClickListener { serverSettings(ctx, overlay) }
            col.addView(srv)

            val note = TextView(t)
            note.setTextColor(Color.parseColor(DIM))
            note.textSize = 11f
            note.setPadding(0, dp(ctx, 12), 0, 0)
            note.text = "Files: Downloads/Lion/<Videos | Audio | Images | Documents | Archives | APKs | Others>/\n" +
                "Fast engine band karne par purana Android DownloadManager chalega (speed/ETA/multi-connection nahi)."
            col.addView(note)

            val sv = ScrollView(t)
            sv.addView(col)
            show(
                AlertDialog.Builder(t)
                    .setTitle("⚡ Download settings")
                    .setView(sv)
                    .setPositiveButton("Done", null),
                overlay
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Settings khul nahi payi", Toast.LENGTH_SHORT).show()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ☁ Server par bhejo (AIC Disk / HF Bucket)
    // ═════════════════════════════════════════════════════════════════════
    fun serverSettings(ctx: Context, overlay: Boolean) {
        try {
            val t = themed(ctx)
            val col = LinearLayout(t)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 4))

            fun label(txt: String): TextView {
                val tv = TextView(t)
                tv.setTextColor(Color.parseColor(DIM)); tv.textSize = 12f
                tv.setPadding(0, dp(ctx, 10), 0, 0); tv.text = txt
                return tv
            }
            fun field(key: String, def: String, hint: String, pass: Boolean): EditText {
                val e = EditText(t)
                e.setTextColor(Color.parseColor(LIGHT)); e.setHintTextColor(Color.parseColor(DIM))
                e.hint = hint; e.setSingleLine(true); e.textSize = 14f
                e.setText(DlSettings.getStr(ctx, key, def))
                e.inputType = if (pass) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                return e
            }

            col.addView(label("Download ke baad file kahan bhejni hai?"))
            val rg = RadioGroup(t)
            val rOff = RadioButton(t); rOff.text = "Sirf phone par (server nahi)"; rOff.id = 1001
            val rAic = RadioButton(t); rAic.text = "💽 AIC Disk (VPS)"; rAic.id = 1002
            val rHf = RadioButton(t); rHf.text = "☁️ HF Bucket"; rHf.id = 1003
            for (r in listOf(rOff, rAic, rHf)) { r.setTextColor(Color.parseColor(LIGHT)); rg.addView(r) }
            when (ServerSync.store(ctx)) { "aic" -> rAic.isChecked = true; "hf" -> rHf.isChecked = true; else -> rOff.isChecked = true }
            col.addView(rg)

            col.addView(label("Server URL (MyFiles wala login)"))
            val eUrl = field(ServerSync.K_URL, ServerSync.DEFAULT_URL, "https://…/dav/", false)
            col.addView(eUrl)
            col.addView(label("Username"))
            val eUser = field(ServerSync.K_USER, "", "username", false)
            col.addView(eUser)
            col.addView(label("Password"))
            val ePass = field(ServerSync.K_PASS, "", "password", true)
            col.addView(ePass)

            val folderTv = TextView(t)
            folderTv.setTextColor(Color.parseColor(LIGHT)); folderTv.textSize = 14f
            folderTv.setPadding(0, dp(ctx, 14), 0, 0)
            fun refreshFolder() { folderTv.text = "📂 Folder: " + ServerSync.path(ctx) }
            refreshFolder()
            col.addView(folderTv)

            fun curStore(): String = when (rg.checkedRadioButtonId) { 1003 -> "hf"; 1002 -> "aic"; else -> "aic" }
            fun saveLogin() {
                DlSettings.setStr(ctx, ServerSync.K_URL, eUrl.text.toString().trim())
                DlSettings.setStr(ctx, ServerSync.K_USER, eUser.text.toString().trim())
                DlSettings.setStr(ctx, ServerSync.K_PASS, ePass.text.toString())
                DlSettings.setStr(ctx, ServerSync.K_STORE, when (rg.checkedRadioButtonId) { 1003 -> "hf"; 1002 -> "aic"; else -> "off" })
            }

            val pick = Button(t)
            pick.text = "📂 Folder chuno"
            pick.isAllCaps = false
            pick.setOnClickListener {
                saveLogin()
                if (DlSettings.getStr(ctx, ServerSync.K_USER, "").isEmpty()) {
                    Toast.makeText(ctx, "Pehle username / password daalo", Toast.LENGTH_SHORT).show()
                } else {
                    folderPicker(ctx, overlay, curStore(), ServerSync.path(ctx)) { chosen ->
                        DlSettings.setStr(ctx, ServerSync.K_PATH, chosen)
                        refreshFolder()
                    }
                }
            }
            col.addView(pick)

            val cb = CheckBox(t)
            cb.text = "Upload ke baad phone se hata do"
            cb.setTextColor(Color.parseColor(LIGHT))
            cb.isChecked = DlSettings.getBool(ctx, ServerSync.K_DELETE, true)
            cb.setOnCheckedChangeListener { _, on -> DlSettings.setBool(ctx, ServerSync.K_DELETE, on) }
            col.addView(cb)

            val cbDirect = CheckBox(t)
            cbDirect.text = "⚡ Seedha server par download (phone me file nahi aayegi)"
            cbDirect.setTextColor(Color.parseColor(LIGHT))
            cbDirect.isChecked = DlSettings.getBool(ctx, ServerSync.K_DIRECT, true)
            cbDirect.setOnCheckedChangeListener { _, on -> DlSettings.setBool(ctx, ServerSync.K_DIRECT, on) }
            col.addView(cbDirect)

            val note = TextView(t)
            note.setTextColor(Color.parseColor(DIM)); note.textSize = 11f
            note.setPadding(0, dp(ctx, 8), 0, 0)
            note.text = "⚡ on: link server ko jata hai, server khud download karke folder me rakhta hai (blob: links phone par hi chalte hain). Server se na ho paye to phone par utarti hai. ⚡ off: pehle phone par, phir server par upload."
            col.addView(note)

            val sv = ScrollView(t)
            sv.addView(col)
            show(
                AlertDialog.Builder(t)
                    .setTitle("☁ Server par bhejo")
                    .setView(sv)
                    .setPositiveButton("Save") { _, _ -> saveLogin() }
                    .setNegativeButton("Cancel", null),
                overlay
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Server settings khul nahi payi", Toast.LENGTH_SHORT).show()
        }
    }

    /** Server ke folders me ghumo; "Yahan chuno" se current folder set. */
    fun folderPicker(ctx: Context, overlay: Boolean, store: String, startPath: String, onChosen: (String) -> Unit) {
        val path = "/" + startPath.trim('/')
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            var names: List<String> = emptyList()
            var err: String? = null
            try { names = ServerSync.listFolders(ctx, store, path) } catch (e: Throwable) { err = e.message ?: e.javaClass.simpleName }
            ui.post {
                try {
                    val t = themed(ctx)
                    val isRoot = path == "/"
                    val labels = ArrayList<String>()
                    if (!isRoot) labels.add("⬆  .. (upar)")
                    for (n in names) labels.add("📁  $n")
                    val b = AlertDialog.Builder(t)
                        .setTitle((if (store == "hf") "☁️ HF " else "💽 AIC ") + path)
                        .setPositiveButton("Yahan chuno") { _, _ -> onChosen(path) }
                        .setNeutralButton("➕ Naya folder") { _, _ -> newFolderDialog(ctx, overlay, store, path, onChosen) }
                        .setNegativeButton("Cancel", null)
                    if (err != null) b.setMessage("Folders nahi mile: $err\n\nLogin / URL check karo.")
                    else b.setItems(labels.toTypedArray()) { _, which ->
                        val next = if (!isRoot && which == 0) {
                            val up = path.trimEnd('/').substringBeforeLast('/')
                            if (up.isEmpty()) "/" else up
                        } else {
                            val n = names[which - (if (isRoot) 0 else 1)]
                            (if (path == "/") "" else path) + "/" + n
                        }
                        folderPicker(ctx, overlay, store, next, onChosen)
                    }
                    show(b, overlay)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Folder list khul nahi payi", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun newFolderDialog(ctx: Context, overlay: Boolean, store: String, path: String, onChosen: (String) -> Unit) {
        val t = themed(ctx)
        val e = EditText(t)
        e.setTextColor(Color.parseColor(LIGHT)); e.hint = "folder ka naam"; e.setSingleLine(true)
        val box = FrameLayout(t)
        box.setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0)
        box.addView(e)
        show(
            AlertDialog.Builder(t)
                .setTitle("Naya folder ($path)")
                .setView(box)
                .setPositiveButton("Banao") { _, _ ->
                    val name = e.text.toString().trim().replace("/", " ")
                    if (name.isNotEmpty()) {
                        val full = (if (path == "/") "" else path) + "/" + name
                        Thread {
                            val msg = try { ServerSync.mkdir(ctx, store, full); null } catch (x: Throwable) { x.message ?: "error" }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (msg != null) Toast.makeText(ctx, "Folder nahi bana: $msg", Toast.LENGTH_LONG).show()
                                else folderPicker(ctx, overlay, store, full, onChosen)
                            }
                        }.start()
                    }
                }
                .setNegativeButton("Cancel", null),
            overlay
        )
    }

    /** Format/quality chuno. */
    fun videoPick(ctx: Context, overlay: Boolean, labels: List<String>, onPick: (Int) -> Unit) {
        try {
            val t = themed(ctx)
            show(
                AlertDialog.Builder(t)
                    .setTitle("⬇ Kis format / quality me download karein?")
                    .setItems(labels.toTypedArray()) { _, which -> onPick(which) }
                    .setNegativeButton("Cancel", null),
                overlay
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "List khul nahi payi", Toast.LENGTH_SHORT).show()
        }
    }

    /** "⬇ Sab": page ke videos ki list (checkbox), chune hue server par bhejo. */
    fun bulkPick(ctx: Context, overlay: Boolean, labels: List<String>, onOk: (List<Int>) -> Unit) {
        try {
            val t = themed(ctx)
            val checked = BooleanArray(labels.size) { true }
            val b = AlertDialog.Builder(t)
                .setTitle("⬇ " + labels.size + " videos mile")
                .setMultiChoiceItems(labels.map { it.take(90) }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("☁ Server par bhejo") { _, _ ->
                    val sel = checked.indices.filter { checked[it] }
                    if (sel.isEmpty()) Toast.makeText(ctx, "Kuch chuna nahi", Toast.LENGTH_SHORT).show() else onOk(sel)
                }
                .setNeutralButton("Sab hatao", null)
                .setNegativeButton("Cancel", null)
            val d = show(b, overlay)
            val neutral = d.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutral.setOnClickListener {
                val any = checked.any { it }
                for (i in checked.indices) {
                    checked[i] = !any
                    d.listView.setItemChecked(i, !any)
                }
                neutral.text = if (any) "Sab chuno" else "Sab hatao"
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "List khul nahi payi", Toast.LENGTH_SHORT).show()
        }
    }

    /** Video ka link nahi mila / info. */
    fun videoInfo(ctx: Context, overlay: Boolean, text: String) {
        try {
            val t = themed(ctx)
            val tv = TextView(t)
            tv.setTextColor(Color.parseColor(LIGHT))
            tv.textSize = 13f
            tv.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 4))
            tv.setTextIsSelectable(true)
            tv.text = text
            val sv = ScrollView(t)
            sv.addView(tv)
            show(
                AlertDialog.Builder(t)
                    .setTitle("⬇ Video")
                    .setView(sv)
                    .setPositiveButton("Theek hai", null),
                overlay
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Info khul nahi payi", Toast.LENGTH_SHORT).show()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 📥 Queue
    // ═════════════════════════════════════════════════════════════════════
    private class RowAdapter(val ctx: Context) : BaseAdapter() {
        var rows: List<DlRow> = emptyList()

        fun set(r: List<DlRow>) {
            rows = r
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): DlRow? = rows.getOrNull(position)
        override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id?.toLong() ?: 0L

        private fun build(): LinearLayout {
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            val title = TextView(ctx)
            title.setTextColor(Color.WHITE)
            title.textSize = 14f
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.MIDDLE
            val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
            pb.max = 1000
            val info = TextView(ctx)
            info.setTextColor(Color.parseColor(DIM))
            info.textSize = 11.5f
            info.maxLines = 4
            box.addView(title)
            box.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 8)))
            box.addView(info)
            return box
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val box = (convertView as? LinearLayout) ?: build()
            val r = rows[position]
            val title = box.getChildAt(0) as TextView
            val pb = box.getChildAt(1) as ProgressBar
            val info = box.getChildAt(2) as TextView
            title.text = r.name

            val pct = if (r.total > 0) (r.done * 100 / r.total).toInt() else -1
            pb.visibility = if (r.status == DlStatus.DONE) View.GONE else View.VISIBLE
            if (r.total > 0) {
                pb.isIndeterminate = false
                pb.progress = (r.done * 1000 / r.total).toInt()
            } else {
                pb.isIndeterminate = r.status == DlStatus.RUNNING
                pb.progress = 0
            }

            val sizeTxt = if (r.total > 0) "${DlFmt.size(r.done)} / ${DlFmt.size(r.total)}" else DlFmt.size(r.done)
            val sb = StringBuilder()
            when (r.status) {
                DlStatus.RUNNING -> {
                    sb.append("⬇ ")
                    if (pct >= 0) sb.append("$pct% · ")
                    sb.append(sizeTxt).append(" · ").append(DlFmt.speed(r.speed))
                    if (r.etaSec >= 0) sb.append(" · ETA ").append(DlFmt.eta(r.etaSec))
                    if (r.conns > 1) sb.append(" · ${r.conns} conn")
                }
                DlStatus.QUEUED -> sb.append("⏳ Queue me – number aane ka intezaar")
                DlStatus.PAUSED -> {
                    sb.append("⏸ Ruka hua")
                    if (pct >= 0) sb.append(" · $pct%")
                    sb.append(" · ").append(sizeTxt)
                }
                DlStatus.FAILED -> {
                    sb.append("❌ Fail: ").append(r.error)
                    if (pct >= 0) sb.append(" · $pct%")
                }
                DlStatus.DONE -> {
                    sb.append("✅ ").append(DlFmt.size(if (r.total > 0) r.total else r.done))
                    if (r.path.isNotEmpty()) sb.append(" · ").append(r.path)
                }
            }
            if (r.retries > 0 && r.status != DlStatus.DONE) sb.append(" · retries ${r.retries}")
            if (r.note.isNotEmpty()) sb.append("\n").append(r.note)
            info.text = sb.toString()
            return box
        }
    }

    private fun smallBtn(ctx: Context, text: String): Button {
        val b = Button(ctx)
        b.text = text
        b.textSize = 12f
        b.isAllCaps = false
        return b
    }

    fun queue(ctx: Context, overlay: Boolean) {
        DlEngine.init(ctx)
        try {
            val t = themed(ctx)
            val root = LinearLayout(t)
            root.orientation = LinearLayout.VERTICAL

            val bar = LinearLayout(t)
            bar.orientation = LinearLayout.HORIZONTAL
            val btnToggle = smallBtn(t, "⏯ Sab pause/resume")
            val btnClear = smallBtn(t, "🧹 Done saaf")
            bar.addView(btnToggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(btnClear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(bar)

            val empty = TextView(t)
            empty.setTextColor(Color.parseColor(DIM))
            empty.textSize = 13f
            empty.gravity = Gravity.CENTER
            empty.setPadding(dp(ctx, 12), dp(ctx, 20), dp(ctx, 12), dp(ctx, 20))
            empty.text = "Queue khali hai.\n\"➕ URLs\" se ek ya kai links daalo (har line me ek)."
            root.addView(empty)

            val lv = ListView(t)
            val adapter = RowAdapter(t)
            lv.adapter = adapter
            val h = (ctx.resources.displayMetrics.heightPixels * 0.5f).toInt()
            root.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))

            val dlg = show(
                AlertDialog.Builder(t)
                    .setTitle("📥 Download queue")
                    .setView(root)
                    .setPositiveButton("➕ URLs") { _, _ -> Downloader.askUrls(ctx, overlay) }
                    .setNegativeButton("Band", null),
                overlay
            )

            val handler = Handler(Looper.getMainLooper())
            val refresh = object : Runnable {
                override fun run() {
                    if (!dlg.isShowing) return
                    val rows = DlEngine.snapshot()
                    adapter.set(rows)
                    empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    lv.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(refresh)
            dlg.setOnDismissListener { handler.removeCallbacks(refresh) }

            btnToggle.setOnClickListener {
                if (DlEngine.hasActive()) DlEngine.pauseAll() else DlEngine.resumeAll()
            }
            btnClear.setOnClickListener {
                DlEngine.clearDone()
                Toast.makeText(ctx, "Poore hue downloads list se hata diye (files safe hain)", Toast.LENGTH_SHORT).show()
            }
            lv.setOnItemClickListener { _, _, pos, _ ->
                val row = adapter.getItem(pos)
                if (row != null) rowActions(ctx, overlay, row)
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Queue khul nahi payi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun rowActions(ctx: Context, overlay: Boolean, r: DlRow) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        when (r.status) {
            DlStatus.RUNNING, DlStatus.QUEUED -> {
                labels.add("⏸ Pause"); acts.add { DlEngine.pause(r.id) }
                labels.add("✕ Cancel (adhuri file hatao)"); acts.add { DlEngine.remove(r.id) }
            }
            DlStatus.PAUSED -> {
                labels.add("▶ Resume"); acts.add { DlEngine.resume(r.id) }
                labels.add("✕ Cancel (adhuri file hatao)"); acts.add { DlEngine.remove(r.id) }
            }
            DlStatus.FAILED -> {
                labels.add("🔁 Retry / Resume"); acts.add { DlEngine.resume(r.id) }
                labels.add("✕ Hatao"); acts.add { DlEngine.remove(r.id) }
            }
            DlStatus.DONE -> {
                labels.add("📂 Kholo"); acts.add { openFile(ctx, r) }
                labels.add("List se hatao (file safe)"); acts.add { DlEngine.remove(r.id) }
                labels.add("🗑 File bhi delete karo"); acts.add { confirmDelete(ctx, overlay, r) }
            }
        }
        try {
            show(
                AlertDialog.Builder(themed(ctx))
                    .setTitle(r.name)
                    .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
                    .setNegativeButton("Cancel", null),
                overlay
            )
        } catch (e: Exception) {
        }
    }

    private fun confirmDelete(ctx: Context, overlay: Boolean, r: DlRow) {
        try {
            show(
                AlertDialog.Builder(themed(ctx))
                    .setTitle("File delete karein?")
                    .setMessage(r.name + "\n" + r.path)
                    .setPositiveButton("Delete") { _, _ ->
                        DlEngine.removeWithFile(r.id)
                        Toast.makeText(ctx, "File delete ho gayi", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Rehne do", null),
                overlay
            )
        } catch (e: Exception) {
        }
    }

    private fun openFile(ctx: Context, r: DlRow) {
        val i = DlStorage.viewIntent(ctx, r.savedUri, DlStorage.mimeFor(r.name, r.mime))
        if (i == null) {
            Toast.makeText(ctx, "File nahi khul payi", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Is file ko kholne wali app nahi mili", Toast.LENGTH_SHORT).show()
        }
    }
}
