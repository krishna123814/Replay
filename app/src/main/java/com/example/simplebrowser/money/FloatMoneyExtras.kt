package com.example.simplebrowser.money

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.simplebrowser.files.OvlBuilder
import com.example.simplebrowser.files.ovlShow
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ═══════════════════════ chhote shared helpers ═══════════════════════

private val PAL = listOf("#2962FF", "#FF9800", "#E91E63", "#9C27B0", "#00BCD4", "#8BC34A", "#FFC107", "#795548").map { Color.parseColor(it) }
private val AMBER = Color.parseColor("#FFB74D")

private fun wlp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
private fun fw(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

private fun yearOf(ts: Long): Int { val c = Calendar.getInstance(); c.timeInMillis = ts; return c.get(Calendar.YEAR) }
private fun monthOf(ts: Long): Int { val c = Calendar.getInstance(); c.timeInMillis = ts; return c.get(Calendar.MONTH) }

private class FGrp(val name: String) { var sum = 0.0; var n = 0 }

internal fun FloatMoney.typeToggle(cur: String, pick: (String) -> Unit): View {
    val tg = LinearLayout(this)
    tg.orientation = LinearLayout.HORIZONTAL
    tg.setPadding(dp(10), dp(8), dp(10), 0)
    val a = tv("Income", 14f, if (cur == "income") GREEN else MUT)
    val b = tv("Expense", 14f, if (cur == "expense") RED else MUT)
    a.gravity = Gravity.CENTER; b.gravity = Gravity.CENTER
    a.background = box(BG, if (cur == "income") GREEN else LINE, 8)
    b.background = box(BG, if (cur == "expense") RED else LINE, 8)
    a.setOnClickListener { pick("income") }
    b.setOnClickListener { pick("expense") }
    val l1 = LinearLayout.LayoutParams(0, dp(40), 1f); l1.setMargins(0, 0, dp(4), 0)
    val l2 = LinearLayout.LayoutParams(0, dp(40), 1f); l2.setMargins(dp(4), 0, 0, 0)
    tg.addView(a, l1)
    tg.addView(b, l2)
    return tg
}

internal fun FloatMoney.chipRow(items: List<Pair<String, String>>, cur: String, pick: (String) -> Unit): View {
    val f = FlowLayout(this)
    f.hGap = dp(6); f.vGap = dp(6)
    f.setPadding(dp(10), dp(8), dp(10), dp(2))
    for ((k, label) in items) {
        val c = chip(label, cur == k)
        c.setOnClickListener { pick(k) }
        f.addView(c)
    }
    return f
}

internal fun FloatMoney.legend(items: List<Pair<String, Int>>): View {
    val f = FlowLayout(this)
    f.hGap = dp(12); f.vGap = dp(4)
    f.setPadding(dp(12), dp(2), dp(12), dp(6))
    for ((name, color) in items) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val sq = View(this)
        sq.background = box(color, 0, 2)
        r.addView(sq, LinearLayout.LayoutParams(dp(9), dp(9)))
        val t = tv(name, 11f, MUT)
        t.setPadding(dp(4), 0, 0, 0)
        r.addView(t)
        f.addView(r)
    }
    return f
}

internal fun FloatMoney.bar(pct: Double, color: Int): View {
    val w = Math.min(Math.max(pct, 0.0), 100.0).toFloat()
    val r = LinearLayout(this)
    r.orientation = LinearLayout.HORIZONTAL
    r.background = box(LINE, 0, 3)
    val fill = View(this)
    fill.background = box(color, 0, 3)
    r.addView(fill, LinearLayout.LayoutParams(0, dp(5), w))
    r.addView(View(this), LinearLayout.LayoutParams(0, dp(5), 100f - w))
    return r
}

internal fun FloatMoney.chartBox(kind: String, labels: List<String>, series: List<ChartSeries>, ref: Double = 0.0, refLbl: String = "", fmt: ((Double) -> String)? = null): View {
    val c = ChartView(this)
    c.kind = kind
    c.labels = labels
    c.series = series
    c.ref = ref
    c.refLbl = refLbl
    if (fmt != null) c.fmt = fmt
    val h = FrameLayout(this)
    h.setPadding(dp(8), 0, dp(8), 0)
    h.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return h
}

private fun FloatMoney.noteText(s: String): View {
    val t = tv(s, 12f, MUT)
    t.setPadding(dp(14), dp(2), dp(14), dp(6))
    return t
}

private fun FloatMoney.pctChip(p: Long): View {
    val t = tv("$p%", 11f, TXT)
    t.gravity = Gravity.CENTER
    t.setPadding(dp(6), dp(1), dp(6), dp(1))
    t.background = box(LINE, 0, 5)
    t.minWidth = dp(38)
    return t
}

/** [left (weight) ... right] wali ek seedhi row. */
private fun FloatMoney.sumRow(left: String, leftColor: Int, mid: List<Pair<String, Int>>, right: String, rightColor: Int, leftW: Int, rightW: Int, click: (() -> Unit)?): View {
    val r = hrow()
    r.setPadding(dp(12), dp(10), dp(12), dp(10))
    r.addView(tv(left, 14f, leftColor, true), LinearLayout.LayoutParams(dp(leftW), ViewGroup.LayoutParams.WRAP_CONTENT))
    val m = LinearLayout(this)
    m.orientation = LinearLayout.VERTICAL
    m.gravity = Gravity.END
    for ((t, c) in mid) m.addView(tv(t, 12f, c))
    r.addView(m, wlp())
    val b = tv(right, 13f, rightColor, true)
    b.gravity = Gravity.END
    r.addView(b, LinearLayout.LayoutParams(dp(rightW), ViewGroup.LayoutParams.WRAP_CONTENT))
    if (click != null) r.setOnClickListener { click() }
    return r
}

// ═══════════════════════ Budget ═══════════════════════

private class FBud(val id: String, val name: String, val lim: Double, val spent: Double) { val pct: Double get() = spent * 100 / lim }

private fun budInfo(y: Int, mo: Int): List<FBud> {
    val bd = MoneyRepo.blob.optJSONObject("budgets") ?: return emptyList()
    val sp = HashMap<String, Double>()
    for (t in MoneyRepo.txs()) {
        if (t.optString("type") != "expense") continue
        val ts = t.optLong("ts")
        if (yearOf(ts) == y && monthOf(ts) == mo) sp[t.optString("cat")] = (sp[t.optString("cat")] ?: 0.0) + t.optDouble("amount", 0.0)
    }
    val out = ArrayList<FBud>()
    val it = bd.keys()
    while (it.hasNext()) {
        val id = it.next()
        val c = MoneyRepo.catOf(id) ?: continue
        val lim = bd.optDouble(id, 0.0)
        if (!(lim > 0)) continue
        out.add(FBud(id, c.optString("name"), lim, sp[id] ?: 0.0))
    }
    return out.sortedByDescending { it.pct }
}

