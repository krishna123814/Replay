package com.example.simplebrowser.krishna

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.simplebrowser.routine.RoutineNet
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 🦚 Krishna — apni heading (jaise Practice and Run, Money Manager) + har heading me Problem aur Solution.
 * Data: SharedPreferences "krishna" -> "data" = {"v":1,"h":[{"id","t","e":[{"id","p","s"}]}],"t":<ms>}
 * Sync (server ke 🦚 panel ke saath): GET dav/app_state/krishna.json, save = POST api/local_state_save {kind:"krishna"}
 * (RoutineNet, File Manager wala login). Whole-blob latest-wins: jiska "t" bada, wahi jeeta.
 */
class KrishnaActivity : AppCompatActivity() {

    private val BG = Color.parseColor("#131722")
    private val PANEL = Color.parseColor("#1E222D")
    private val LINE = Color.parseColor("#2A2E39")
    private val MUT = Color.parseColor("#848DA0")
    private val ACC = Color.parseColor("#FF6150")
    private val GREEN = Color.parseColor("#26A69A")

    private lateinit var titleTv: TextView
    private lateinit var backBtn: TextView
    private lateinit var editBtn: TextView
    private lateinit var syncTv: TextView
    private lateinit var listBox: LinearLayout
    private var cur: String? = null

