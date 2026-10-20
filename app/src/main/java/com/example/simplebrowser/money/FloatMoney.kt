package com.example.simplebrowser.money

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.simplebrowser.files.OvlBuilder
import com.example.simplebrowser.files.OvlScreen
import com.example.simplebrowser.files.ovlShow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Floating Money Manager (MoneyActivity ki copy, floating window ke liye). */
class FloatMoney(base: Context, intent0: Intent, host: OvlScreen.Host) : OvlScreen(base, intent0, host) {

    internal val BG = Color.parseColor("#131722")
    internal val PANEL = Color.parseColor("#1E222D")
    internal val LINE = Color.parseColor("#2A2E39")
    internal val TXT = Color.parseColor("#D1D4DC")
    internal val MUT = Color.parseColor("#848DA0")
    internal val GREEN = Color.parseColor("#26A69A")
    internal val RED = Color.parseColor("#EF5350")
    internal val ACCENT = Color.parseColor("#FF6150")
    internal val BLUE = Color.parseColor("#2962FF")

    internal lateinit var tabsRow: LinearLayout
    internal lateinit var banner: TextView
    internal lateinit var body: LinearLayout
    internal lateinit var fab: TextView

    internal var tab = "daily"
    internal var year = Calendar.getInstance().get(Calendar.YEAR)
    internal var month = Calendar.getInstance().get(Calendar.MONTH)
    internal var mYear = year                // Monthly tab ka saal
    internal var view = "tx"                 // "tx" | "income" | "expense" (cards se category-wise)
    internal var lastAcct = "Cash"
    internal var started = false

    internal val tabs = listOf("daily" to "Daily", "monthly" to "Monthly", "yearly" to "Yearly", "category" to "Category",
        "analysis" to "Analysis", "total" to "Total", "plan" to "Plan")

