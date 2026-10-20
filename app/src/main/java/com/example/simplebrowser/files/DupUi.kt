package com.example.simplebrowser.files

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 🧬 Duplicate finder: kisi bhi storage (AIC / HF / Phone / SD / USB) ke folder me (andar ke sab folders samet)
 * duplicate video / music / image dhundta hai. Match ke tarike: naam+size, sirf size, sirf naam, milta-julta naam,
 * aur phone par file ke andar ka content. Phir chuni hui copies delete kar sakte ho.
 */
class DupUi(
    private val act: Context,
    private val store: Store,
    private val rootLabel: String,
    private val onDone: () -> Unit = {}
) {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MAX_GROUPS = 200

    private class DupFile(val e: Entry, val rel: String)
    private class Group(val key: String, val items: List<DupFile>, val showSize: Boolean) {
        val extra: Long = items.sumOf { it.e.size } - (items.maxOfOrNull { it.e.size } ?: 0L)
    }

    private lateinit var dlg: AlertDialog
    private lateinit var titleTv: TextView
    private lateinit var typeRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var hintTv: TextView
    private lateinit var sumTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var delBtn: Button
    private lateinit var listBox: LinearLayout

    private var startUrl = ""
    private var cancel = AtomicBoolean(false)
    @Volatile private var gen = 0
    private var files: MutableList<DupFile> = ArrayList()
    private var scanning = false
    private var mode = 0          // 0 naam+size · 1 size · 2 naam · 3 milta naam · 4 content (phone)
    private var typeFilter = 0    // 0 sab · 1 image · 2 video · 3 music
    private val selected = HashSet<String>()
    private var groups: List<Group> = emptyList()
    private val hashes = ConcurrentHashMap<String, String>()

    private val modeNames = arrayOf("Naam + Size", "Sirf Size", "Sirf Naam", "Milta-julta naam", "Content (phone)")
    private val modeHints = arrayOf(
        "Naam aur size dono same = pakki copy.",
        "Size bilkul same (naam alag bhi) = rename hui copy ho sakti hai.",
        "Naam same par size alag = alag file bhi ho sakti hai, dhyan se chuno.",
        "(1), copy jaise hisse hata kar naam milaya. Size alag ho sakta hai.",
        "Phone / SD par file ke andar ka data (shuru, beech, ant) milaya = sabse pakka."
    )
    private val typeNames = arrayOf("Sab", "🖼 Image", "🎬 Video", "🎵 Music")

    private fun dp(v: Int): Int = act.dp(v)

    fun show(from: String) {
        startUrl = Dav.withSlash(from)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(12), dp(14), dp(4))

        titleTv = TextView(act)
        titleTv.textSize = 18f
        titleTv.setTextColor(Color.BLACK)
        titleTv.typeface = Typeface.DEFAULT_BOLD
        titleTv.maxLines = 2
        titleTv.ellipsize = TextUtils.TruncateAt.MIDDLE
        titleTv.text = "🧬 Duplicates · " + (if (LocalFs.isLocal(startUrl)) LocalFs.nameOf(startUrl).ifEmpty { rootLabel } else rootLabel)
        col.addView(titleTv, LinearLayout.LayoutParams(MATCH, WRAP))

        val sub = TextView(act)
        sub.textSize = 11.5f
        sub.setTextColor(0xFF666666.toInt())
        sub.text = "Is folder + uske andar ke saare folders me video / music / image dhundta hai"
        col.addView(sub, LinearLayout.LayoutParams(MATCH, WRAP))

        typeRow = LinearLayout(act)
        typeRow.orientation = LinearLayout.HORIZONTAL
        col.addView(scrollRow(typeRow), LinearLayout.LayoutParams(MATCH, WRAP))

        modeRow = LinearLayout(act)
        modeRow.orientation = LinearLayout.HORIZONTAL
        col.addView(scrollRow(modeRow), LinearLayout.LayoutParams(MATCH, WRAP))

        hintTv = TextView(act)
        hintTv.textSize = 12f
        hintTv.setTextColor(0xFF444444.toInt())
        hintTv.setPadding(0, dp(2), 0, dp(2))
        col.addView(hintTv, LinearLayout.LayoutParams(MATCH, WRAP))

        val btns = LinearLayout(act)
        btns.orientation = LinearLayout.HORIZONTAL
        val auto = Button(act)
        auto.text = "⚡ Auto-chuno"
        auto.textSize = 11.5f
        auto.setOnClickListener { autoSelect() }
        val clear = Button(act)
        clear.text = "✕ Clear"
        clear.textSize = 11.5f
        clear.setOnClickListener {
            selected.clear()
            render()
        }
        val again = Button(act)
        again.text = "🔄 Dobara"
        again.textSize = 11.5f
        again.setOnClickListener { scan() }
        btns.addView(auto, LinearLayout.LayoutParams(0, WRAP, 1.3f))
        btns.addView(clear, LinearLayout.LayoutParams(0, WRAP, 1f))
        btns.addView(again, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP))

        delBtn = Button(act)
        delBtn.textSize = 12.5f
        delBtn.setOnClickListener { confirmDelete() }
        col.addView(delBtn, LinearLayout.LayoutParams(MATCH, WRAP))

        sumTv = TextView(act)
        sumTv.textSize = 13f
        sumTv.setTextColor(Color.BLACK)
        sumTv.typeface = Typeface.DEFAULT_BOLD
        sumTv.setPadding(0, dp(6), 0, dp(2))
        col.addView(sumTv, LinearLayout.LayoutParams(MATCH, WRAP))

        statusTv = TextView(act)
        statusTv.textSize = 12.5f
        statusTv.setTextColor(0xFFE65100.toInt())
        col.addView(statusTv, LinearLayout.LayoutParams(MATCH, WRAP))

        listBox = LinearLayout(act)
        listBox.orientation = LinearLayout.VERTICAL
        col.addView(listBox, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(act)
        scroll.addView(col)

        dlg = OvlBuilder(act)
            .setView(scroll)
            .setNegativeButton("Band", null)
            .create()
        dlg.setOnDismissListener {
            cancel.set(true)
            gen++
        }
        dlg.show()

        rebuildChips()
        updateDelBtn()
        scan()
    }

    private fun scrollRow(inner: LinearLayout): HorizontalScrollView {
        val h = HorizontalScrollView(act)
        h.isHorizontalScrollBarEnabled = false
        h.addView(inner)
        return h
    }

    private fun chip(label: String, on: Boolean, click: () -> Unit): TextView {
        val t = TextView(act)
        t.text = label
        t.textSize = 12f
        t.gravity = Gravity.CENTER
        t.setTextColor(if (on) Color.WHITE else Color.BLACK)
        t.setPadding(dp(10), dp(6), dp(10), dp(6))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(if (on) 0xFFE65100.toInt() else 0xFFE0E0E0.toInt())
        t.background = bg
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.setMargins(0, dp(3), dp(6), dp(3))
        t.layoutParams = lp
        t.setOnClickListener { click() }
        return t
    }

    private fun rebuildChips() {
        typeRow.removeAllViews()
        for (i in typeNames.indices) {
            typeRow.addView(chip(typeNames[i], typeFilter == i) {
                typeFilter = i
                afterFilterChange()
            })
        }
        modeRow.removeAllViews()
        for (i in modeNames.indices) {
            modeRow.addView(chip(modeNames[i], mode == i) {
                if (i == 4 && !LocalFs.isLocal(startUrl)) {
                    Toast.makeText(act, "Content check sirf Phone / SD / USB par ho sakta hai (server ki file download karni padti)", Toast.LENGTH_LONG).show()
                } else {
                    mode = i
                    afterFilterChange()
                }
            })
        }
        hintTv.text = modeHints[mode]
    }

    private fun afterFilterChange() {
        rebuildChips()
        if (!scanning) {
            if (mode == 4) startHashing() else render()
        }
    }

    // ───────────────────────── scan ─────────────────────────

    private fun alive(g: Int): Boolean = g == gen && !cancel.get()

    private fun scan() {
        cancel.set(true)
        val my = AtomicBoolean(false)
        cancel = my
        gen++
        val g = gen
        scanning = true
        files = ArrayList()
        selected.clear()
        hashes.clear()
        groups = emptyList()
        listBox.removeAllViews()
        sumTv.text = ""
        statusTv.text = "⏳ Scan ho raha hai…"
        updateDelBtn()

        Thread {
            val found = ArrayList<DupFile>()
            var folders = 0
            var failed = 0
            var err: String? = null
            val stack = java.util.ArrayDeque<Pair<String, String>>()
            stack.push(Pair(startUrl, ""))
            try {
                while (!stack.isEmpty()) {
                    if (my.get()) break
                    val cur = stack.pop()
                    val list = try {
                        Fs.list(cur.first)
                    } catch (e: DavAuthException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        continue
                    }
                    for (e in list) {
                        if (e.isDir) {
                            // chhupe folders (.thumbnails, .trash...) aur Android/data ka cache chhodo
                            if (e.name.startsWith(".")) continue
                            if (cur.second.isEmpty() && e.name == "Android" && LocalFs.isLocal(cur.first)) continue
                            stack.push(Pair(e.url, cur.second + e.name + "/"))
                        } else if (e.kind == Kind.IMAGE || e.kind == Kind.VIDEO || e.kind == Kind.AUDIO) {
                            found.add(DupFile(e, cur.second))
                        }
                    }
                    folders++
                    if (folders % 5 == 0) {
                        val n = folders
                        val m = found.size
                        Ui.post(act) { if (alive(g)) statusTv.text = "⏳ Scan ho raha hai… $n folder · $m media file" }
                    }
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            Ui.post(act) {
                if (g != gen) return@post
                scanning = false
                if (my.get()) {
                    statusTv.text = "⏹ Scan roka gaya"
                    return@post
                }
                files = found
                statusTv.text = when {
                    err != null -> "❌ $err"
                    failed > 0 -> "⚠ $failed folder padh nahi paye"
                    else -> ""
                }
                if (mode == 4) startHashing() else render()
            }
        }.start()
    }

    // ───────────────────────── content hash (phone) ─────────────────────────

    private val CHUNK = 256 * 1024

    private fun quickHash(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        val len = f.length()
        md.update(len.toString().toByteArray())
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(CHUNK)
            fun part(pos: Long) {
                r.seek(pos)
                val n = r.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
            if (len <= CHUNK * 3L) {
                part(0)
                var read = CHUNK.toLong()
                while (read < len) {
                    part(read)
                    read += CHUNK
                }
            } else {
                part(0)
                part(len / 2 - CHUNK / 2)
                part(len - CHUNK)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun startHashing() {
        val g = gen
        val need = filtered().filter { it.e.size > 0 }
            .groupBy { it.e.size }.values.filter { it.size >= 2 }.flatten()
            .filter { !hashes.containsKey(it.e.url) }
        if (need.isEmpty()) {
            render()
            return
        }
        scanning = true
        statusTv.text = "⏳ Content check 0/${need.size}…"
        Thread {
            var done = 0
            for (d in need) {
                if (!alive(g)) break
                hashes[d.e.url] = try {
                    quickHash(LocalFs.fileOf(d.e.url))
                } catch (e: Exception) {
                    "err:" + d.e.url
                }
                done++
                if (done % 3 == 0) {
                    val n = done
                    Ui.post(act) { if (alive(g)) statusTv.text = "⏳ Content check $n/${need.size}…" }
                }
            }
            Ui.post(act) {
                if (g != gen) return@post
                scanning = false
                if (!cancel.get()) {
                    statusTv.text = ""
                    render()
                }
            }
        }.start()
    }

    // ───────────────────────── grouping + render ─────────────────────────

    private fun filtered(): List<DupFile> = when (typeFilter) {
        1 -> files.filter { it.e.kind == Kind.IMAGE }
        2 -> files.filter { it.e.kind == Kind.VIDEO }
        3 -> files.filter { it.e.kind == Kind.AUDIO }
        else -> files
    }

    private val copyRe1 = Regex("\\s*\\(\\d+\\)\\z")
    private val copyRe2 = Regex("[\\s_-]*(copy|copy\\s*\\d+|duplicate)\\z", RegexOption.IGNORE_CASE)

    private fun norm(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        var b = name.substringBeforeLast('.', name).trim()
        var prev: String
        do {
            prev = b
            b = copyRe1.replace(b, "")
            b = copyRe2.replace(b, "").trim()
        } while (b != prev)
        if (b.lowercase().startsWith("copy of ")) b = b.substring(8)
        return b.lowercase() + "." + ext
    }

    private fun computeGroups(): List<Group> {
        val src = filtered()
        val map = LinkedHashMap<String, MutableList<DupFile>>()
        for (d in src) {
            val e = d.e
            val key: String = when (mode) {
                0 -> e.name.lowercase() + "|" + e.size
                1 -> if (e.size > 0) e.size.toString() else continue
                2 -> e.name.lowercase()
                3 -> norm(e.name)
                else -> {
                    val h = hashes[d.e.url] ?: continue
                    if (e.size <= 0 || h.startsWith("err:")) continue
                    e.size.toString() + "|" + h
                }
            }
            map.getOrPut(key) { ArrayList() }.add(d)
        }
        val out = ArrayList<Group>()
        for ((k, v) in map) {
            if (v.size < 2) continue
            val sorted = v.sortedBy { it.e.modified }
            out.add(Group(k, sorted, mode == 2 || mode == 3))
        }
        out.sortByDescending { it.extra }
        return out
    }

    private fun render() {
        listBox.removeAllViews()
        groups = computeGroups()
        // jo chuni hui file ab kisi group me nahi, use selection se hatao
        val live = HashSet<String>()
        for (gr in groups) for (d in gr.items) live.add(d.e.url)
        selected.retainAll(live)

        if (files.isEmpty()) {
            sumTv.text = "Is folder me koi video / music / image nahi mila"
            updateDelBtn()
            return
        }
        if (groups.isEmpty()) {
            sumTv.text = "✅ Is tarike se koi duplicate nahi mila (${filtered().size} file dekhi)"
            updateDelBtn()
            return
        }
        val extraTotal = groups.sumOf { it.extra }
        val copies = groups.sumOf { it.items.size - 1 }
        sumTv.text = "${groups.size} group · $copies extra copy · ${fmtSize(extraTotal)} jagah ghir rahi hai"

        val shown = if (groups.size > MAX_GROUPS) groups.subList(0, MAX_GROUPS) else groups
        for (gr in shown) addGroup(gr)
        if (groups.size > MAX_GROUPS) {
            val t = TextView(act)
            t.text = "… pehle $MAX_GROUPS group (sabse zyada jagah wale) dikhaye. Baaki ke liye filter lagao."
            t.textSize = 12f
            t.setTextColor(0xFF666666.toInt())
            t.setPadding(0, dp(8), 0, dp(8))
            listBox.addView(t)
        }
        updateDelBtn()
    }

    private fun addGroup(gr: Group) {
        val first = gr.items[0].e
        val head = TextView(act)
        val sizeTxt = if (gr.showSize) "" else " · " + fmtSize(first.size) + " each"
        head.text = Kinds.emoji(first.kind) + " " + first.name + "\n" +
            gr.items.size + " copy" + sizeTxt + " · " + fmtSize(gr.extra) + " extra"
        head.textSize = 13f
        head.setTextColor(Color.BLACK)
        head.typeface = Typeface.DEFAULT_BOLD
        head.setBackgroundColor(0xFFF0F0F0.toInt())
        head.setPadding(dp(8), dp(6), dp(8), dp(6))
        val hlp = LinearLayout.LayoutParams(MATCH, WRAP)
        hlp.topMargin = dp(10)
        listBox.addView(head, hlp)

        for (d in gr.items) {
            val cb = CheckBox(act)
            cb.text = d.e.name + "\n" +
                (if (d.rel.isEmpty()) "(isi folder me)" else d.rel) + " · " + fmtDate(d.e.modified) +
                (if (gr.showSize) " · " + fmtSize(d.e.size) else "")
            cb.textSize = 12f
            cb.setTextColor(Color.BLACK)
            cb.isChecked = d.e.url in selected
            val url = d.e.url
            cb.setOnCheckedChangeListener { _, on ->
                if (on) selected.add(url) else selected.remove(url)
                updateDelBtn()
            }
            listBox.addView(cb, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** Har group me sabse purani file rakho, baaki sab chun lo. */
    private fun autoSelect() {
        if (groups.isEmpty()) return
        selected.clear()
        for (gr in groups) {
            for (i in 1 until gr.items.size) selected.add(gr.items[i].e.url)
        }
        render()
        Toast.makeText(act, "Har group me sabse purani file rakhi hai, baaki chuni gayi. Delete se pehle ek baar dekh lo.", Toast.LENGTH_LONG).show()
    }

    private fun selectedFiles(): List<DupFile> {
        val out = ArrayList<DupFile>()
        val seen = HashSet<String>()
        for (gr in groups) for (d in gr.items) if (d.e.url in selected && seen.add(d.e.url)) out.add(d)
        return out
    }

    private fun updateDelBtn() {
        val sel = selectedFiles()
        val size = sel.sumOf { it.e.size }
        delBtn.text = if (sel.isEmpty()) "🗑 Delete (kuch chuna nahi)" else "🗑 Delete ${sel.size} file · ${fmtSize(size)}"
        delBtn.isEnabled = sel.isNotEmpty()
    }

    // ───────────────────────── delete ─────────────────────────

    private fun confirmDelete() {
        val sel = selectedFiles()
        if (sel.isEmpty()) return
        val urls = sel.map { it.e.url }.toHashSet()
        val wipedGroups = groups.count { gr -> gr.items.all { it.e.url in urls } }
        val sb = StringBuilder()
        sb.append("${sel.size} file (${fmtSize(sel.sumOf { it.e.size })}) delete hongi.")
        if (store == Store.LOCAL) sb.append("\n\n⚠ Phone se PERMANENTLY delete hoga (recycle bin nahi hai).")
        else if (store.isHf) sb.append("\n\n⚠ ${store.label} se PERMANENTLY delete hoga. Trash nahi hai, wapas nahi aayega.")
        if (wipedGroups > 0) {
            sb.append("\n\n❗ $wipedGroups group me SAARI copies chuni hui hain — wahan original bhi ud jayega!")
        }
        OvlBuilder(act)
            .setTitle("Duplicate delete karein?")
            .setMessage(sb.toString())
            .setPositiveButton("Delete") { _, _ -> doDelete(sel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete(sel: List<DupFile>) {
        val g = gen
        scanning = true
        delBtn.isEnabled = false
        Thread {
            val ok = HashSet<String>()
            var fail = 0
            var last = ""
            var n = 0
            for (d in sel) {
                try {
                    Fs.delete(d.e.url)
                    ok.add(d.e.url)
                } catch (e: Exception) {
                    fail++
                    last = e.message ?: ""
                }
                n++
                val k = n
                Ui.post(act) { if (g == gen) statusTv.text = "🗑 Delete ho raha hai $k/${sel.size}…" }
            }
            Ui.post(act) {
                if (g == gen) {
                    scanning = false
                    files = files.filter { it.e.url !in ok }.toMutableList()
                    selected.removeAll(ok)
                    statusTv.text = if (fail > 0) "⚠ $fail delete nahi hui ($last)" else ""
                    render()
                }
                Toast.makeText(act, "✅ ${ok.size} duplicate delete hui" + (if (fail > 0) ", $fail fail" else ""), Toast.LENGTH_LONG).show()
                onDone()
            }
        }.start()
    }
}
