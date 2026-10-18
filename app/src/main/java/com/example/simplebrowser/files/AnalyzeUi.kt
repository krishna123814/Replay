package com.example.simplebrowser.files

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "📊 Analyze": folder-wise space. Kisi bhi storage (AIC / HF / Phone / SD / USB) par chalta hai.
 * Folder par dabao to uske andar ka hisaab khulta hai, "⬆ Upar" se wapas.
 */
class AnalyzeUi(
    private val act: AppCompatActivity,
    private val store: Store,
    private val rootUrl: String,
    private val rootLabel: String,
    private val onDone: () -> Unit = {}
) {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MAX_ROWS = 50

    private lateinit var dlg: AlertDialog
    private lateinit var titleTv: TextView
    private lateinit var capTv: TextView
    private lateinit var capBox: LinearLayout
    private lateinit var sumTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var rowsBox: LinearLayout
    private lateinit var upBtn: Button

    private var curUrl = rootUrl
    private var cancel = AtomicBoolean(false)
    private var gen = 0

    fun show(startUrl: String = rootUrl) {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(4))

        titleTv = TextView(act)
        titleTv.textSize = 18f
        titleTv.setTextColor(Color.BLACK)
        titleTv.typeface = Typeface.DEFAULT_BOLD
        titleTv.maxLines = 2
        titleTv.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(titleTv, LinearLayout.LayoutParams(MATCH, WRAP))

        val btns = LinearLayout(act)
        btns.orientation = LinearLayout.HORIZONTAL
        upBtn = Button(act)
        upBtn.text = "⬆ Upar"
        upBtn.setOnClickListener { run(parentOf(curUrl)) }
        val again = Button(act)
        again.text = "🔄 Dobara"
        again.setOnClickListener { run(curUrl) }
        btns.addView(upBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        btns.addView(again, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP))

        capTv = TextView(act)
        capTv.textSize = 14f
        capTv.setTextColor(Color.BLACK)
        capTv.setPadding(0, act.dp(8), 0, 0)
        col.addView(capTv, LinearLayout.LayoutParams(MATCH, WRAP))

        capBox = LinearLayout(act)
        capBox.orientation = LinearLayout.VERTICAL
        col.addView(capBox, LinearLayout.LayoutParams(MATCH, WRAP))

        sumTv = TextView(act)
        sumTv.textSize = 13f
        sumTv.setTextColor(0xFF555555.toInt())
        sumTv.setPadding(0, act.dp(6), 0, act.dp(6))
        col.addView(sumTv, LinearLayout.LayoutParams(MATCH, WRAP))

        statusTv = TextView(act)
        statusTv.textSize = 13f
        statusTv.setTextColor(0xFFE65100.toInt())
        col.addView(statusTv, LinearLayout.LayoutParams(MATCH, WRAP))

        rowsBox = LinearLayout(act)
        rowsBox.orientation = LinearLayout.VERTICAL
        col.addView(rowsBox, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(act)
        scroll.addView(col)

        dlg = AlertDialog.Builder(act)
            .setView(scroll)
            .setNegativeButton("Band", null)
            .create()
        dlg.setOnDismissListener {
            cancel.set(true)
            onDone()
        }
        dlg.show()
        run(startUrl)
    }

    private fun parentOf(u: String): String {
        val p = u.trimEnd('/').substringBeforeLast('/') + "/"
        return if (p.length < rootUrl.length) rootUrl else p
    }

    private fun ui(g: Int, f: () -> Unit) {
        act.runOnUiThread {
            if (g == gen && !act.isFinishing && !act.isDestroyed && dlg.isShowing) f()
        }
    }

    private fun run(url: String) {
        cancel.set(true)                       // pichla scan (agar chal raha ho) band
        val my = AtomicBoolean(false)
        cancel = my
        gen++
        val g = gen
        curUrl = url

        titleTv.text = "📊 " + (if (url == rootUrl) rootLabel else LocalFs.nameOf(url))
        upBtn.visibility = if (url == rootUrl) View.GONE else View.VISIBLE
        capTv.text = ""
        capBox.removeAllViews()
        sumTv.text = ""
        rowsBox.removeAllViews()
        statusTv.text = "⏳ Scan ho raha hai…"

        Thread {
            var res: AnResult? = null
            var err: String? = null
            try {
                res = Analyzer.analyze(url, my) { n ->
                    if (n % 4 == 0) ui(g) { statusTv.text = "⏳ Scan ho raha hai… $n folder dekhe" }
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            val r = res
            var cap: CapInfo? = null
            if (r != null && !my.get()) {
                val complete = r.failed == 0 && !r.cancelled
                cap = Analyzer.capacity(act, store, url, rootUrl, r.total, complete)
                // Poore storage ka scan theek se ho gaya: HF card ke liye yaad rakho
                if (url == rootUrl && complete) {
                    Analyzer.saveUsed(act, Analyzer.keyFor(store, rootUrl), r.total)
                }
            }
            ui(g) {
                if (r == null) {
                    statusTv.text = "❌ " + (err ?: "scan nahi hua")
                } else if (my.get()) {
                    statusTv.text = "⏹ Scan roka gaya"
                } else {
                    render(r, cap)
                }
            }
        }.start()
    }

    private fun render(r: AnResult, cap: CapInfo?) {
        statusTv.text = if (r.failed > 0) "⚠ ${r.failed} folder padh nahi paye, asli total isse zyada ho sakta hai" else ""

        if (cap != null) {
            capTv.text = cap.text
            val f = cap.frac
            if (f != null) capBox.addView(bar(f, true))
        }

        sumTv.text = "Is folder me kul: " + fmtSize(r.total) + " · " + r.files + " files · " + r.rows.size + " items"

        if (r.rows.isEmpty()) {
            val t = TextView(act)
            t.text = "Folder khaali hai"
            rowsBox.addView(t)
            return
        }

        val shown = if (r.rows.size > MAX_ROWS) r.rows.subList(0, MAX_ROWS) else r.rows
        for (row in shown) rowsBox.addView(rowView(row, r.total))
        if (r.rows.size > MAX_ROWS) {
            var rest = 0L
            for (i in MAX_ROWS until r.rows.size) rest += r.rows[i].bytes
            val t = TextView(act)
            t.text = "… aur ${r.rows.size - MAX_ROWS} items (" + fmtSize(rest) + ")"
            t.setTextColor(0xFF555555.toInt())
            t.setPadding(act.dp(4), act.dp(8), 0, act.dp(8))
            rowsBox.addView(t)
        }
    }

    private fun rowView(r: AnRow, total: Long): View {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(act.dp(4), act.dp(8), act.dp(4), act.dp(8))

        val frac = if (total > 0) (r.bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

        val name = TextView(act)
        name.text = (if (r.isDir) "📁 " else "📄 ") + r.name
        name.textSize = 15f
        name.setTextColor(Color.BLACK)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        c.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))

        val sub = TextView(act)
        sub.text = fmtSize(r.bytes) + String.format(Locale.US, " · %.1f%%", frac * 100) +
            (if (r.isDir) " · ${r.files} files" else "")
        sub.textSize = 12f
        sub.setTextColor(0xFF555555.toInt())
        c.addView(sub, LinearLayout.LayoutParams(MATCH, WRAP))

        c.addView(bar(frac, false))

        if (r.isDir) {
            c.setOnClickListener { run(r.url) }
            c.isClickable = true
        }
        return c
    }

    /** Patti: `big` = capacity wali (rang use ke hisaab se), warna neeli. */
    private fun bar(frac: Float, big: Boolean): View {
        val color = if (big) {
            when {
                frac < 0.8f -> 0xFF2E7D32.toInt()
                frac < 0.92f -> 0xFFEF6C00.toInt()
                else -> 0xFFC62828.toInt()
            }
        } else {
            0xFF1565C0.toInt()
        }
        val b = LinearLayout(act)
        b.orientation = LinearLayout.HORIZONTAL
        b.setBackgroundColor(0xFFD0D0D0.toInt())
        val u = View(act)
        u.setBackgroundColor(color)
        b.addView(u, LinearLayout.LayoutParams(0, MATCH, frac.coerceAtLeast(0.01f)))
        b.addView(View(act), LinearLayout.LayoutParams(0, MATCH, (1f - frac).coerceAtLeast(0.01f)))
        val lp = LinearLayout.LayoutParams(MATCH, act.dp(if (big) 8 else 5))
        lp.topMargin = act.dp(4)
        b.layoutParams = lp
        return b
    }
}