    // Part 2 ki state
    internal var ct = "expense"          // Category tab: income / expense
    internal var cp = "month"            // month | year | all | cmp
    internal var cc = ""                 // khuli hui category ki key
    internal var cm: String? = null      // Category detail me khula mahina
    internal var ap = "month"            // Analysis: month | year
    internal var al = "common"           // Analysis line graph: common | sep
    internal var at = "expense"
    internal val asel = HashMap<String, MutableList<String>>()
    internal var onRenderHook: (() -> Unit)? = null
    internal val impLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importBackup(uri)
    }

    // ───────────────────────── chhote helpers ─────────────────────────

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    internal fun tv(text: String, sp: Float = 14f, color: Int = TXT, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD)
        return t
    }

    internal fun box(fill: Int, stroke: Int = 0, radiusDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        if (stroke != 0) g.setStroke(dp(1), stroke)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    internal fun hrow(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    internal fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    internal fun canEdit(): Boolean {
        if (!MoneyRepo.loaded || MoneyRepo.readOnly) { toast("Server se connect nahi, abhi edit nahi ho sakta"); return false }
        return true
    }

    internal fun persist() {
        MoneyRepo.touch()
        MoneyRepo.save { ok, msg ->
            if (!ok && !isFinishing) Toast.makeText(this, "⚠ Server save fail: $msg (⟳ dabake dobara try karo)", Toast.LENGTH_LONG).show()
        }
    }

    internal fun prompt(title: String, initial: String, hint: String, numeric: Boolean, ok: (String) -> Unit) {
        val et = EditText(this)
        et.setText(initial)
        et.hint = hint
        et.setSingleLine(true)
        if (numeric) et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val holder = FrameLayout(this)
        holder.setPadding(dp(20), dp(8), dp(20), 0)
        holder.addView(et)
        OvlBuilder(this)
            .setTitle(title)
            .setView(holder)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    internal fun confirm(msg: String, ok: () -> Unit) {
        OvlBuilder(this)
            .setMessage(msg)
            .setPositiveButton("Haan") { _, _ -> ok() }
            .setNegativeButton("Nahi", null)
            .show()
    }

    // ───────────────────────── screen ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.fitsSystemWindows = true

        // header
        val hdr = hrow()
        hdr.setBackgroundColor(PANEL)
        hdr.setPadding(dp(8), dp(6), dp(8), dp(6))
        val back = tv("←", 22f)
        back.setPadding(dp(10), dp(4), dp(12), dp(4))
        back.setOnClickListener { finish() }
        hdr.addView(back)
        val title = tv("💵 Money Manager", 16f, Color.WHITE, true)
        hdr.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ref = tv("⟳", 22f)
        ref.setPadding(dp(12), dp(4), dp(12), dp(4))
        ref.setOnClickListener { refreshFromServer(false) }
        hdr.addView(ref)
        val cfg = tv("⚙", 22f)
        cfg.setPadding(dp(12), dp(4), dp(8), dp(4))
        cfg.setOnClickListener { if (canEdit()) catsDialog() }
        hdr.addView(cfg)
        root.addView(hdr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // tabs
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.setBackgroundColor(PANEL)
        tabsRow = LinearLayout(this)
        tabsRow.orientation = LinearLayout.HORIZONTAL
        hs.addView(tabsRow)
        root.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // banner
        banner = tv("", 12f, Color.WHITE)
        banner.setPadding(dp(12), dp(6), dp(12), dp(6))
        banner.visibility = View.GONE
        root.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // content + fab
        val frame = FrameLayout(this)
        val sv = ScrollView(this)
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, 0, 0, dp(96))
        sv.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fab = tv("+", 30f, Color.WHITE)
        fab.gravity = Gravity.CENTER
        fab.background = box(ACCENT, 0, 28)
        fab.setOnClickListener { openAdd(null) }
        val flp = FrameLayout.LayoutParams(dp(56), dp(56))
        flp.gravity = Gravity.BOTTOM or Gravity.END
        flp.setMargins(0, 0, dp(16), dp(16))
        frame.addView(fab, flp)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        render()
        refreshFromServer(true)
    }

    override fun onResume() {
        super.onResume()
        if (started && MoneyRepo.loaded && !MoneyRepo.dirty) refreshFromServer(true)
        started = true
    }

    internal fun setBanner(msg: String, color: Int) {
        if (msg.isEmpty()) { banner.visibility = View.GONE; return }
        banner.text = msg
        banner.setBackgroundColor(color)
        banner.visibility = View.VISIBLE
    }

    /** silent = chupchap (banner nahi) */
    internal fun refreshFromServer(silent: Boolean) {
        if (MoneyRepo.dirty && MoneyRepo.loaded && !MoneyRepo.readOnly) {
            // pehle jo save nahi ho paya wo bhejo, tab hi load (warna local change mit jaata)
            MoneyRepo.save { ok, msg ->
                if (isFinishing) return@save
                if (ok) loadNow(silent) else { setBanner("⚠ Pichhla change server tak nahi gaya: $msg", Color.parseColor("#B71C1C")); render() }
            }
        } else loadNow(silent)
    }

    internal fun loadNow(silent: Boolean) {
        if (!silent) setBanner("Server se load ho raha hai…", Color.parseColor("#37474F"))
        MoneyRepo.load(this) { ok, msg ->
            if (isFinishing || isDestroyed) return@load
            if (ok) setBanner("", 0)
            else if (MoneyRepo.loaded) setBanner("📴 $msg — purana cache dikh raha hai (sirf dekh sakte ho). ⟳ se dobara try karo.", Color.parseColor("#E65100"))
            else setBanner("⚠ $msg", Color.parseColor("#B71C1C"))
            render()
        }
    }

    internal fun renderTabs() {
        tabsRow.removeAllViews()
        for ((k, label) in tabs) {
            val t = tv(label, 12f, if (tab == k) Color.WHITE else MUT, tab == k)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(18), dp(11), dp(18), dp(9))
            if (tab == k) { t.setTextColor(ACCENT); t.background = box(BG, 0, 0) }
            t.setOnClickListener {
                tab = k; view = "tx"
                cc = ""; cm = null
                if (k == "monthly" || k == "category" || k == "analysis") mYear = year
                render()
            }
            tabsRow.addView(t)
        }
    }

    internal fun render() {
        renderInner()
        onRenderHook?.invoke()
    }

    internal fun renderInner() {
        renderTabs()
        body.removeAllViews()
        fab.visibility = if (MoneyRepo.loaded && !MoneyRepo.readOnly && tab != "plan") View.VISIBLE else View.GONE
        if (!MoneyRepo.loaded) {
            val e = tv("Data load nahi hua.\n\nPehle 📁 File Manager me login karo, phir yaha ⟳ dabao.", 14f, MUT)
            e.gravity = Gravity.CENTER
            e.setPadding(dp(24), dp(40), dp(24), dp(20))
            body.addView(e)
            return
        }
        when (tab) {
            "monthly" -> renderMonthly()
            "yearly" -> renderYearly()
            "category" -> renderCategory()
            "analysis" -> renderAnalysis()
            "plan" -> renderPlan()
            "total" -> renderTotal()
            else -> renderDaily()
        }
    }

    // ───────────────────────── common widgets ─────────────────────────

    internal fun navRow(label: String, onPrev: () -> Unit, onNext: () -> Unit): View {
        val r = hrow()
        r.gravity = Gravity.CENTER
        r.setPadding(0, dp(10), 0, dp(6))
        val p = tv("‹", 26f)
        p.setPadding(dp(18), 0, dp(18), 0)
        p.setOnClickListener { onPrev() }
        val n = tv("›", 26f)
        n.setPadding(dp(18), 0, dp(18), 0)
        n.setOnClickListener { onNext() }
        r.addView(p)
        r.addView(tv(label, 15f, Color.WHITE, true))
        r.addView(n)
        return r
    }

    internal fun card(label: String, value: Double, color: Int, on: Boolean, click: (() -> Unit)?): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(10), dp(8), dp(10), dp(8))
        c.background = box(PANEL, if (on) color else LINE, 10)
        c.addView(tv(label, 11f, MUT))
        c.addView(tv(MoneyRepo.fmt(value), 15f, color, true))
        if (click != null) c.setOnClickListener { click() }
        return c
    }

    internal fun cardsRow(inc: Double, exp: Double, clickable: Boolean): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(dp(10), dp(4), dp(10), dp(4))
        val a = card("Income", inc, GREEN, view == "income", if (clickable) ({ view = if (view == "income") "tx" else "income"; render() }) else null)
        val b = card("Expenses", exp, RED, view == "expense", if (clickable) ({ view = if (view == "expense") "tx" else "expense"; render() }) else null)
        val lp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp1.setMargins(0, 0, dp(4), 0)
        val lp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp2.setMargins(dp(4), 0, 0, 0)
        r.addView(a, lp1)
        r.addView(b, lp2)
        return r
    }

    internal fun colView(label: String, value: String, color: Int, click: (() -> Unit)?): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(tv(label, 11f, MUT))
        c.addView(tv(value, 14f, color, true))
        if (click != null) c.setOnClickListener { click() }
        return c
    }

    internal fun balanceRow(bal: Double): View {
        val a = MoneyRepo.actual()
        val df = if (a == null) null else Math.round((a - bal) * 100) / 100.0
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(dp(10), dp(8), dp(10), dp(6))
        val dcol = if (df == null) TXT else if (df >= 0 && df <= 10) GREEN else RED
        r.addView(colView("Balance", MoneyRepo.fmt(bal), if (bal >= 0) GREEN else RED, null), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(colView("Actual Balance ✎", if (a == null) "—" else MoneyRepo.fmt(a), TXT) { editActual() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(colView("Difference", if (df == null) "—" else MoneyRepo.fmt(df), dcol, null), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    internal fun editActual() {
        if (!canEdit()) return
        val cur = MoneyRepo.actual()
        prompt("Actual Balance (jo asli me paas hai). Khaali = hata do", if (cur == null) "" else cur.toString(), "0.00", true) { s ->
            val c = s.replace("₹", "").replace(",", "").trim()
            if (c.isEmpty()) { MoneyRepo.setActual(null); persist(); render() }
            else {
                val n = c.toDoubleOrNull()
                if (n == null) toast("Sahi number daalo") else { MoneyRepo.setActual(n); persist(); render() }
            }
        }
    }

    internal fun section(text: String): View {
        val t = tv(text.uppercase(), 11f, MUT)
        t.setPadding(dp(12), dp(12), dp(12), dp(4))
        return t
    }

    internal fun empty(text: String): View {
        val t = tv(text, 14f, MUT)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(18), dp(28), dp(18), dp(28))
        return t
    }

    internal fun kv(k: String, v: String, color: Int = TXT): View {
        val r = hrow()
        r.setPadding(dp(14), dp(9), dp(14), dp(9))
        r.addView(tv(k, 14f, MUT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(tv(v, 14f, color, true))
        return r
    }

    // ───────────────────────── Daily ─────────────────────────

    internal fun inMonth(ts: Long, y: Int, mo: Int): Boolean {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return c.get(Calendar.YEAR) == y && c.get(Calendar.MONTH) == mo
    }

    internal fun renderDaily() {
        val monthLbl = SimpleDateFormat("MMM yyyy", Locale("en", "IN")).format(Date(Calendar.getInstance().also { it.clear(); it.set(year, month, 1) }.timeInMillis))
        body.addView(navRow(monthLbl, { stepMonth(-1) }, { stepMonth(1) }))

        val txs = MoneyRepo.txs().filter { inMonth(it.optLong("ts"), year, month) }.sortedByDescending { it.optLong("ts") }
        var inc = 0.0
        var exp = 0.0
        for (t in txs) if (t.optString("type") == "income") inc += t.optDouble("amount", 0.0) else exp += t.optDouble("amount", 0.0)

        body.addView(cardsRow(inc, exp, true))
        body.addView(balanceRow(inc - exp))
        if (view == "tx") budgetBanner(year, month)?.let { body.addView(it) }

        if (view == "income" || view == "expense") {
            val isInc = view == "income"
            val tot = if (isInc) inc else exp
            val col = if (isInc) GREEN else RED
            body.addView(section((if (isInc) "Income" else "Expenses") + " — category wise (card dobara dabao = wapas)"))
            val names = LinkedHashMap<String, String>()
            val sums = LinkedHashMap<String, Double>()
            val subs = LinkedHashMap<String, LinkedHashMap<String, Double>>()
            for (t in txs) {
                if (t.optString("type") != view) continue
                val cid = t.optString("cat")
                val k = if (cid.isEmpty()) "n:" + t.optString("catName") else cid
                names[k] = MoneyRepo.catName(t)
                sums[k] = (sums[k] ?: 0.0) + t.optDouble("amount", 0.0)
                val sn = MoneyRepo.subName(t)
                if (sn.isNotEmpty()) {
                    val m = subs.getOrPut(k) { LinkedHashMap() }
                    m[sn] = (m[sn] ?: 0.0) + t.optDouble("amount", 0.0)
                }
            }
            if (sums.isEmpty()) body.addView(empty("Is mahine koi entry nahi."))
            for (k in sums.keys.sortedByDescending { sums[it] ?: 0.0 }) {
                val s = sums[k] ?: 0.0
                val pc = if (tot > 0) Math.round(s * 100 / tot) else 0L
                val r = hrow()
                r.setPadding(dp(12), dp(8), dp(12), dp(8))
                val pct = tv("$pc%", 11f, TXT)
                pct.gravity = Gravity.CENTER
                pct.setPadding(dp(6), dp(1), dp(6), dp(1))
                pct.background = box(LINE, 0, 5)
                pct.minWidth = dp(38)
                r.addView(pct)
                val nm = tv(names[k] ?: "?", 14f)
                nm.setPadding(dp(10), 0, 0, 0)
                r.addView(nm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(tv(MoneyRepo.fmt(s), 14f, col, true))
                body.addView(r)
                val sm = subs[k]
                if (sm != null) for ((sn, sv) in sm) {
                    val sr = hrow()
                    sr.setPadding(dp(34), dp(3), dp(12), dp(3))
                    sr.addView(tv("↳ $sn", 12f, MUT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    sr.addView(tv(MoneyRepo.fmt(sv), 12f, MUT))
                    body.addView(sr)
                }
            }
            return
        }

        if (txs.isEmpty()) {
            body.addView(empty(
                if (MoneyRepo.txs().isNotEmpty() || MoneyRepo.cats().isNotEmpty()) "Is mahine koi entry nahi."
                else "Abhi kuch nahi hai.\nPehle ⚙ se categories banao, phir + dabake entry jodo."))
            return
        }

        // din ke hisaab se groups
        val order = ArrayList<String>()
        val groups = HashMap<String, ArrayList<JSONObject>>()
        val cal = Calendar.getInstance()
        for (t in txs) {
            cal.timeInMillis = t.optLong("ts")
            val k = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
            if (!groups.containsKey(k)) { groups[k] = ArrayList(); order.add(k) }
            groups[k]!!.add(t)
        }
        val wk = SimpleDateFormat("EEE", Locale("en", "IN"))
        for (k in order) {
            val list = groups[k]!!
            var di = 0.0
            var de = 0.0
            for (t in list) if (t.optString("type") == "income") di += t.optDouble("amount", 0.0) else de += t.optDouble("amount", 0.0)
            cal.timeInMillis = list[0].optLong("ts")
            val h = hrow()
            h.setBackgroundColor(Color.parseColor("#1A1E29"))
            h.setPadding(dp(12), dp(6), dp(12), dp(6))
            val hl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            hl.topMargin = dp(8)
            h.addView(tv(cal.get(Calendar.DAY_OF_MONTH).toString(), 15f, Color.WHITE, true))
            val wd = tv(wk.format(Date(cal.timeInMillis)), 12f, MUT)
            wd.setPadding(dp(8), 0, 0, 0)
            h.addView(wd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (di > 0) { val x = tv(MoneyRepo.fmt(di), 12f, GREEN); x.setPadding(0, 0, dp(10), 0); h.addView(x) }
            if (de > 0) h.addView(tv(MoneyRepo.fmt(de), 12f, RED))
            body.addView(h, hl)
            for (t in list) body.addView(txRow(t))
        }
    }

    internal fun txRow(t: JSONObject): View {
        val r = hrow()
        r.setPadding(dp(12), dp(9), dp(12), dp(9))
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val sn = MoneyRepo.subName(t)
        val ac = t.optString("acct")
        var title = MoneyRepo.catName(t)
        if (sn.isNotEmpty()) title += " › $sn"
        if (ac.isNotEmpty() && ac != "Cash") title += " · $ac"
        left.addView(tv(title, 13f))
        val note = t.optString("note")
        if (note.isNotEmpty()) left.addView(tv(note, 11f, MUT))
        r.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val income = t.optString("type") == "income"
        r.addView(tv(MoneyRepo.fmt(t.optDouble("amount", 0.0)), 14f, if (income) GREEN else RED, true))
        r.setOnClickListener { openAdd(t.optString("id")) }
        return r
    }

    internal fun stepMonth(d: Int) {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month + d, 1)
        year = c.get(Calendar.YEAR)
        month = c.get(Calendar.MONTH)
        view = "tx"
        render()
    }

    // ───────────────────────── Monthly ─────────────────────────

    internal fun renderMonthly() {
        body.addView(navRow(mYear.toString(), { mYear--; render() }, { mYear++; render() }))
        val ys = MoneyRepo.sum(mYear, null)
        body.addView(cardsRow(ys.i, ys.e, false))
        val bal = tv("Balance  " + MoneyRepo.fmt(ys.i - ys.e), 14f, if (ys.i >= ys.e) GREEN else RED, true)
        bal.gravity = Gravity.CENTER
        bal.setPadding(0, dp(8), 0, dp(6))
        body.addView(bal)
        val now = Calendar.getInstance()
        val mn = SimpleDateFormat("MMM", Locale("en", "IN"))
        for (k in 11 downTo 0) {
            val s = MoneyRepo.sum(mYear, k)
            val c = Calendar.getInstance()
            c.clear(); c.set(mYear, k, 1)
            val cur = now.get(Calendar.YEAR) == mYear && now.get(Calendar.MONTH) == k
            val r = hrow()
            r.setPadding(dp(12), dp(10), dp(12), dp(10))
            val nm = tv(mn.format(Date(c.timeInMillis)), 14f, if (cur) ACCENT else Color.WHITE, true)
            r.addView(nm, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            val mid = LinearLayout(this)
            mid.orientation = LinearLayout.VERTICAL
            mid.gravity = Gravity.END
            mid.addView(tv(MoneyRepo.fmt(s.i), 12f, GREEN))
            mid.addView(tv(MoneyRepo.fmt(s.e), 12f, RED))
            r.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val b = tv(MoneyRepo.fmt(s.i - s.e), 13f, if (s.i >= s.e) GREEN else RED, true)
            b.gravity = Gravity.END
            r.addView(b, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
            r.setOnClickListener { year = mYear; month = k; tab = "daily"; view = "tx"; render() }
            body.addView(r)
        }
    }

    // ───────────────────────── Total ─────────────────────────

    internal fun renderTotal() {
        val monthLbl = SimpleDateFormat("MMM yyyy", Locale("en", "IN")).format(Date(Calendar.getInstance().also { it.clear(); it.set(year, month, 1) }.timeInMillis))
        body.addView(navRow(monthLbl, { stepMonth(-1) }, { stepMonth(1) }))
        val cs = MoneyRepo.sum(year, month)
        body.addView(cardsRow(cs.i, cs.e, false))

        val pc = Calendar.getInstance()
        pc.clear(); pc.set(year, month - 1, 1)
        val ps = MoneyRepo.sum(pc.get(Calendar.YEAR), pc.get(Calendar.MONTH))
        body.addView(section("Pichhle mahine se"))
        body.addView(kv("Pichhle mahine ka kharcha", MoneyRepo.fmt(ps.e), RED))
        body.addView(kv("Kharcha (is mahine / pichhla)", if (ps.e > 0) Math.round(cs.e * 100 / ps.e).toString() + "%" else "—"))
        body.addView(kv("Pichhle mahine ka balance", MoneyRepo.fmt(ps.i - ps.e), if (ps.i >= ps.e) GREEN else RED))

        val all = MoneyRepo.sumAll()
        body.addView(section("Ab tak ka poora (sab mahine)"))
        body.addView(kv("Total Income", MoneyRepo.fmt(all.i), GREEN))
        body.addView(kv("Total Expenses", MoneyRepo.fmt(all.e), RED))
        body.addView(kv("Total Balance", MoneyRepo.fmt(all.i - all.e), if (all.i >= all.e) GREEN else RED))
        body.addView(kv("Entries", MoneyRepo.txs().size.toString()))

        // account wise
        body.addView(section("Account wise balance (ab tak)"))
        val ai = LinkedHashMap<String, Double>()
        val ae = LinkedHashMap<String, Double>()
        for (a in MoneyRepo.accts()) { ai[a] = 0.0; ae[a] = 0.0 }
        for (t in MoneyRepo.txs()) {
            var k = t.optString("acct")
            if (k.isEmpty()) k = "Cash"
            if (!ai.containsKey(k)) { ai[k] = 0.0; ae[k] = 0.0 }
            if (t.optString("type") == "income") ai[k] = (ai[k] ?: 0.0) + t.optDouble("amount", 0.0)
            else ae[k] = (ae[k] ?: 0.0) + t.optDouble("amount", 0.0)
        }
        for (k in ai.keys) {
            val i = ai[k] ?: 0.0
            val e = ae[k] ?: 0.0
            val r = hrow()
            r.setPadding(dp(12), dp(9), dp(12), dp(9))
            r.addView(tv(k, 13f, Color.WHITE, true), LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
            val mid = LinearLayout(this)
            mid.orientation = LinearLayout.VERTICAL
            mid.gravity = Gravity.END
            mid.addView(tv(MoneyRepo.fmt(i), 12f, GREEN))
            mid.addView(tv(MoneyRepo.fmt(e), 12f, RED))
            r.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val b = tv(MoneyRepo.fmt(i - e), 13f, if (i >= e) GREEN else RED, true)
            b.gravity = Gravity.END
            r.addView(b, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(r)
        }
        body.addView(kv("Sab account milake", MoneyRepo.fmt(all.i - all.e), if (all.i >= all.e) GREEN else RED))
    }

    // ───────────────────────── Add / Edit entry ─────────────────────────

    private class AddState(var type: String) {
        var cat = ""
        var sub = ""
        var acct = "Cash"
        var edit = ""
        val cal: Calendar = Calendar.getInstance()
    }

    internal fun chip(text: String, on: Boolean, dashed: Boolean = false): TextView {
        val c = tv(text, 12f, if (on) Color.parseColor("#90CAF9") else TXT)
        c.setPadding(dp(12), dp(6), dp(12), dp(6))
        c.background = box(if (on) Color.parseColor("#1E3A5F") else BG, if (on) BLUE else LINE, 16)
        if (dashed) c.setTextColor(MUT)
        return c
    }

    internal fun openAdd(editId: String?) {
        if (!canEdit()) return
        if (MoneyRepo.rkEnsure()) persist()
        val tx = if (editId.isNullOrEmpty()) null else MoneyRepo.txs().firstOrNull { it.optString("id") == editId }
        if (!editId.isNullOrEmpty() && tx == null) return

        val st = AddState(if (tx != null) (if (tx.optString("type") == "income") "income" else "expense") else (if (view == "income") "income" else "expense"))
        if (tx != null) {
            st.edit = tx.optString("id")
            st.acct = tx.optString("acct").ifEmpty { "Cash" }
            st.cal.timeInMillis = tx.optLong("ts", System.currentTimeMillis())
        } else st.acct = lastAcct

        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(12), dp(14), dp(16))
        sv.addView(col)

        val dlg = OvlBuilder(this).setView(sv).create()

        // title
        val th = hrow()
        th.addView(tv(if (tx != null) "Entry badlo" else "Nayi entry", 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = tv("✕", 18f)
        x.setPadding(dp(10), 0, 0, 0)
        x.setOnClickListener { dlg.dismiss() }
        th.addView(x)
        col.addView(th)

        // type toggle
        val tg = LinearLayout(this)
        tg.orientation = LinearLayout.HORIZONTAL
        val tIn = tv("Income", 14f, MUT)
        val tEx = tv("Expense", 14f, MUT)
        for (t in listOf(tIn, tEx)) t.gravity = Gravity.CENTER
        val tl1 = LinearLayout.LayoutParams(0, dp(40), 1f); tl1.setMargins(0, dp(10), dp(4), 0)
        val tl2 = LinearLayout.LayoutParams(0, dp(40), 1f); tl2.setMargins(dp(4), dp(10), 0, 0)
        tg.addView(tIn, tl1)
        tg.addView(tEx, tl2)
        col.addView(tg)

        fun label(s: String): View { val l = tv(s, 11f, MUT); l.setPadding(0, dp(12), 0, dp(4)); return l }

        // date / time
        col.addView(label("Date & time"))
        val dtRow = LinearLayout(this)
        dtRow.orientation = LinearLayout.HORIZONTAL
        val dBtn = tv("", 14f, Color.WHITE)
        val tBtn = tv("", 14f, Color.WHITE)
        for (b in listOf(dBtn, tBtn)) { b.gravity = Gravity.CENTER; b.background = box(BG, LINE, 8); b.setPadding(dp(8), dp(10), dp(8), dp(10)) }
        val dfmt = SimpleDateFormat("dd MMM yyyy", Locale("en", "IN"))
        val tfmt = SimpleDateFormat("hh:mm a", Locale("en", "IN"))
        fun showDT() { dBtn.text = dfmt.format(Date(st.cal.timeInMillis)); tBtn.text = tfmt.format(Date(st.cal.timeInMillis)) }
        showDT()
        dBtn.setOnClickListener {
            ovlShow(this, DatePickerDialog(this, { _, yy, mm, dd -> st.cal.set(yy, mm, dd); showDT() },
                st.cal.get(Calendar.YEAR), st.cal.get(Calendar.MONTH), st.cal.get(Calendar.DAY_OF_MONTH)))
        }
        tBtn.setOnClickListener {
            ovlShow(this, TimePickerDialog(this, { _, hh, mi -> st.cal.set(Calendar.HOUR_OF_DAY, hh); st.cal.set(Calendar.MINUTE, mi); showDT() },
                st.cal.get(Calendar.HOUR_OF_DAY), st.cal.get(Calendar.MINUTE), false))
        }
        val dl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); dl.setMargins(0, 0, dp(4), 0)
        val tl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); tl.setMargins(dp(4), 0, 0, 0)
        dtRow.addView(dBtn, dl)
        dtRow.addView(tBtn, tl)
        col.addView(dtRow)

        // amount
        col.addView(label("Amount"))
        val amt = EditText(this)
        amt.hint = "0.00"
        amt.setTextColor(Color.WHITE)
        amt.setHintTextColor(MUT)
        amt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        amt.setSingleLine(true)
        if (tx != null) amt.setText(trimNum(tx.optDouble("amount", 0.0)))
        col.addView(amt)

        // account / category / sub chips
        col.addView(label("Account"))
        val acFlow = FlowLayout(this); acFlow.hGap = dp(6); acFlow.vGap = dp(6)
        col.addView(acFlow)
        col.addView(label("Category"))
        val ccFlow = FlowLayout(this); ccFlow.hGap = dp(6); ccFlow.vGap = dp(6)
        col.addView(ccFlow)
        col.addView(label("Subcategory"))
        val scFlow = FlowLayout(this); scFlow.hGap = dp(6); scFlow.vGap = dp(6)
        col.addView(scFlow)

        // note
        col.addView(label("Note"))
        val note = EditText(this)
        note.hint = "Note (optional)"
        note.setTextColor(Color.WHITE)
        note.setHintTextColor(MUT)
        note.setSingleLine(true)
        if (tx != null) note.setText(tx.optString("note"))
        col.addView(note)

        val err = tv("", 12f, RED)
        err.setPadding(0, dp(8), 0, 0)
        col.addView(err)

        fun refreshChips() {
            tIn.background = box(BG, if (st.type == "income") GREEN else LINE, 8)
            tEx.background = box(BG, if (st.type == "expense") RED else LINE, 8)
            tIn.setTextColor(if (st.type == "income") GREEN else MUT)
            tEx.setTextColor(if (st.type == "expense") RED else MUT)

            acFlow.removeAllViews()
            val al = ArrayList(MoneyRepo.accts())
            if (!al.contains(st.acct)) al.add(st.acct)
            for (a in al) {
                val c = chip(a, st.acct == a)
                c.setOnClickListener { st.acct = a; refreshChipsRef?.invoke() }
                acFlow.addView(c)
            }
            val an = chip("＋ Nayi", false, true)
            an.setOnClickListener {
                prompt("Account ka naam", "", "jaise Bank, UPI", false) { n ->
                    if (n.isNotEmpty()) { st.acct = MoneyRepo.addAcct(n); persist(); refreshChipsRef?.invoke() }
                }
            }
            acFlow.addView(an)

            ccFlow.removeAllViews()
            for (c0 in MoneyRepo.catsOf(st.type)) {
                val id = c0.optString("id")
                val c = chip(c0.optString("name"), st.cat == id)
                c.setOnClickListener { st.cat = id; st.sub = ""; refreshChipsRef?.invoke() }
                ccFlow.addView(c)
            }
            val cn = chip("＋ Nayi", false, true)
            cn.setOnClickListener {
                prompt("Nayi category", "", "Category ka naam", false) { n ->
                    if (n.isNotEmpty()) { val c1 = MoneyRepo.addCat(st.type, n); st.cat = c1.optString("id"); st.sub = ""; persist(); refreshChipsRef?.invoke() }
                }
            }
            ccFlow.addView(cn)

            scFlow.removeAllViews()
            val cc = MoneyRepo.catOf(st.cat)
            if (cc == null) {
                scFlow.addView(tv("Pehle category chuno", 12f, MUT))
            } else {
                for (s0 in MoneyRepo.subsOf(cc)) {
                    val sid = s0.optString("id")
                    val c = chip(s0.optString("name"), st.sub == sid)
                    c.setOnClickListener { st.sub = if (st.sub == sid) "" else sid; refreshChipsRef?.invoke() }
                    scFlow.addView(c)
                }
                val sn = chip("＋ Nayi", false, true)
                sn.setOnClickListener {
                    prompt("Nayi subcategory", "", "Subcategory ka naam", false) { n ->
                        if (n.isNotEmpty()) { val s1 = MoneyRepo.addSub(cc, n); st.sub = s1.optString("id"); persist(); refreshChipsRef?.invoke() }
                    }
                }
                scFlow.addView(sn)
            }
        }
        refreshChipsRef = ::refreshChips
        tIn.setOnClickListener { if (st.type != "income") { st.type = "income"; st.cat = ""; st.sub = ""; refreshChips() } }
        tEx.setOnClickListener { if (st.type != "expense") { st.type = "expense"; st.cat = ""; st.sub = ""; refreshChips() } }

        if (tx != null) {
            val oc = MoneyRepo.catOf(tx.optString("cat"))
            if (oc != null) {
                st.cat = oc.optString("id")
                if (MoneyRepo.subOf(oc, tx.optString("sub")) != null) st.sub = tx.optString("sub")
            } else err.text = "Purani category \"" + tx.optString("catName") + "\" delete ho chuki hai — nayi chuno."
        }
        refreshChips()

        fun save(cont: Boolean) {
            val a = amt.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
            if (!(a > 0)) { err.setTextColor(RED); err.text = "Amount daalo (0 se zyada)."; return }
            if (st.cat.isEmpty()) { err.setTextColor(RED); err.text = "Category chuno ya ＋ Nayi se banao."; return }
            val c = MoneyRepo.catOf(st.cat)
            val s = MoneyRepo.subOf(c, st.sub)
            if (st.edit.isEmpty()) {
                val pend = MoneyRepo.rkPending()
                val isRk = st.type == "expense" && Math.round(a * 100) == 50000L && c != null && MoneyRepo.rkNorm(c.optString("name")) == "rich kids"
                if (pend > 0 && !isRk) {
                    err.setTextColor(RED)
                    err.text = "⛔ Pehle 'rich kids' category me ₹500 ki Expense entry karo ($pend mahine ki baaki). Uske baad hi baaki entries hongi."
                    return
                }
            }
            val rec = JSONObject()
            rec.put("id", if (st.edit.isNotEmpty()) st.edit else MoneyRepo.newId("t"))
            rec.put("type", st.type)
            rec.put("ts", st.cal.timeInMillis)
            rec.put("amount", Math.round(a * 100) / 100.0)
            rec.put("cat", st.cat)
            rec.put("sub", st.sub)
            rec.put("acct", st.acct)
            rec.put("catName", c?.optString("name") ?: "")
            rec.put("subName", s?.optString("name") ?: "")
            rec.put("note", note.text.toString().trim())
            if (!MoneyRepo.putTx(rec, st.edit)) { err.setTextColor(RED); err.text = "Entry nahi mili (shayad delete ho gayi)."; return }
            lastAcct = st.acct
            year = st.cal.get(Calendar.YEAR)
            month = st.cal.get(Calendar.MONTH)
            persist()
            render()
            if (cont) {
                amt.setText("")
                note.setText("")
                err.setTextColor(GREEN)
                err.text = "✓ Save hua — agli entry daalo"
                amt.requestFocus()
            } else dlg.dismiss()
        }

        // buttons
        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        br.setPadding(0, dp(14), 0, 0)
        val bSave = tv("Save", 15f, Color.WHITE, true)
        bSave.gravity = Gravity.CENTER
        bSave.background = box(ACCENT, 0, 8)
        bSave.setOnClickListener { save(false) }
        val sl = LinearLayout.LayoutParams(0, dp(46), 1f)
        br.addView(bSave, sl)
        if (tx == null) {
            val bCont = tv("Continue", 15f, TXT, true)
            bCont.gravity = Gravity.CENTER
            bCont.background = box(BG, LINE, 8)
            bCont.setOnClickListener { save(true) }
            val cl = LinearLayout.LayoutParams(0, dp(46), 1f)
            cl.setMargins(dp(8), 0, 0, 0)
            br.addView(bCont, cl)
        } else {
            val bDel = tv("🗑 Delete", 14f, RED, true)
            bDel.gravity = Gravity.CENTER
            bDel.background = box(BG, RED, 8)
            bDel.setOnClickListener {
                confirm("Ye entry pakka delete karni hai?") {
                    MoneyRepo.deleteTx(st.edit)
                    persist()
                    render()
                    dlg.dismiss()
                }
            }
            val cl = LinearLayout.LayoutParams(dp(110), dp(46))
            cl.setMargins(dp(8), 0, 0, 0)
            br.addView(bDel, cl)
        }
        col.addView(br)

        dlg.show()
        dlg.window?.let {
            it.setBackgroundDrawable(box(PANEL, 0, 14))
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setGravity(Gravity.BOTTOM)
            it.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    internal var refreshChipsRef: (() -> Unit)? = null

    internal fun trimNum(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString() else d.toString()

    // ───────────────────────── ⚙ Categories ─────────────────────────

    internal var catType = "expense"

    internal fun catsDialog() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(12), dp(14), dp(16))
        sv.addView(col)
        val dlg = OvlBuilder(this).setView(sv).create()

        fun build() {
            col.removeAllViews()
            val th = hrow()
            th.addView(tv("⚙ Categories", 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val x = tv("✕", 18f)
            x.setPadding(dp(10), 0, 0, 0)
            x.setOnClickListener { dlg.dismiss() }
            th.addView(x)
            col.addView(th)

            val tg = LinearLayout(this)
            tg.orientation = LinearLayout.HORIZONTAL
            val tIn = tv("Income", 14f, if (catType == "income") GREEN else MUT)
            val tEx = tv("Expense", 14f, if (catType == "expense") RED else MUT)
            tIn.gravity = Gravity.CENTER; tEx.gravity = Gravity.CENTER
            tIn.background = box(BG, if (catType == "income") GREEN else LINE, 8)
            tEx.background = box(BG, if (catType == "expense") RED else LINE, 8)
            tIn.setOnClickListener { catType = "income"; build() }
            tEx.setOnClickListener { catType = "expense"; build() }
            val l1 = LinearLayout.LayoutParams(0, dp(40), 1f); l1.setMargins(0, dp(10), dp(4), dp(6))
            val l2 = LinearLayout.LayoutParams(0, dp(40), 1f); l2.setMargins(dp(4), dp(10), 0, dp(6))
            tg.addView(tIn, l1)
            tg.addView(tEx, l2)
            col.addView(tg)

            val cats = MoneyRepo.catsOf(catType)
            if (cats.isEmpty()) col.addView(empty("Abhi koi category nahi. Neeche se banao."))
            for (c in cats) {
                val cid = c.optString("id")
                col.addView(catRow(c.optString("name"), false,
                    { prompt("Category ka naya naam", c.optString("name"), "", false) { n -> if (n.isNotEmpty()) { MoneyRepo.renameCat(cid, n); persist(); render(); build() } } },
                    { confirm("Category \"" + c.optString("name") + "\" delete karni hai? (purani entries me naam bacha rahega)") { MoneyRepo.deleteCat(cid); persist(); render(); build() } }))
                for (s in MoneyRepo.subsOf(c)) {
                    val sid = s.optString("id")
                    col.addView(catRow(s.optString("name"), true,
                        { prompt("Subcategory ka naya naam", s.optString("name"), "", false) { n -> if (n.isNotEmpty()) { MoneyRepo.renameSub(cid, sid, n); persist(); render(); build() } } },
                        { confirm("Subcategory \"" + s.optString("name") + "\" delete karni hai?") { MoneyRepo.deleteSub(cid, sid); persist(); render(); build() } }))
                }
                val addSub = tv("＋ subcategory", 12f, MUT)
                addSub.setPadding(dp(22), dp(4), 0, dp(8))
                addSub.setOnClickListener {
                    prompt("Nayi subcategory (" + c.optString("name") + ")", "", "Naam", false) { n ->
                        if (n.isNotEmpty()) { MoneyRepo.addSub(c, n); persist(); render(); build() }
                    }
                }
                col.addView(addSub)
            }
            val addCat = tv("＋ Nayi " + (if (catType == "income") "Income" else "Expense") + " category", 14f, Color.WHITE, true)
            addCat.gravity = Gravity.CENTER
            addCat.background = box(LINE, 0, 8)
            addCat.setOnClickListener {
                prompt("Nayi category", "", "Category ka naam", false) { n ->
                    if (n.isNotEmpty()) { MoneyRepo.addCat(catType, n); persist(); render(); build() }
                }
            }
            val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            alp.topMargin = dp(14)
            col.addView(addCat, alp)

            col.addView(section("Backup"))
            val bk = FlowLayout(this)
            bk.hGap = dp(8); bk.vGap = dp(8)
            val b1 = chip("\uD83D\uDCE5 Backup import (.json)", false)
            b1.setOnClickListener { if (canEdit()) impLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            val b2 = chip("\uD83D\uDCE4 Excel (.xlsx)", false)
            b2.setOnClickListener { exportXlsx() }
            val b3 = chip("\uD83D\uDCE4 JSON", false)
            b3.setOnClickListener { exportJson() }
            bk.addView(b1); bk.addView(b2); bk.addView(b3)
            col.addView(bk)
        }
        build()
        dlg.show()
        dlg.window?.let {
            it.setBackgroundDrawable(box(PANEL, 0, 14))
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setGravity(Gravity.BOTTOM)
        }
    }

    internal fun catRow(name: String, sub: Boolean, onEdit: () -> Unit, onDel: () -> Unit): View {
        val r = hrow()
        r.setPadding(if (sub) dp(22) else 0, dp(6), 0, dp(6))
        r.addView(tv((if (sub) "↳ " else "") + name, if (sub) 13f else 14f, if (sub) MUT else TXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val e = tv("✎", 18f)
        e.setPadding(dp(12), dp(4), dp(12), dp(4))
        e.setOnClickListener { onEdit() }
        val d = tv("🗑", 18f)
        d.setPadding(dp(12), dp(4), dp(4), dp(4))
        d.setOnClickListener { onDel() }
        r.addView(e)
        r.addView(d)
        return r
    }
}