internal fun FloatMoney.budgetBanner(y: Int, mo: Int): View? {
    val w = budInfo(y, mo).filter { it.pct >= 80 }
    if (w.isEmpty()) return null
    val box0 = LinearLayout(this)
    box0.orientation = LinearLayout.VERTICAL
    box0.setPadding(dp(10), dp(2), dp(10), dp(4))
    for (x in w) {
        val over = x.spent > x.lim
        val col = if (over) RED else AMBER
        val t = tv(
            (if (over) "⚠ " + x.name + " ka limit paar! " else "⚡ " + x.name + " limit ke kareeb: ") +
                MoneyRepo.fmt(x.spent) + " / " + MoneyRepo.fmt(x.lim) +
                (if (over) " (+" + MoneyRepo.fmt(x.spent - x.lim) + ")" else " (" + Math.round(x.pct) + "%)"), 12f, col)
        t.setPadding(dp(9), dp(5), dp(9), dp(5))
        t.background = box(Color.argb(30, Color.red(col), Color.green(col), Color.blue(col)), col, 8)
        val lp = fw(); lp.topMargin = dp(5)
        box0.addView(t, lp)
    }
    return box0
}

// ═══════════════════════ Yearly ═══════════════════════

internal fun FloatMoney.renderYearly() {
    val all = MoneyRepo.sumAll()
    body.addView(section("Ab tak ka total (sab saal)"))
    body.addView(cardsRow(all.i, all.e, false))
    val bal = tv("Balance  " + MoneyRepo.fmt(all.i - all.e), 14f, if (all.i >= all.e) GREEN else RED, true)
    bal.gravity = Gravity.CENTER
    bal.setPadding(0, dp(8), 0, dp(6))
    body.addView(bal)
    val nowY = Calendar.getInstance().get(Calendar.YEAR)
    for (y in txYears()) {
        val s = MoneyRepo.sum(y, null)
        body.addView(sumRow(y.toString(), if (y == nowY) ACCENT else Color.WHITE,
            listOf(MoneyRepo.fmt(s.i) to GREEN, MoneyRepo.fmt(s.e) to RED),
            MoneyRepo.fmt(s.i - s.e), if (s.i >= s.e) GREEN else RED, 56, 100) { mYear = y; tab = "monthly"; view = "tx"; render() })
    }
}

// ═══════════════════════ Category ═══════════════════════

