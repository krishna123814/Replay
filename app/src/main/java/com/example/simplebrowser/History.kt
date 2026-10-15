package com.example.simplebrowser

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistItem(val url: String, val title: String, val time: Long)

/**
 * Browsing history: floating window aur normal browser dono record karte hain.
 * Dekhna: ⚙ settings -> 📜 History (search, tap = kholo, long-press = hatao).
 */
object History {

    private const val KEY = "history_v1"
    private const val KEY_ON = "hist_on"
    private const val MAX = 300

    private val lock = Any()
    private var cache: ArrayList<HistItem>? = null

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FloatingService.PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, true)

    private fun list(ctx: Context): ArrayList<HistItem> {
        val c = cache
        if (c != null) return c
        val out = ArrayList<HistItem>()
        val raw = prefs(ctx).getString(KEY, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(HistItem(o.getString("u"), o.optString("t"), o.optLong("ts")))
                }
            } catch (e: Exception) {
            }
        }
        cache = out
        return out
    }

    private fun persist(ctx: Context, l: List<HistItem>) {
        val arr = JSONArray()
        for (h in l) arr.put(JSONObject().put("u", h.url).put("t", h.title).put("ts", h.time))
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun recordable(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    /** Page visit save karo (naya sabse upar; same URL ho to purana hata kar upar). */
    fun record(ctx: Context, url: String?, title: String?) {
        if (!recordable(url) || !isOn(ctx)) return
        val u = url!!
        synchronized(lock) {
            val l = list(ctx)
            var keepTitle = title?.trim().orEmpty()
            val idx = l.indexOfFirst { it.url == u }
            if (idx >= 0) {
                if (keepTitle.isEmpty()) keepTitle = l[idx].title
                l.removeAt(idx)
            }
            l.add(0, HistItem(u, keepTitle, System.currentTimeMillis()))
            while (l.size > MAX) l.removeAt(l.size - 1)
            persist(ctx, l)
        }
    }

    /** Title baad me aata hai: usi URL ki entry me bharo. */
    fun touchTitle(ctx: Context, url: String?, title: String?) {
        if (!recordable(url) || title.isNullOrBlank() || !isOn(ctx)) return
        synchronized(lock) {
            val l = list(ctx)
            val idx = l.indexOfFirst { it.url == url }
            if (idx < 0) return
            val old = l[idx]
            if (old.title == title.trim()) return
            l[idx] = HistItem(old.url, title.trim(), old.time)
            persist(ctx, l)
        }
    }

    fun remove(ctx: Context, url: String) {
        synchronized(lock) {
            val l = list(ctx)
            l.removeAll { it.url == url }
            persist(ctx, l)
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            val l = list(ctx)
            l.clear()
            persist(ctx, l)
        }
    }

    fun snapshot(ctx: Context): List<HistItem> = synchronized(lock) { ArrayList(list(ctx)) }

    // ── UI ────────────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private class HistAdapter(val ctx: Context) : BaseAdapter() {
        var rows: List<HistItem> = emptyList()
        private val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

        fun set(r: List<HistItem>) {
            rows = r
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): HistItem? = rows.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val d = ctx.resources.displayMetrics.density
            val box: LinearLayout
            if (convertView is LinearLayout) {
                box = convertView
            } else {
                box = LinearLayout(ctx)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                val t1 = TextView(ctx)
                t1.setTextColor(Color.WHITE)
                t1.textSize = 14f
                t1.maxLines = 1
                t1.ellipsize = TextUtils.TruncateAt.END
                val t2 = TextView(ctx)
                t2.setTextColor(Color.parseColor("#9aa0ad"))
                t2.textSize = 11f
                t2.maxLines = 1
                t2.ellipsize = TextUtils.TruncateAt.END
                box.addView(t1)
                box.addView(t2)
            }
            val h = rows[position]
            (box.getChildAt(0) as TextView).text = if (h.title.isNotEmpty()) h.title else h.url
            (box.getChildAt(1) as TextView).text = fmt.format(Date(h.time)) + " · " + h.url
            return box
        }
    }

    /** History dialog. onOpen(url): kisi entry par tap karne par. */
    fun show(ctx: Context, overlay: Boolean, onOpen: (String) -> Unit) {
        try {
            val t = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            val d = ctx.resources.displayMetrics.density
            val root = LinearLayout(t)
            root.orientation = LinearLayout.VERTICAL
            val pad = (12 * d).toInt()
            root.setPadding(pad, 0, pad, 0)

            val cb = CheckBox(t)
            cb.text = "History save karo"
            cb.setTextColor(Color.parseColor("#d1d4dc"))
            cb.isChecked = isOn(ctx)
            cb.setOnCheckedChangeListener { _, on ->
                prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
            }
            root.addView(cb)

            val search = EditText(t)
            search.hint = "Search (title ya link)"
            search.setSingleLine(true)
            search.inputType = InputType.TYPE_CLASS_TEXT
            search.setTextColor(Color.WHITE)
            search.setHintTextColor(Color.parseColor("#7d8290"))
            search.textSize = 14f
            root.addView(search)

            val empty = TextView(t)
            empty.setTextColor(Color.parseColor("#9aa0ad"))
            empty.textSize = 13f
            empty.setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            root.addView(empty)

            val lv = ListView(t)
            val adapter = HistAdapter(t)
            lv.adapter = adapter
            val h = (ctx.resources.displayMetrics.heightPixels * 0.5f).toInt()
            root.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))

            fun refresh() {
                val q = search.text.toString().trim().lowercase()
                val all = snapshot(ctx)
                val rows = if (q.isEmpty()) all
                else all.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
                adapter.set(rows)
                empty.text = if (all.isEmpty()) "Abhi koi history nahi hai" else if (rows.isEmpty()) "Kuch nahi mila" else ""
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { refresh() }
            })
            refresh()

            val b = AlertDialog.Builder(t)
                .setTitle("📜 History")
                .setView(root)
                .setNeutralButton("Sab saaf") { _, _ ->
                    try {
                        val c = AlertDialog.Builder(t)
                            .setTitle("Poori history hatayein?")
                            .setPositiveButton("Hatao") { _, _ ->
                                clear(ctx)
                                Toast.makeText(ctx, "History saaf", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Rehne do", null)
                            .create()
                        if (overlay) c.window?.setType(overlayType())
                        c.show()
                    } catch (e: Exception) { }
                }
                .setNegativeButton("Band", null)
            val dlg = b.create()
            if (overlay) dlg.window?.setType(overlayType())

            lv.setOnItemClickListener { _, _, pos, _ ->
                val item = adapter.getItem(pos)
                if (item != null) {
                    dlg.dismiss()
                    onOpen(item.url)
                }
            }
            lv.setOnItemLongClickListener { _, _, pos, _ ->
                val item = adapter.getItem(pos)
                if (item != null) {
                    remove(ctx, item.url)
                    refresh()
                    Toast.makeText(ctx, "Hata diya", Toast.LENGTH_SHORT).show()
                }
                true
            }
            dlg.show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "History khul nahi payi", Toast.LENGTH_SHORT).show()
        }
    }
}
