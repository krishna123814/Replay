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