    private val ui = Handler(Looper.getMainLooper())
    private var syncing = false
    private var syncErr = ""
    @Volatile private var ver = 0
    private val tick = object : Runnable {
        override fun run() { sync(false); ui.postDelayed(this, 20000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun load(): JSONObject {
        return try {
            val o = JSONObject(getSharedPreferences("krishna", MODE_PRIVATE).getString("data", "") ?: "")
            if (o.optJSONArray("h") == null) o.put("h", JSONArray())
            norm(o)
            o
        } catch (_: Throwable) {
            JSONObject().put("v", 1).put("h", JSONArray())
        }
    }

    /** Purana format {p, s:"text"} -> naya {p, sl:[{id,x}]} (ek problem ke multiple solutions). */
    private fun norm(o: JSONObject) {
        val hs = o.optJSONArray("h") ?: return
        for (i in 0 until hs.length()) {
            val es = hs.getJSONObject(i).optJSONArray("e") ?: continue
            for (j in 0 until es.length()) {
                val e = es.getJSONObject(j)
                if (e.optJSONArray("sl") == null) {
                    val sl = JSONArray()
                    val old = e.optString("s").trim()
                    if (old.isNotEmpty()) sl.put(JSONObject().put("id", newId()).put("x", old))
                    e.put("sl", sl)
                }
                e.remove("s")
            }
        }
    }

    /** Apna change: t = abhi, dirty, thodi der me server par. */
    private fun save(o: JSONObject) {
        synchronized(this) {
            o.put("t", System.currentTimeMillis())
            getSharedPreferences("krishna", MODE_PRIVATE).edit()
                .putString("data", o.toString()).putBoolean("dirty", true).apply()
            ver++
        }
        chip()
        ui.removeCallbacks(kick)
        ui.postDelayed(kick, 1200)
    }

    private val kick = Runnable { sync(false) }

    private fun isDirty() = getSharedPreferences("krishna", MODE_PRIVATE).getBoolean("dirty", false)

    private fun chip() {
        val (txt, col) = when {
            syncing -> "☁ …" to Color.parseColor("#90CAF9")
            syncErr.isNotEmpty() -> "☁ ⚠" to Color.parseColor("#EF5350")
            isDirty() -> "☁ ↑" to Color.parseColor("#FFB74D")
            else -> "☁ ✓" to GREEN
        }
        syncTv.text = txt
        syncTv.setTextColor(col)
    }

    /** Server se: @return true agar phone ka data badla (render chahiye). */
    private fun pull(): Boolean {
        val sp = getSharedPreferences("krishna", MODE_PRIVATE)
        val (code, body) = RoutineNet.http(applicationContext, "GET", "dav/app_state/krishna.json", "")
        if (code == 401) throw IOException("Login galat (401)")
        val lv = synchronized(this) { load() }
        if (code == 404) {   // server par file nahi: jo phone me hai wahi bhejo
            if (lv.getJSONArray("h").length() > 0) sp.edit().putBoolean("dirty", true).apply()
            return false
        }
        if (code < 200 || code >= 300) throw IOException("HTTP $code")
        val j = try { JSONObject(body) } catch (_: Throwable) { throw IOException("server data JSON nahi hai") }
        if (j.optJSONArray("h") == null) throw IOException("server data ka shape galat")
        return synchronized(this) {
            val cur0 = load()
            if (sp.getBoolean("dirty", false) || cur0.optLong("t") > j.optLong("t")) {
                sp.edit().putBoolean("dirty", true).apply()   // phone aage hai: push karenge
                false
            } else if (cur0.toString() == j.toString()) {
                false
            } else {
                sp.edit().putString("data", j.toString()).apply()
                true
            }
        }
    }

    private fun push() {
        val sp = getSharedPreferences("krishna", MODE_PRIVATE)
        if (!sp.getBoolean("dirty", false)) return
        val (v0, blob) = synchronized(this) { Pair(ver, load()) }
        val req = JSONObject().put("kind", "krishna").put("seq", System.currentTimeMillis()).put("payload", blob)
        val (code, txt) = RoutineNet.http(applicationContext, "POST", "api/local_state_save", req.toString())
        val ack = try { JSONObject(txt) } catch (_: Throwable) { null }
        if (code == 200 && ack != null && ack.optBoolean("ok", false)) {
            synchronized(this) { if (ver == v0) sp.edit().putBoolean("dirty", false).apply() }
        } else {
            throw IOException(ack?.optString("msg")?.takeIf { it.isNotBlank() } ?: "HTTP $code")
        }
    }

    private fun sync(manual: Boolean) {
        if (syncing) return
        syncing = true; syncErr = ""; chip()
        RoutineNet.io.execute {
            var changed = false
            var err = ""
            try { changed = pull(); push() } catch (e: Throwable) { err = e.message ?: "sync fail" }
            runOnUiThread {
                syncing = false; syncErr = err; chip()
                if (isDestroyed) return@runOnUiThread
                if (changed) render()
                if (manual) android.widget.Toast.makeText(this,
                    if (err.isEmpty()) "☁ Sync ho gaya" else "⚠ $err", android.widget.Toast.LENGTH_SHORT).show()
                if (err.isEmpty() && isDirty()) { ui.removeCallbacks(kick); ui.postDelayed(kick, 1500) }
            }
        }
    }

    override fun onResume() { super.onResume(); ui.removeCallbacks(tick); ui.post(tick) }
    override fun onPause() {
        ui.removeCallbacks(tick); ui.removeCallbacks(kick)
        if (isDirty()) sync(false)   // band hone se pehle bhej do
        super.onPause()
    }

    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36) + (1000..9999).random()

    private fun findHead(o: JSONObject, id: String?): JSONObject? {
        val a = o.getJSONArray("h")
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i)
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(BG); fitsSystemWindows = true }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        val hdr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(PANEL)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        backBtn = hdrBtn("←") { goBack() }
        titleTv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        editBtn = hdrBtn("✏️") { editHeading(cur) }
        syncTv = TextView(this).apply {
            textSize = 12f; setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { sync(true) }
        }
        hdr.addView(backBtn)
        hdr.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        hdr.addView(syncTv)
        hdr.addView(editBtn)
        col.addView(hdr, LinearLayout.LayoutParams(-1, -2))

        val sv = ScrollView(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(90)) }
        sv.addView(listBox, ViewGroup.LayoutParams(-1, -2))
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        val fab = TextView(this).apply {
            text = "+"; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACC) }
            elevation = dp(6).toFloat()
            setOnClickListener { if (cur == null) editHeading(null) else editProblem(null) }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(18), dp(18))
        })

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (cur != null) goBack() else finish()
            }
        })
        render()
        chip()
    }

    private fun hdrBtn(t: String, click: () -> Unit) = TextView(this).apply {
        text = t; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(1), LINE) }
        setOnClickListener { click() }
    }

    private fun goBack() { cur = null; render() }

    private fun render() {
        val d = load()
        val h = if (cur != null) findHead(d, cur) else null
        if (cur != null && h == null) cur = null
        backBtn.visibility = if (h != null) View.VISIBLE else View.GONE
        editBtn.visibility = if (h != null) View.VISIBLE else View.GONE
        titleTv.text = h?.optString("t") ?: "🦚 Krishna"
        listBox.removeAllViews()

        if (h == null) {
            val a = d.getJSONArray("h")
            if (a.length() == 0) { listBox.addView(emptyView("+ dabao aur heading likho")); return }
            for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    setOnClickListener { cur = x.optString("id"); render() }
                }
                row.addView(TextView(this).apply {
                    text = x.optString("t"); setTextColor(Color.WHITE); textSize = 15f
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this).apply {
                    text = x.optJSONArray("e")?.length()?.toString() ?: "0"
                    setTextColor(MUT); textSize = 12f
                })
                listBox.addView(row)
                listBox.addView(View(this).apply { setBackgroundColor(PANEL) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
            return
        }

        val e = h.optJSONArray("e") ?: JSONArray().also { h.put("e", it) }
        if (e.length() == 0) { listBox.addView(emptyView("+ dabao aur Problem likho")); return }
        for (i in 0 until e.length()) {
            val x = e.getJSONObject(i)
            val eid = x.optString("id")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(PANEL); cornerRadius = dp(10).toFloat(); setStroke(dp(1), LINE)
                }
            }
            card.addView(small("Problem"))
            card.addView(TextView(this).apply {
                text = x.optString("p"); setTextColor(Color.WHITE); textSize = 14f
                setPadding(0, 0, 0, dp(8))
                setOnClickListener { editProblem(eid) }
            })
            card.addView(small("Solution"))
            val sl = x.optJSONArray("sl") ?: JSONArray()
            for (k in 0 until sl.length()) {
                val so = sl.getJSONObject(k)
                val sid = so.optString("id")
                card.addView(TextView(this).apply {
                    text = "${k + 1}. " + so.optString("x"); setTextColor(GREEN); textSize = 14f
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { editSol(eid, sid) }
                })
            }
            card.addView(TextView(this).apply {
                text = "+ Solution"; setTextColor(Color.parseColor("#90CAF9")); textSize = 13f
                setPadding(0, dp(8), 0, 0)
                setOnClickListener { editSol(eid, null) }
            })
            listBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(10), dp(10), 0) })
        }
    }

    private fun small(t: String) = TextView(this).apply { text = t; setTextColor(MUT); textSize = 11f }

    private fun emptyView(t: String) = TextView(this).apply {
        text = t; setTextColor(MUT); gravity = Gravity.CENTER; setPadding(dp(20), dp(40), dp(20), dp(40))
    }

    private fun field(hint: String, value: String, lines: Int): EditText = EditText(this).apply {
        this.hint = hint; setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START }
    }

    private fun editHeading(id: String?) {
        val d = load()
        val x = if (id != null) findHead(d, id) else null
        val et = field("Heading", x?.optString("t") ?: "", 1)
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
        val b = AlertDialog.Builder(this).setTitle("Heading").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                val dd = load()
                val y = if (id != null) findHead(dd, id) else null
                if (y != null) y.put("t", t)
                else dd.getJSONArray("h").put(JSONObject().put("id", newId()).put("t", t).put("e", JSONArray()))
                save(dd); render()
            }
            .setNegativeButton("Cancel", null)
        if (x != null) b.setNeutralButton("Delete") { _, _ ->
            AlertDialog.Builder(this).setMessage("Ye heading aur uske saare Problem/Solution delete karu?")
                .setPositiveButton("Delete") { _, _ ->
                    val dd = load(); val a = dd.getJSONArray("h"); val n = JSONArray()
                    for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") != id) n.put(a.getJSONObject(i))
                    dd.put("h", n); save(dd); cur = null; render()
                }.setNegativeButton("Cancel", null).show()
        }
        b.show()
    }

    private fun findEntry(h: JSONObject, id: String?): JSONObject? {
        val e = h.optJSONArray("e") ?: return null
        for (i in 0 until e.length()) if (e.getJSONObject(i).optString("id") == id) return e.getJSONObject(i)
        return null
    }

    private fun boxOf(vararg v: View): ScrollView {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        for (x in v) box.addView(x, LinearLayout.LayoutParams(-1, -2))
        return ScrollView(this).apply { addView(box) }
    }

    /** Problem add / edit. Naya problem: saath me pehla Solution bhi likh sakte ho. */
    private fun editProblem(id: String?) {
        val h0 = findHead(load(), cur) ?: return
        val x0 = if (id != null) findEntry(h0, id) else null
        val p = field("Problem", x0?.optString("p") ?: "", 3)
        val s1 = field("Solution", "", 3)
        val b = AlertDialog.Builder(this).setTitle("Problem")
            .setView(if (x0 == null) boxOf(p, s1) else boxOf(p))
            .setPositiveButton("Save") { _, _ ->
                val pt = p.text.toString().trim()
                if (pt.isEmpty()) return@setPositiveButton
                val dd = load(); val hh = findHead(dd, cur) ?: return@setPositiveButton
                val ee = hh.optJSONArray("e") ?: JSONArray().also { hh.put("e", it) }
                val y = if (id != null) findEntry(hh, id) else null
                if (y != null) y.put("p", pt)
                else {
                    val sl = JSONArray()
                    val st = s1.text.toString().trim()
                    if (st.isNotEmpty()) sl.put(JSONObject().put("id", newId()).put("x", st))
                    val n = JSONArray()
                    n.put(JSONObject().put("id", newId()).put("p", pt).put("sl", sl))
                    for (i in 0 until ee.length()) n.put(ee.getJSONObject(i))
                    hh.put("e", n)
                }
                save(dd); render()
            }
            .setNegativeButton("Cancel", null)
        if (x0 != null) b.setNeutralButton("Delete") { _, _ ->
            AlertDialog.Builder(this).setMessage("Ye Problem aur uske saare Solution delete karu?")
                .setPositiveButton("Delete") { _, _ ->
                    val dd = load(); val hh = findHead(dd, cur) ?: return@setPositiveButton
                    val ee = hh.optJSONArray("e") ?: JSONArray(); val n = JSONArray()
                    for (i in 0 until ee.length()) if (ee.getJSONObject(i).optString("id") != id) n.put(ee.getJSONObject(i))
                    hh.put("e", n); save(dd); render()
                }.setNegativeButton("Cancel", null).show()
        }
        b.show()
    }

    /** Ek Problem ke andar Solution add / edit (sid == null => naya). */
    private fun editSol(eid: String, sid: String?) {
        val h0 = findHead(load(), cur) ?: return
        val e0 = findEntry(h0, eid) ?: return
        var old: JSONObject? = null
        val sl0 = e0.optJSONArray("sl") ?: JSONArray()
        if (sid != null) for (i in 0 until sl0.length()) if (sl0.getJSONObject(i).optString("id") == sid) old = sl0.getJSONObject(i)
        val et = field("Solution", old?.optString("x") ?: "", 3)
        val b = AlertDialog.Builder(this).setTitle("Solution").setView(boxOf(et))
            .setPositiveButton("Save") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                val dd = load(); val hh = findHead(dd, cur) ?: return@setPositiveButton
                val ee = findEntry(hh, eid) ?: return@setPositiveButton
                val sl = ee.optJSONArray("sl") ?: JSONArray().also { ee.put("sl", it) }
                var y: JSONObject? = null
                if (sid != null) for (i in 0 until sl.length()) if (sl.getJSONObject(i).optString("id") == sid) y = sl.getJSONObject(i)
                if (y != null) y.put("x", t) else sl.put(JSONObject().put("id", newId()).put("x", t))
                save(dd); render()
            }
            .setNegativeButton("Cancel", null)
        if (old != null) b.setNeutralButton("Delete") { _, _ ->
            val dd = load(); val hh = findHead(dd, cur) ?: return@setNeutralButton
            val ee = findEntry(hh, eid) ?: return@setNeutralButton
            val sl = ee.optJSONArray("sl") ?: JSONArray(); val n = JSONArray()
            for (i in 0 until sl.length()) if (sl.getJSONObject(i).optString("id") != sid) n.put(sl.getJSONObject(i))
            ee.put("sl", n); save(dd); render()
        }
        b.show()
    }
}