internal fun FloatMoney.renderCategory() {
    val isInc = ct == "income"
    val cl = if (isInc) GREEN else RED
    val mine = MoneyRepo.txs().filter { it.optString("type") == ct }
    body.addView(typeToggle(ct) { ct = it; cc = ""; cm = null; render() })

    if (cc.isEmpty()) {
        body.addView(chipRow(listOf("month" to "Monthly", "year" to "Yearly", "all" to "Sab time", "cmp" to "Compare"), cp) { cp = it; cc = ""; cm = null; render() })
        if (cp == "cmp") { renderCompare(mine, cl); return }
        val list: List<JSONObject>
        if (cp == "month") {
            body.addView(navRow(mnLong(year, month), { stepMonth(-1) }, { stepMonth(1) }))
            list = mine.filter { yearOf(it.optLong("ts")) == year && monthOf(it.optLong("ts")) == month }
        } else if (cp == "year") {
            body.addView(navRow(mYear.toString(), { mYear--; render() }, { mYear++; render() }))
            list = mine.filter { yearOf(it.optLong("ts")) == mYear }
        } else {
            body.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
            list = mine
        }
        val g = LinkedHashMap<String, FGrp>()
        var tot = 0.0
        for (t in list) {
            val k = catKey(t)
            val o = g.getOrPut(k) { FGrp(MoneyRepo.catName(t)) }
            o.sum += t.optDouble("amount", 0.0); o.n++; tot += t.optDouble("amount", 0.0)
        }
        val bmap = if (cp == "month" && ct == "expense") MoneyRepo.blob.optJSONObject("budgets") else null
        if (bmap != null) {
            val it = bmap.keys()
            while (it.hasNext()) {
                val id = it.next()
                val c = MoneyRepo.catOf(id)
                if (c != null && bmap.optDouble(id, 0.0) > 0 && !g.containsKey(id)) g[id] = FGrp(c.optString("name"))
            }
        }
        body.addView(kv("Total (" + list.size + " entries)", MoneyRepo.fmt(tot), cl))
        body.addView(section("Category wise — dabao to detail"))
        if (g.isEmpty()) body.addView(empty("Is period me koi entry nahi."))
        for (k in g.keys.sortedByDescending { g[it]!!.sum }) {
            val o = g[k]!!
            val pc = if (tot > 0) Math.round(o.sum * 100 / tot) else 0L
            val r = hrow()
            r.setPadding(dp(12), dp(8), dp(12), dp(8))
            r.addView(pctChip(pc))
            val nm = tv(o.name + "  · " + o.n, 14f)
            nm.setPadding(dp(10), 0, 0, 0)
            r.addView(nm, wlp())
            r.addView(tv(MoneyRepo.fmt(o.sum), 14f, cl, true))
            r.setOnClickListener { cc = k; cm = null; mYear = if (cp == "year") mYear else year; render() }
            body.addView(r)
            val lim = if (bmap != null) bmap.optDouble(k, 0.0) else 0.0
            if (lim > 0) {
                val bp = o.sum * 100 / lim
                val bc = if (bp > 100) RED else if (bp >= 80) AMBER else GREEN
                val w = LinearLayout(this)
                w.orientation = LinearLayout.VERTICAL
                w.setPadding(dp(12), 0, dp(12), dp(8))
                w.addView(bar(bp, bc), fw())
                w.addView(tv((if (bp > 100) "⚠ Limit paar! " else "") + MoneyRepo.fmt(o.sum) + " / " + MoneyRepo.fmt(lim) + " (" + Math.round(bp) + "%)", 11f, if (bp > 100) RED else MUT))
                body.addView(w)
            }
        }
        return
    }

    // ── ek category ki detail ──
    val cx = mine.filter { catKey(it) == cc }
    val nm = if (cx.isNotEmpty()) MoneyRepo.catName(cx[0]) else "?"
    val y = mYear
    var ysum = 0.0
    var yn = 0
    var asum = 0.0
    for (t in cx) {
        asum += t.optDouble("amount", 0.0)
        if (yearOf(t.optLong("ts")) == y) { ysum += t.optDouble("amount", 0.0); yn++ }
    }
    val hd = hrow()
    hd.setBackgroundColor(Color.parseColor("#1A1E29"))
    hd.setPadding(dp(12), dp(8), dp(12), dp(8))
    hd.addView(tv("‹", 22f, Color.WHITE, true))
    val hn = tv(nm, 14f, Color.WHITE)
    hn.setPadding(dp(10), 0, 0, 0)
    hd.addView(hn, wlp())
    hd.addView(tv(MoneyRepo.fmt(asum), 14f, cl, true))
    hd.setOnClickListener { cc = ""; cm = null; render() }
    val hlp = fw(); hlp.topMargin = dp(8)
    body.addView(hd, hlp)
    body.addView(navRow(y.toString(), { mYear--; render() }, { mYear++; render() }))
    body.addView(kv("$y ka total ($yn entries)", MoneyRepo.fmt(ysum), cl))
    val dlim = if (ct == "expense") budgetOf(cc) else 0.0
    if (dlim > 0) body.addView(kv("Mahine ka limit", MoneyRepo.fmt(dlim)))
    body.addView(section("Mahine wise — $y"))
    for (mo in 11 downTo 0) {
        val ms = cx.filter { yearOf(it.optLong("ts")) == y && monthOf(it.optLong("ts")) == mo }
        var sm = 0.0
        for (t in ms) sm += t.optDouble("amount", 0.0)
        val mk = "$y-$mo"
        val r = hrow()
        r.setPadding(dp(12), dp(10), dp(12), dp(10))
        r.addView(tv(mnShort(y, mo), 14f, Color.WHITE, true), LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        val cnt = tv(if (ms.isNotEmpty()) ms.size.toString() + " entries" else "", 12f, MUT)
        cnt.gravity = Gravity.END
        r.addView(cnt, wlp())
        val amt = tv((if (dlim > 0 && sm > dlim) "⚠ " else "") + MoneyRepo.fmt(sm), 13f, if (sm > 0) cl else MUT, sm > 0)
        amt.gravity = Gravity.END
        r.addView(amt, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (ms.isNotEmpty()) r.setOnClickListener { cm = if (cm == mk) null else mk; render() }
        body.addView(r)
        if (cm == mk) {
            val wk = SimpleDateFormat("EEE", Locale("en", "IN"))
            for (t in ms.sortedByDescending { it.optLong("ts") }) {
                val cal = Calendar.getInstance(); cal.timeInMillis = t.optLong("ts")
                val tr = hrow()
                tr.setBackgroundColor(Color.parseColor("#1A1E29"))
                tr.setPadding(dp(16), dp(8), dp(12), dp(8))
                val left = LinearLayout(this)
                left.orientation = LinearLayout.VERTICAL
                left.addView(tv(cal.get(Calendar.DAY_OF_MONTH).toString() + " " + mnShort(y, mo) + " · " + wk.format(Date(cal.timeInMillis)), 13f))
                val nt = t.optString("note")
                if (nt.isNotEmpty()) left.addView(tv(nt, 11f, MUT))
                tr.addView(left, wlp())
                tr.addView(tv(MoneyRepo.fmt(t.optDouble("amount", 0.0)), 14f, cl, true))
                val id = t.optString("id")
                tr.setOnClickListener { openAdd(id) }
                body.addView(tr)
            }
        }
    }
    body.addView(section("Saal wise"))
    val nowY = y
    for (yy in txYears()) {
        val ys = cx.filter { yearOf(it.optLong("ts")) == yy }
        var sm = 0.0
        for (t in ys) sm += t.optDouble("amount", 0.0)
        val r = hrow()
        r.setPadding(dp(12), dp(10), dp(12), dp(10))
        r.addView(tv(yy.toString(), 14f, if (yy == nowY) ACCENT else Color.WHITE, true), LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        val cnt = tv(ys.size.toString() + " entries", 12f, MUT)
        cnt.gravity = Gravity.END
        r.addView(cnt, wlp())
        val amt = tv(MoneyRepo.fmt(sm), 13f, if (sm > 0) cl else MUT, sm > 0)
        amt.gravity = Gravity.END
        r.addView(amt, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.setOnClickListener { mYear = yy; render() }
        body.addView(r)
    }
}

private fun FloatMoney.renderCompare(mine: List<JSONObject>, cl: Int) {
    val pc = Calendar.getInstance(); pc.clear(); pc.set(year, month - 1, 1)
    val py = pc.get(Calendar.YEAR)
    val pmo = pc.get(Calendar.MONTH)
    body.addView(navRow(mnShort(year, month) + " " + year + " vs " + mnShort(py, pmo), { stepMonth(-1) }, { stepMonth(1) }))
    val cur = LinkedHashMap<String, Double>()
    val prv = LinkedHashMap<String, Double>()
    val nmx = LinkedHashMap<String, String>()
    for (t in mine) {
        val ts = t.optLong("ts")
        val yy = yearOf(ts)
        val mm = monthOf(ts)
        val k = catKey(t)
        val a = t.optDouble("amount", 0.0)
        if (yy == year && mm == month) cur[k] = (cur[k] ?: 0.0) + a
        else if (yy == py && mm == pmo) prv[k] = (prv[k] ?: 0.0) + a
        else continue
        nmx[k] = MoneyRepo.catName(t)
    }
    var tc = 0.0
    var tp = 0.0
    for (k in nmx.keys) { tc += cur[k] ?: 0.0; tp += prv[k] ?: 0.0 }
    val isInc = ct == "income"
    fun goodCol(d: Double): Int = if ((d >= 0) == isInc) GREEN else RED
    val td = tc - tp
    body.addView(kv(mnShort(year, month) + " total", MoneyRepo.fmt(tc), cl))
    body.addView(kv(mnShort(py, pmo) + " total", MoneyRepo.fmt(tp)))
    body.addView(kv("Farak",
        (if (td > 0) "▲ " else if (td < 0) "▼ " else "") + MoneyRepo.fmt(Math.abs(td)) + (if (tp > 0) " (" + String.format(Locale.US, "%.1f", Math.abs(td * 100 / tp)) + "%)" else ""),
        if (td == 0.0) TXT else goodCol(td)))
    body.addView(section("Category wise — sabse zyada badlav pehle"))
    if (nmx.isEmpty()) body.addView(empty("Dono mahine me koi entry nahi."))
    for (k in nmx.keys.sortedByDescending { Math.abs((cur[it] ?: 0.0) - (prv[it] ?: 0.0)) }) {
        val c = cur[k] ?: 0.0
        val p = prv[k] ?: 0.0
        val d = c - p
        val txt = if (d == 0.0) "— same" else (if (d > 0) "▲ " else "▼ ") + MoneyRepo.fmt(Math.abs(d)) + (if (p > 0) " (" + Math.round(Math.abs(d * 100 / p)) + "%)" else " (naya)")
        val r = hrow()
        r.setPadding(dp(12), dp(9), dp(12), dp(9))
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(tv(nmx[k] ?: "?", 14f))
        left.addView(tv("pichhla " + MoneyRepo.fmt(p), 11f, MUT))
        r.addView(left, wlp())
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        right.addView(tv(MoneyRepo.fmt(c), 14f, cl, true))
        right.addView(tv(txt, 11f, if (d == 0.0) MUT else goodCol(d)))
        r.addView(right)
        r.setOnClickListener { cc = k; cm = null; mYear = year; render() }
        body.addView(r)
    }
}

// ═══════════════════════ Analysis ═══════════════════════

private class FPer(val lb: String, val y: Int, val mo: Int?) {
    var i = 0.0
    var e = 0.0
    val ci = HashMap<String, Double>()
    val ce = HashMap<String, Double>()
}

private fun FloatMoney.trendRow(lbl: String, cur: Double, prev: Double, upGood: Boolean): View {
    if (!(prev > 0)) return kv(lbl, "—")
    val d = (cur - prev) * 100 / prev
    val up = d >= 0
    val good = up == upGood
    return kv(lbl, (if (up) "▲ badh rahi " else "▼ ghat rahi ") + String.format(Locale.US, "%.1f", Math.abs(d)) + "%", if (good) GREEN else RED)
}

internal fun FloatMoney.renderAnalysis() {
    val y = mYear
    val per = ArrayList<FPer>()
    if (ap == "month") for (mo in 0..11) per.add(FPer(mnShort(y, mo), y, mo))
    else for (yy in txYears().reversed()) per.add(FPer(yy.toString(), yy, null))
    val nm = HashMap<String, String>()
    for (t in MoneyRepo.txs()) {
        val ts = t.optLong("ts")
        val yy = yearOf(ts)
        val mm = monthOf(ts)
        val p = per.firstOrNull { it.y == yy && (it.mo == null || it.mo == mm) } ?: continue
        val k = catKey(t)
        val a = t.optDouble("amount", 0.0)
        nm[t.optString("type") + k] = MoneyRepo.catName(t)
        if (t.optString("type") == "income") { p.i += a; p.ci[k] = (p.ci[k] ?: 0.0) + a }
        else { p.e += a; p.ce[k] = (p.ce[k] ?: 0.0) + a }
    }
    body.addView(chipRow(listOf("month" to "Monthly", "year" to "Yearly"), ap) { ap = it; render() })
    if (ap == "month") body.addView(navRow(y.toString(), { mYear--; render() }, { mYear++; render() }))
    val labels = per.map { it.lb }
    val act = per.filter { it.i > 0 || it.e > 0 }
    val fi = per.indexOfFirst { it.i > 0 || it.e > 0 }
    val la = per.indexOfLast { it.i > 0 || it.e > 0 }
    fun ln(fn: (FPer) -> Double): List<Double?> = per.mapIndexed { ix, x -> if (ix < fi || ix > la) null else fn(x) }

    // trend
    val now = Calendar.getInstance()
    val tr = act.filter { !(it.y == now.get(Calendar.YEAR) && (it.mo == null || it.mo == now.get(Calendar.MONTH))) }
    if (tr.size >= 2) {
        val c = tr[tr.size - 1]
        val pv = tr[tr.size - 2]
        body.addView(section("Trend — " + c.lb + " vs " + pv.lb))
        body.addView(trendRow("Income", c.i, pv.i, true))
        body.addView(trendRow("Expense", c.e, pv.e, false))
        if (tr.size < act.size) body.addView(noteText("(Abhi chal raha " + (if (ap == "month") "mahina" else "saal") + " adhura hai, isliye trend me nahi ginta.)"))
    } else {
        body.addView(section("Trend"))
        body.addView(noteText("Trend ke liye kam se kam 2 period ka data chahiye."))
    }

    // 1) bar graph
    body.addView(section("📊 Bar graph — Income vs Expense"))
    body.addView(chartBox("bar", labels, listOf(ChartSeries("Income", GREEN, per.map { it.i }), ChartSeries("Expense", RED, per.map { it.e }))))
    body.addView(legend(listOf("Income" to GREEN, "Expense" to RED)))

    // 2) line graph
    body.addView(section("📈 Line graph"))
    body.addView(chipRow(listOf("common" to "Common (sabka total)", "sep" to "Alag alag (category)"), al) { al = it; render() })
    if (al == "common") {
        body.addView(chartBox("line", labels, listOf(ChartSeries("Income", GREEN, ln { it.i }), ChartSeries("Expense", RED, ln { it.e }))))
        body.addView(legend(listOf("Income (total)" to GREEN, "Expense (total)" to RED)))
    } else {
        body.addView(typeToggle(at) { at = it; render() })
        val tot = HashMap<String, Double>()
        for (x in per) {
            val m = if (at == "income") x.ci else x.ce
            for ((k, v) in m) tot[k] = (tot[k] ?: 0.0) + v
        }
        val keys = tot.keys.sortedByDescending { tot[it] ?: 0.0 }
        val adef = keys.take(5)
        val sel: List<String> = asel[at] ?: adef
        if (keys.isEmpty()) body.addView(empty("Is period me koi entry nahi."))
        else {
            val f = FlowLayout(this)
            f.hGap = dp(6); f.vGap = dp(6)
            f.setPadding(dp(10), dp(8), dp(10), dp(6))
            for (k in keys) {
                val ix = sel.indexOf(k)
                val on = ix >= 0
                val col = PAL[(if (on) ix else 0) % PAL.size]
                val c = chip(nm[at + k] ?: "?", false)
                if (on) { c.setTextColor(col); c.background = box(BG, col, 16) }
                c.setOnClickListener {
                    val l = asel.getOrPut(at) { adef.toMutableList() }
                    if (l.contains(k)) l.remove(k) else l.add(k)
                    render()
                }
                f.addView(c)
            }
            body.addView(f)
            val ss = ArrayList<ChartSeries>()
            for ((ix, k) in sel.filter { tot.containsKey(it) }.withIndex()) {
                ss.add(ChartSeries(nm[at + k] ?: "?", PAL[ix % PAL.size], ln { (if (at == "income") it.ci else it.ce)[k] ?: 0.0 }))
            }
            if (ss.isEmpty()) body.addView(noteText("Upar se category chuno."))
            else {
                body.addView(chartBox("line", labels, ss))
                body.addView(legend(ss.map { it.name to it.color }))
            }
        }
    }

    // 3) ratio
    var ti = 0.0
    var te = 0.0
    for (x in per) { ti += x.i; te += x.e }
    fun ratio(x: FPer): Double? = if (x.i > 0) x.e * 100 / x.i else null
    body.addView(section("⚖ Income : Expense ratio (Expense ÷ Income)"))
    body.addView(kv((if (ap == "month") "$y ka" else "Sab saal ka") + " ratio",
        if (ti > 0) String.format(Locale.US, "%.1f", te * 100 / ti) + "%" else "—", if (ti >= te) GREEN else RED))
    body.addView(noteText("100% se neeche = balance bacha, upar = income se zyada kharcha."))
    body.addView(chartBox("bar", labels,
        listOf(ChartSeries("Ratio", GREEN, per.map { val r = ratio(it); if (r == null) null else Math.round(r * 10) / 10.0 }, { v -> if (v > 100) RED else GREEN })),
        100.0, "100%", { v -> Math.round(v).toString() + "%" }))
    body.addView(legend(listOf("≤ 100% (balance)" to GREEN, "> 100% (zyada kharcha)" to RED)))
    for (x in act.reversed()) {
        val r = ratio(x)
        body.addView(sumRow(x.lb, Color.WHITE, listOf(MoneyRepo.fmt(x.i) to GREEN, MoneyRepo.fmt(x.e) to RED),
            if (r == null) "—" else String.format(Locale.US, "%.1f", r) + "%", if (r != null && r <= 100) GREEN else RED, 56, 80, null))
    }
}

// ═══════════════════════ Plan (andaaza) ═══════════════════════

private fun estList(): List<JSONObject> {
    val a = MoneyRepo.blob.optJSONArray("est") ?: return emptyList()
    val l = ArrayList<JSONObject>()
    for (i in 0 until a.length()) { val o = a.optJSONObject(i); if (o != null) l.add(o) }
    return l
}

private fun estFind(id: String): JSONObject? = estList().firstOrNull { it.optString("id") == id }

private fun planNum(r: String): Double? {
    val s = r.replace("₹", "").replace(",", "").replace(" ", "").trim()
    if (s.isEmpty()) return null
    val n = s.toDoubleOrNull() ?: return null
    return if (n < 0 || n.isNaN()) null else Math.round(n * 100) / 100.0
}

internal fun FloatMoney.renderPlan() {
    val est = estList()
    var tot = 0.0
    for (x in est) tot += x.optDouble("amt", 0.0)
    val n = tv("Ye sirf tumhara andaaza hai — save hota hai, par Daily / Monthly / Total / Analysis / Balance me kahin count nahi hota.", 12f, MUT)
    n.setPadding(dp(14), dp(10), dp(14), dp(4))
    body.addView(n)
    val cr = LinearLayout(this)
    cr.orientation = LinearLayout.HORIZONTAL
    cr.setPadding(dp(10), dp(4), dp(10), dp(4))
    val c1 = LinearLayout(this); c1.orientation = LinearLayout.VERTICAL; c1.setPadding(dp(10), dp(8), dp(10), dp(8)); c1.background = box(PANEL, LINE, 10)
    c1.addView(tv("Mahine ka kharcha", 11f, MUT)); c1.addView(tv(MoneyRepo.fmt(tot), 15f, RED, true))
    val c2 = LinearLayout(this); c2.orientation = LinearLayout.VERTICAL; c2.setPadding(dp(10), dp(8), dp(10), dp(8)); c2.background = box(PANEL, LINE, 10)
    c2.addView(tv("Saal ka (×12)", 11f, MUT)); c2.addView(tv(MoneyRepo.fmt(tot * 12), 15f, TXT, true))
    val l1 = wlp(); l1.setMargins(0, 0, dp(4), 0)
    val l2 = wlp(); l2.setMargins(dp(4), 0, 0, 0)
    cr.addView(c1, l1); cr.addView(c2, l2)
    body.addView(cr)
    body.addView(kv("Roz ka (÷30)", MoneyRepo.fmt(tot / 30)))

    body.addView(section("Category add karo (per mahina)"))
    val ar = hrow()
    ar.setPadding(dp(10), dp(4), dp(10), dp(4))
    val en = EditText(this)
    en.hint = "Category (jaise Kiraya)"
    en.setTextColor(Color.WHITE); en.setHintTextColor(MUT); en.setSingleLine(true); en.textSize = 13f
    val ea = EditText(this)
    ea.hint = "₹ amount"
    ea.setTextColor(Color.WHITE); ea.setHintTextColor(MUT); ea.setSingleLine(true); ea.textSize = 13f
    ea.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    val add = tv("Add", 13f, Color.WHITE, true)
    add.gravity = Gravity.CENTER
    add.background = box(ACCENT, 0, 8)
    add.setPadding(dp(16), dp(10), dp(16), dp(10))
    add.setOnClickListener {
        if (!canEdit()) return@setOnClickListener
        val name = en.text.toString().trim()
        val amt = planNum(ea.text.toString())
        if (name.isEmpty()) { Toast.makeText(this, "Category ka naam daalo", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
        if (amt == null) { Toast.makeText(this, "Sahi amount daalo", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
        var arr = MoneyRepo.blob.optJSONArray("est")
        if (arr == null) { arr = JSONArray(); MoneyRepo.blob.put("est", arr) }
        val o = JSONObject()
        o.put("id", MoneyRepo.newId("e")); o.put("name", name.take(40)); o.put("amt", amt)
        arr.put(o)
        persist(); render()
    }
    ar.addView(en, wlp())
    ar.addView(ea, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
    val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    alp.leftMargin = dp(6)
    ar.addView(add, alp)
    body.addView(ar)

    body.addView(section("Mere fix kharche (naam / amount dabao = badlo)"))
    if (est.isEmpty()) body.addView(empty("Abhi koi category nahi.\nUpar naam + amount daal ke Add dabao."))
    for (x in est) {
        val id = x.optString("id")
        val amt = x.optDouble("amt", 0.0)
        val pc = if (tot > 0) Math.round(amt * 100 / tot) else 0L
        val r = hrow()
        r.setPadding(dp(12), dp(9), dp(12), dp(9))
        r.addView(pctChip(pc))
        val nm = tv(x.optString("name"), 14f)
        nm.setPadding(dp(10), dp(4), dp(4), dp(4))
        nm.setOnClickListener {
            if (!canEdit()) return@setOnClickListener
            prompt("Category ka naam", x.optString("name"), "", false) { v ->
                if (v.isNotEmpty()) { estFind(id)?.put("name", v.take(40)); persist(); render() }
            }
        }
        r.addView(nm, wlp())
        val am = tv(MoneyRepo.fmt(amt), 14f, RED, true)
        am.setPadding(dp(8), dp(4), dp(8), dp(4))
        am.setOnClickListener {
            if (!canEdit()) return@setOnClickListener
            prompt("\"" + x.optString("name") + "\" ka mahine ka amount", Math.round(amt * 100).let { if (it % 100 == 0L) (it / 100).toString() else (it / 100.0).toString() }, "₹", true) { v ->
                val nn = planNum(v)
                if (nn == null) Toast.makeText(this, "Sahi amount daalo", Toast.LENGTH_SHORT).show()
                else { estFind(id)?.put("amt", nn); persist(); render() }
            }
        }
        r.addView(am)
        val del = tv("✕", 15f, RED)
        del.setPadding(dp(10), dp(4), dp(4), dp(4))
        del.setOnClickListener {
            if (!canEdit()) return@setOnClickListener
            confirm("\"" + x.optString("name") + "\" hata dein?") {
                val na = JSONArray()
                for (y in estList()) if (y.optString("id") != id) na.put(y)
                MoneyRepo.blob.put("est", na)
                persist(); render()
            }
        }
        r.addView(del)
        body.addView(r)
    }
}

// ═══════════════════════ Search / Filter ═══════════════════════

internal fun FloatMoney.searchDialog() {
    val f = HashMap<String, String>()
    val sv = ScrollView(this)
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(12), dp(10), dp(12), dp(16))
    sv.addView(col)
    val dlg = OvlBuilder(this).setView(sv).create()
    val dfmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val shown = SimpleDateFormat("dd MMM yyyy", Locale("en", "IN"))

    // header
    val th = hrow()
    th.addView(tv("🔍 Search / Filter", 15f, Color.WHITE, true), wlp())
    val reset = tv("Reset", 12f, MUT)
    reset.setPadding(dp(10), dp(4), dp(10), dp(4))
    th.addView(reset)
    val x = tv("✕", 18f)
    x.setPadding(dp(10), 0, 0, 0)
    x.setOnClickListener { dlg.dismiss() }
    th.addView(x)
    col.addView(th)

    val q = EditText(this)
    q.hint = "Note ya category (jaise bidi, dwai)"
    q.setTextColor(Color.WHITE); q.setHintTextColor(MUT); q.setSingleLine(true)
    col.addView(q)

    fun pickBtn(): TextView {
        val b = tv("", 13f, Color.WHITE)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(8), dp(10), dp(8), dp(10))
        b.background = box(BG, LINE, 8)
        return b
    }
    val bTy = pickBtn(); val bAc = pickBtn(); val bCt = pickBtn(); val bD1 = pickBtn(); val bD2 = pickBtn()
    val results = LinearLayout(this)
    results.orientation = LinearLayout.VERTICAL

    val mn = EditText(this)
    mn.hint = "Amount se (min)"
    val mx = EditText(this)
    mx.hint = "Amount tak (max)"
    for (e in listOf(mn, mx)) {
        e.setTextColor(Color.WHITE); e.setHintTextColor(MUT); e.setSingleLine(true); e.textSize = 13f
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    fun runSearch() {
        f["q"] = q.text.toString()
        f["mn"] = mn.text.toString()
        f["mx"] = mx.text.toString()
        val ty = f["ty"] ?: ""
        val ac = f["ac"] ?: ""
        val ctf = f["ct"] ?: ""
        bTy.text = if (ty == "income") "Sirf Income" else if (ty == "expense") "Sirf Expense" else "Income + Expense"
        bAc.text = if (ac.isEmpty()) "Sab account" else ac
        bCt.text = if (ctf.isEmpty()) "Sab category" else ctf
        bD1.text = "Date se: " + (f["d1"]?.let { if (it.isEmpty()) "—" else shown.format(dfmt.parse(it)!!) } ?: "—")
        bD2.text = "Date tak: " + (f["d2"]?.let { if (it.isEmpty()) "—" else shown.format(dfmt.parse(it)!!) } ?: "—")
        val terms = (f["q"] ?: "").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val vmn = f["mn"]?.toDoubleOrNull()
        val vmx = f["mx"]?.toDoubleOrNull()
        val t1 = f["d1"]?.takeIf { it.isNotEmpty() }?.let { dfmt.parse(it)?.time }
        val t2 = f["d2"]?.takeIf { it.isNotEmpty() }?.let { (dfmt.parse(it)?.time ?: 0L) + 86400000L - 1 }
        val res = MoneyRepo.txs().filter { t ->
            val a = t.optDouble("amount", 0.0)
            val cn = MoneyRepo.catName(t)
            val ts = t.optLong("ts")
            if (ty.isNotEmpty() && t.optString("type") != ty) return@filter false
            val acct = t.optString("acct").ifEmpty { "Cash" }
            if (ac.isNotEmpty() && acct != ac) return@filter false
            if (ctf.isNotEmpty() && !cn.equals(ctf, ignoreCase = true)) return@filter false
            if (vmn != null && a < vmn) return@filter false
            if (vmx != null && a > vmx) return@filter false
            if (t1 != null && ts < t1) return@filter false
            if (t2 != null && ts > t2) return@filter false
            if (terms.isNotEmpty()) {
                val hay = (cn + " " + MoneyRepo.subName(t) + " " + t.optString("note") + " " + acct).lowercase()
                for (w in terms) if (!hay.contains(w)) return@filter false
            }
            true
        }.sortedByDescending { it.optLong("ts") }
        var ti = 0.0
        var te = 0.0
        for (t in res) if (t.optString("type") == "income") ti += t.optDouble("amount", 0.0) else te += t.optDouble("amount", 0.0)
        results.removeAllViews()
        val sum = hrow()
        sum.setPadding(dp(2), dp(10), dp(2), dp(6))
        sum.addView(tv(res.size.toString() + " entries", 13f, MUT), wlp())
        val si = tv(MoneyRepo.fmt(ti), 13f, GREEN, true)
        si.setPadding(0, 0, dp(10), 0)
        sum.addView(si)
        sum.addView(tv(MoneyRepo.fmt(te), 13f, RED, true))
        results.addView(sum)
        if (res.isEmpty()) results.addView(empty("Koi entry nahi mili."))
        val cal = Calendar.getInstance()
        for (t in res.take(150)) {
            cal.timeInMillis = t.optLong("ts")
            val r = hrow()
            r.setPadding(dp(2), dp(8), dp(2), dp(8))
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            val sn = MoneyRepo.subName(t)
            left.addView(tv(MoneyRepo.catName(t) + (if (sn.isNotEmpty()) " › $sn" else ""), 13f))
            val nt = t.optString("note")
            left.addView(tv(shown.format(Date(cal.timeInMillis)) + " · " + t.optString("acct").ifEmpty { "Cash" } + (if (nt.isNotEmpty()) " · $nt" else ""), 11f, MUT))
            r.addView(left, wlp())
            r.addView(tv(MoneyRepo.fmt(t.optDouble("amount", 0.0)), 14f, if (t.optString("type") == "income") GREEN else RED, true))
            val id = t.optString("id")
            r.setOnClickListener { openAdd(id) }
            results.addView(r)
        }
        if (res.size > 150) results.addView(noteText("Pehli 150 dikhayi — filter badhao to baaki dikhengi."))
    }

    fun choose(title: String, opts: List<String>, onPick: (Int) -> Unit) {
        OvlBuilder(this).setTitle(title).setItems(opts.toTypedArray()) { _, i -> onPick(i) }.show()
    }
    bTy.setOnClickListener {
        choose("Type", listOf("Income + Expense", "Sirf Income", "Sirf Expense")) { i -> f["ty"] = if (i == 1) "income" else if (i == 2) "expense" else ""; runSearch() }
    }
    bAc.setOnClickListener {
        val l = listOf("Sab account") + MoneyRepo.accts()
        choose("Account", l) { i -> f["ac"] = if (i == 0) "" else l[i]; runSearch() }
    }
    bCt.setOnClickListener {
        val names = LinkedHashMap<String, String>()
        for (c in MoneyRepo.cats()) names[c.optString("name").lowercase()] = c.optString("name")
        for (t in MoneyRepo.txs()) { val n = t.optString("catName"); if (n.isNotEmpty() && !names.containsKey(n.lowercase())) names[n.lowercase()] = n }
        val l = listOf("Sab category") + names.values.sorted()
        choose("Category", l) { i -> f["ct"] = if (i == 0) "" else l[i]; runSearch() }
    }
    fun pickDate(key: String) {
        val c = Calendar.getInstance()
        f[key]?.takeIf { it.isNotEmpty() }?.let { dfmt.parse(it)?.let { d -> c.time = d } }
        ovlShow(this, DatePickerDialog(this, { _, yy, mm, dd -> f[key] = String.format(Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd); runSearch() },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)))
    }
    bD1.setOnClickListener { pickDate("d1") }
    bD2.setOnClickListener { pickDate("d2") }
    bD1.setOnLongClickListener { f["d1"] = ""; runSearch(); true }
    bD2.setOnLongClickListener { f["d2"] = ""; runSearch(); true }

    val r1 = LinearLayout(this); r1.orientation = LinearLayout.HORIZONTAL
    val a1 = wlp(); a1.setMargins(0, dp(8), dp(4), 0)
    val a2 = wlp(); a2.setMargins(dp(4), dp(8), 0, 0)
    r1.addView(bTy, a1); r1.addView(bAc, a2)
    col.addView(r1)
    val r2 = LinearLayout(this); r2.orientation = LinearLayout.VERTICAL
    val lc = fw(); lc.topMargin = dp(8)
    col.addView(bCt, lc)
    val r3 = LinearLayout(this); r3.orientation = LinearLayout.HORIZONTAL
    val m1 = wlp(); m1.setMargins(0, dp(4), dp(4), 0)
    val m2 = wlp(); m2.setMargins(dp(4), dp(4), 0, 0)
    r3.addView(mn, m1); r3.addView(mx, m2)
    col.addView(r3)
    val r4 = LinearLayout(this); r4.orientation = LinearLayout.HORIZONTAL
    val d1 = wlp(); d1.setMargins(0, dp(4), dp(4), 0)
    val d2 = wlp(); d2.setMargins(dp(4), dp(4), 0, 0)
    r4.addView(bD1, d1); r4.addView(bD2, d2)
    col.addView(r4)
    val hint = tv("(Date button lamba dabao = date hatao)", 10f, MUT)
    hint.setPadding(0, dp(2), 0, 0)
    col.addView(hint)
    col.addView(results)

    q.doAfterTextChanged { runSearch() }
    mn.doAfterTextChanged { runSearch() }
    mx.doAfterTextChanged { runSearch() }
    reset.setOnClickListener { f.clear(); q.setText(""); mn.setText(""); mx.setText(""); runSearch() }

    onRenderHook = { runSearch() }          // entry badli/delete hui to results turant fresh
    dlg.setOnDismissListener { onRenderHook = null }
    runSearch()
    dlg.show()
    dlg.window?.let {
        it.setBackgroundDrawable(box(PANEL, 0, 0))
        it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}

// ═══════════════════════ Export / Import ═══════════════════════

private fun xe(v: String): String {
    val sb = StringBuilder()
    for (ch in v) {
        if (ch.code < 32 && ch != '\n' && ch != '\t' && ch != '\r') continue
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

private fun plain(d: Double): String = BigDecimal.valueOf(d).toPlainString()

private fun stamp(): String = SimpleDateFormat("dd-MM-yy", Locale.US).format(Date())

private fun buildXlsx(out: File) {
    val cols = "ABCDEFGHIJK"
    val hd = listOf("Date", "Account", "Category", "Subcategory", "Note", "INR", "Income/Expense", "Description", "Amount", "Currency", "Account")
    fun s(r: Int, ci: Int, v: String, st: Int): String =
        "<c r=\"" + cols[ci] + r + "\"" + (if (st != 0) " s=\"$st\"" else "") + " t=\"inlineStr\"><is><t xml:space=\"preserve\">" + xe(v) + "</t></is></c>"
    fun n(r: Int, ci: Int, v: String, st: Int): String =
        "<c r=\"" + cols[ci] + r + "\"" + (if (st != 0) " s=\"$st\"" else "") + "><v>" + v + "</v></c>"

    val rows = StringBuilder()
    rows.append("<row r=\"1\">")
    for ((i, h) in hd.withIndex()) rows.append(s(1, i, h, 2))
    rows.append("</row>")
    val cal = Calendar.getInstance()
    for ((ix, t) in MoneyRepo.txs().sortedByDescending { it.optLong("ts") }.withIndex()) {
        val r = ix + 2
        cal.timeInMillis = t.optLong("ts")
        val u = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        u.clear()
        u.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
        val ser = u.timeInMillis / 86400000.0 + 25569
        val amt = t.optDouble("amount", 0.0)
        val ac = t.optString("acct").ifEmpty { "Cash" }
        rows.append("<row r=\"$r\">")
            .append(n(r, 0, plain(ser), 1)).append(s(r, 1, ac, 0)).append(s(r, 2, MoneyRepo.catName(t), 0))
            .append(s(r, 3, MoneyRepo.subName(t), 0)).append(s(r, 4, t.optString("note"), 0)).append(n(r, 5, plain(amt), 0))
            .append(s(r, 6, if (t.optString("type") == "income") "Income" else "Expense", 0)).append(s(r, 7, "", 0))
            .append(s(r, 8, plain(amt), 0)).append(s(r, 9, "INR", 0)).append(n(r, 10, plain(amt), 0)).append("</row>")
    }
    val x = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    val ns = "http://schemas.openxmlformats.org/"
    val files = LinkedHashMap<String, String>()
    files["[Content_Types].xml"] = x + "<Types xmlns=\"" + ns + "package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>"
    files["_rels/.rels"] = x + "<Relationships xmlns=\"" + ns + "package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"" + ns + "officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
    files["xl/workbook.xml"] = x + "<workbook xmlns=\"" + ns + "spreadsheetml/2006/main\" xmlns:r=\"" + ns + "officeDocument/2006/relationships\"><sheets><sheet name=\"Money Manager\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
    files["xl/_rels/workbook.xml.rels"] = x + "<Relationships xmlns=\"" + ns + "package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"" + ns + "officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"" + ns + "officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
    files["xl/styles.xml"] = x + "<styleSheet xmlns=\"" + ns + "spreadsheetml/2006/main\"><numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\\ hh:mm:ss\"/></numFmts><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>"
    files["xl/worksheets/sheet1.xml"] = x + "<worksheet xmlns=\"" + ns + "spreadsheetml/2006/main\"><cols><col min=\"1\" max=\"1\" width=\"20\" customWidth=\"1\"/><col min=\"2\" max=\"4\" width=\"16\" customWidth=\"1\"/><col min=\"5\" max=\"5\" width=\"30\" customWidth=\"1\"/><col min=\"6\" max=\"11\" width=\"14\" customWidth=\"1\"/></cols><sheetData>" + rows + "</sheetData></worksheet>"
    ZipOutputStream(out.outputStream().buffered()).use { z ->
        for ((name, content) in files) {
            z.putNextEntry(ZipEntry(name))
            z.write(content.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
    }
}

private fun FloatMoney.shareFile(f: File, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND)
        i.type = mime
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Save / Share: " + f.name))
    } catch (e: Exception) {
        Toast.makeText(this, "Share fail: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}

internal fun FloatMoney.exportXlsx() {
    if (!MoneyRepo.loaded) return
    if (MoneyRepo.txs().isEmpty()) { Toast.makeText(this, "Abhi koi entry nahi hai.", Toast.LENGTH_SHORT).show(); return }
    try {
        val dir = File(cacheDir, "exports"); dir.mkdirs()
        val f = File(dir, "Money_Manager_" + stamp() + ".xlsx")
        buildXlsx(f)
        shareFile(f, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    } catch (e: Exception) {
        Toast.makeText(this, "Export error: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}

internal fun FloatMoney.exportJson() {
    if (!MoneyRepo.loaded) return
    try {
        val dir = File(cacheDir, "exports"); dir.mkdirs()
        val f = File(dir, "money_manager_backup_" + stamp() + ".json")
        f.writeText(MoneyRepo.blob.toString())
        shareFile(f, "application/json")
    } catch (e: Exception) {
        Toast.makeText(this, "Export error: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}

/** Backup (.json) merge: duplicate entries skip, purana data safe. */
internal fun FloatMoney.importBackup(uri: Uri) {
    if (!canEdit()) return
    try {
        val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: throw Exception("File khuli nahi")
        val j = JSONObject(txt)
        val jc = j.optJSONArray("cats")
        val jt = j.optJSONArray("tx")
        if (jc == null || jt == null) { Toast.makeText(this, "Ye valid backup file nahi hai.", Toast.LENGTH_LONG).show(); return }
        val idMap = HashMap<String, String>()
        var addC = 0
        var addT = 0
        var skip = 0
        for (i in 0 until jc.length()) {
            val c = jc.optJSONObject(i) ?: continue
            val type = c.optString("type")
            val name = c.optString("name")
            var ex = MoneyRepo.cats().firstOrNull { it.optString("type") == type && it.optString("name").equals(name, ignoreCase = true) }
            if (ex == null) {
                ex = JSONObject()
                ex.put("id", c.optString("id")); ex.put("type", type); ex.put("name", name)
                ex.put("subs", c.optJSONArray("subs") ?: JSONArray())
                MoneyRepo.blob.getJSONArray("cats").put(ex)
                addC++
            }
            idMap[c.optString("id")] = ex.optString("id")
        }
        val jb = j.optJSONObject("budgets")
        if (jb != null) {
            val it = jb.keys()
            while (it.hasNext()) {
                val id = it.next()
                val nid = idMap[id]
                if (nid != null && budgetOf(nid) <= 0) setBudget(nid, jb.optDouble(id, 0.0))
            }
        }
        val have = HashSet<String>()
        for (t in MoneyRepo.txs()) have.add(t.optString("id"))
        val arr = MoneyRepo.blob.getJSONArray("tx")
        for (i in 0 until jt.length()) {
            val t = jt.optJSONObject(i) ?: continue
            val id = t.optString("id")
            if (have.contains(id)) { skip++; continue }
            val cp0 = JSONObject(t.toString())
            cp0.put("cat", idMap[t.optString("cat")] ?: t.optString("cat"))
            arr.put(cp0)
            have.add(id)
            addT++
        }
        persist()
        render()
        Toast.makeText(this, "✓ $addT entries + $addC categories import hui" + (if (skip > 0) " ($skip pehle se thi)" else ""), Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(this, "Import error: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}
