package com.example.simplebrowser.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.simplebrowser.vault.CryptoVault
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * 🔒 Encrypted Vault (Cryptomator-compatible). SD card / phone ke kisi folder me vault banta hai;
 * laptop par (Windows / Linux / Mac) Cryptomator se wahi vault password dalke khulta hai.
 *
 * Intent extras: "dir" = abhi khula folder (yahin naya vault banega / yahan ke vaults dikhenge),
 *                "import" = String[] paths jo vault me daalne hain (move / copy).
 */
class VaultActivity : AppCompatActivity() {

    private var vault: CryptoVault? = null
    private var vaultDir: File? = null
    private val stack = ArrayList<Pair<String, String>>()      // (dirId, dikhne wala naam)
    private var items: List<CryptoVault.Item> = emptyList()
    private lateinit var listView: ListView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private var parentDir: File? = null
    private var pendingImport: List<File> = emptyList()
    private var busyDlg: AlertDialog? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val imgWorker = Executors.newFixedThreadPool(2)
    private var playedVault = false

    private val pickDocs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addFromUris(uris)
    }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // screenshot / recent apps me vault ki cheezein na dikhein
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        buildUi()
        parentDir = intent.getStringExtra("dir")?.let { File(it) }
        pendingImport = (intent.getStringArrayExtra("import") ?: emptyArray()).map { File(it) }.filter { it.exists() }
        wipeViewCache()
        showChooser()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (playedVault) PlaybackService.inst?.pauseNow()
        VaultSession.vault = null
        vault?.close()
        vault = null
        wipeViewCache()
        worker.shutdown()
        imgWorker.shutdown()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (vault != null && stack.size > 1) {
            stack.removeAt(stack.size - 1)
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(8), dp(8))
        }
        titleTv = TextView(this).apply { textSize = 18f; setTextColor(0xFF000000.toInt()); text = "🔒 Vault" }
        bar.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menu = TextView(this).apply {
            text = "⋮"; textSize = 24f; setTextColor(0xFF000000.toInt()); setPadding(dp(16), dp(4), dp(16), dp(4))
            setOnClickListener { showMenu() }
        }
        bar.addView(menu)
        root.addView(bar)
        subTv = TextView(this).apply { textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(dp(14), 0, dp(14), dp(8)) }
        root.addView(subTv)
        listView = ListView(this)
        listView.setOnItemClickListener { _, _, pos, _ -> onTap(items[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ -> onLong(items[pos]); true }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    // ───────────────────────── vault chooser / create / unlock ─────────────────────────

    private val prefs get() = getSharedPreferences("vaults", Context.MODE_PRIVATE)

    private fun knownVaults(): List<File> {
        val set = LinkedHashSet<File>()
        for (p in prefs.getStringSet("paths", emptySet()) ?: emptySet()) {
            val f = File(p)
            if (CryptoVault.isVault(f)) set.add(f)
        }
        parentDir?.let { d ->
            if (CryptoVault.isVault(d)) set.add(d)
            d.listFiles()?.forEach { if (it.isDirectory && CryptoVault.isVault(it)) set.add(it) }
        }
        return set.toList()
    }

    private fun remember(f: File) {
        val s = HashSet(prefs.getStringSet("paths", emptySet()) ?: emptySet())
        s.add(f.absolutePath)
        prefs.edit().putStringSet("paths", s).apply()
    }

    private fun showChooser() {
        val vs = knownVaults()
        val labels = ArrayList<String>()
        for (v in vs) labels.add("🔒 ${v.name}\n${v.absolutePath}")
        labels.add("➕ Naya vault banao")
        labels.add("❌ Band karo")
        val head = if (pendingImport.isNotEmpty()) "${pendingImport.size} item vault me daalne hain: vault chuno" else "Vault chuno"
        AlertDialog.Builder(this)
            .setTitle(head)
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < vs.size -> askPassword(vs[i])
                    i == vs.size -> askCreate()
                    else -> finish()
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun field(hint: String, pw: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        inputType = if (pw) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
    }

    private fun column(vararg v: android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        for (x in v) addView(x)
    }

    private fun askCreate() {
        val base = parentDir ?: Environment.getExternalStorageDirectory()
        val name = field("Vault folder ka naam", false).apply { setText("Vault") }
        val p1 = field("Password (kam se kam 8 akshar)", true)
        val p2 = field("Password dobara", true)
        val d = AlertDialog.Builder(this)
            .setTitle("Naya vault: ${base.absolutePath}")
            .setMessage("⚠ Password bhool gaye to data kabhi wapas nahi aayega. Koi recovery nahi hai.")
            .setView(column(name, p1, p2))
            .setPositiveButton("Banao", null)
            .setNegativeButton("Wapas") { _, _ -> showChooser() }
            .setOnCancelListener { showChooser() }
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            val a = p1.text.toString()
            when {
                n.isEmpty() || n.contains('/') || n.startsWith(".") -> Toast.makeText(this, "Folder ka naam sahi likho", Toast.LENGTH_SHORT).show()
                a.length < 8 -> Toast.makeText(this, "Password kam se kam 8 akshar ka rakho", Toast.LENGTH_SHORT).show()
                a != p2.text.toString() -> Toast.makeText(this, "Dono password alag hain", Toast.LENGTH_SHORT).show()
                File(base, n).exists() -> Toast.makeText(this, "Is naam ka folder pehle se hai", Toast.LENGTH_SHORT).show()
                else -> {
                    d.dismiss()
                    val target = File(base, n)
                    busy("Vault ban raha hai…")
                    worker.execute {
                        try {
                            val v = CryptoVault.create(target, a)
                            runOnUiThread { unlocked(v, target) }
                        } catch (e: Exception) {
                            runOnUiThread { unbusy(); Toast.makeText(this, "Vault nahi bana: ${e.message}", Toast.LENGTH_LONG).show(); showChooser() }
                        }
                    }
                }
            }
        }
    }

    private fun askPassword(dir: File) {
        val pw = field("Password", true)
        val d = AlertDialog.Builder(this)
            .setTitle("🔒 ${dir.name}")
            .setView(column(pw))
            .setPositiveButton("Kholo", null)
            .setNegativeButton("Wapas") { _, _ -> showChooser() }
            .setOnCancelListener { showChooser() }
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p = pw.text.toString()
            if (p.isEmpty()) return@setOnClickListener
            d.dismiss()
            busy("Khul raha hai…")
            worker.execute {
                try {
                    val v = CryptoVault.open(dir, p)
                    runOnUiThread { unlocked(v, dir) }
                } catch (e: CryptoVault.WrongPasswordException) {
                    runOnUiThread { unbusy(); Toast.makeText(this, "❌ Galat password", Toast.LENGTH_SHORT).show(); askPassword(dir) }
                } catch (e: Exception) {
                    runOnUiThread { unbusy(); Toast.makeText(this, e.message ?: "Vault nahi khula", Toast.LENGTH_LONG).show(); showChooser() }
                }
            }
        }
    }

    private fun unlocked(v: CryptoVault, dir: File) {
        unbusy()
        vault?.close()
        vault = v
        VaultSession.vault = v
        vaultDir = dir
        remember(dir)
        stack.clear()
        stack.add(Pair(CryptoVault.ROOT, dir.name))
        if (pendingImport.isNotEmpty()) askImportMode() else refresh()
    }

    // ───────────────────────── browsing ─────────────────────────

    private fun curId(): String = stack.last().first

    private fun refresh() {
        val v = vault ?: return
        val id = curId()
        titleTv.text = "🔒 " + stack.joinToString(" / ") { it.second }
        worker.execute {
            try {
                val l = v.list(id)
                runOnUiThread {
                    items = l
                    val labels = l.map { it ->
                        if (it.isDir) "📁 ${it.name}"
                        else "${Kinds.emoji(Kinds.of(it.name, false))} ${it.name}\n${fmtSize(it.size)} · ${fmtDate(it.modified)}"
                    }
                    listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                    subTv.text = if (l.isEmpty()) "Khaali. ⋮ → files jodo" else "${l.size} item · encrypted"
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "List nahi bani: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun onTap(item: CryptoVault.Item) {
        if (item.isDir) {
            stack.add(Pair(item.dirId!!, item.name))
            refresh()
            return
        }
        when (Kinds.of(item.name, false)) {
            Kind.VIDEO, Kind.AUDIO -> playMedia(item)
            Kind.IMAGE -> {
                val imgs = items.filter { !it.isDir && Kinds.of(it.name, false) == Kind.IMAGE }
                showImages(imgs, imgs.indexOfFirst { it.name == item.name }.coerceAtLeast(0))
            }
            else -> openFile(item)
        }
    }

    /** Video / audio: app ke player me seedha stream (poori file decrypt nahi hoti, seek chalta hai). */
    private fun playMedia(item: CryptoVault.Item) {
        val kind = Kinds.of(item.name, false)
        val pid = curId()
        val same = items.filter { !it.isDir && Kinds.of(it.name, false) == kind }
        VaultSession.vault = vault
        playedVault = true
        Queue.media = same.map { Entry(it.name, VaultSession.url(pid, it.name), false, it.size, it.modified) }
        Queue.mediaIndex = same.indexOfFirst { it.name == item.name }.coerceAtLeast(0)
        Queue.subs = emptyMap()
        Queue.pending = true
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("video", kind == Kind.VIDEO))
    }

    /** Photos: swipe + pinch-zoom, vault se seedha decrypt (disk par plain file nahi banti). */
    private fun showImages(imgs: List<CryptoVault.Item>, start: Int) {
        val v = vault ?: return
        val pid = curId()
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val pager = androidx.viewpager2.widget.ViewPager2(this)
        pager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = imgs.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val iv = ZoomImageView(parent.context)
                iv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(iv) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val iv = holder.itemView as ZoomImageView
                iv.setImageBitmap(null)
                iv.tag = position
                imgWorker.execute {
                    val bm = try { decodeImage(v, pid, imgs[position].name) } catch (e: Throwable) { null }
                    runOnUiThread {
                        if (iv.tag == position) {
                            if (bm != null) iv.setImageBitmap(bm)
                            else Toast.makeText(this@VaultActivity, "Photo khul nahi payi", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        pager.setCurrentItem(start, false)
        dlg.setContentView(pager)
        dlg.show()
    }

    private fun decodeImage(v: CryptoVault, pid: String, name: String): android.graphics.Bitmap? {
        val bo = java.io.ByteArrayOutputStream()
        v.read(pid, name, bo)
        val bytes = bo.toByteArray()
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
        val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
        val bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: return null
        val deg = try {
            when (androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (deg == 0f) return bm
        val m = android.graphics.Matrix().apply { postRotate(deg) }
        return android.graphics.Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
    }

    private fun onLong(item: CryptoVault.Item) {
        val o = ArrayList<String>()
        if (!item.isDir) o.add("↗ Dusre app me kholo")
        o.add(if (item.isDir) "💾 Folder phone me nikalo (decrypt)" else "💾 Phone me nikalo (decrypt)")
        o.add("✏ Rename")
        o.add("🗑 Delete")
        AlertDialog.Builder(this).setTitle(item.name).setItems(o.toTypedArray()) { _, i ->
            when (o[i]) {
                "↗ Dusre app me kholo" -> openFile(item)
                "✏ Rename" -> renameItem(item)
                "🗑 Delete" -> deleteItem(item)
                else -> exportItem(item)
            }
        }.show()
    }

    private fun showMenu() {
        val v = vault
        if (v == null) { showChooser(); return }
        val o = arrayOf("📄 Files jodo (gallery / file picker se)", "📁 Naya folder", "🔒 Lock karo aur band karo")
        AlertDialog.Builder(this).setItems(o) { _, i ->
            when (i) {
                0 -> pickDocs.launch(arrayOf("*/*"))
                1 -> newFolder()
                2 -> finish()
            }
        }.show()
    }

    private fun askText(title: String, init: String, ok: (String) -> Unit) {
        val e = field("", false).apply { setText(init); setSelection(init.length) }
        AlertDialog.Builder(this).setTitle(title).setView(column(e))
            .setPositiveButton("OK") { _, _ ->
                val t = e.text.toString().trim()
                if (t.isNotEmpty() && !t.contains('/')) ok(t) else Toast.makeText(this, "Naam sahi likho", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun newFolder() {
        val v = vault ?: return
        askText("Naya folder", "") { n ->
            worker.execute {
                try { v.mkdir(curId(), n); runOnUiThread { refresh() } }
                catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() } }
            }
        }
    }

    private fun renameItem(it: CryptoVault.Item) {
        val v = vault ?: return
        askText("Rename", it.name) { n ->
            worker.execute {
                try { v.rename(curId(), it.name, n); runOnUiThread { refresh() } }
                catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() } }
            }
        }
    }

    private fun deleteItem(it: CryptoVault.Item) {
        val v = vault ?: return
        AlertDialog.Builder(this).setTitle("Delete?").setMessage("\"${it.name}\" vault se hamesha ke liye hat jayega.")
            .setPositiveButton("Delete") { _, _ ->
                worker.execute {
                    try { v.delete(curId(), it.name); runOnUiThread { refresh() } }
                    catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() } }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ───────────────────────── open / export ─────────────────────────

    private fun viewCache(): File = File(cacheDir, "vaultview")

    private fun wipeViewCache() {
        viewCache().listFiles()?.forEach { it.delete() }
    }

    private fun openFile(it: CryptoVault.Item) {
        val v = vault ?: return
        busy("Decrypt ho raha hai…")
        val id = curId()
        worker.execute {
            try {
                val dir = viewCache(); dir.mkdirs()
                val f = File(dir, it.name.replace('/', '_'))
                f.outputStream().use { o -> v.read(id, it.name, o) }
                runOnUiThread { unbusy(); Opener.viewFile(this, f, it.name) }
            } catch (e: Exception) {
                runOnUiThread { unbusy(); Toast.makeText(this, "Khul nahi paya: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun outDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VaultOut")

    private fun freeName(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    private fun exportItem(it: CryptoVault.Item) {
        val v = vault ?: return
        val id = curId()
        busy("Nikal raha hai…")
        worker.execute {
            try {
                val dest = outDir(); dest.mkdirs()
                exportRec(v, id, it, dest)
                runOnUiThread { unbusy(); Toast.makeText(this, "Decrypted: ${dest.absolutePath}\n(Ye files ab bina lock ke hain!)", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { unbusy(); Toast.makeText(this, "Nikal nahi paya: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun exportRec(v: CryptoVault, parentId: String, it: CryptoVault.Item, dest: File) {
        if (it.isDir) {
            val d = freeName(dest, it.name); d.mkdirs()
            for (c in v.list(it.dirId!!)) exportRec(v, it.dirId!!, c, d)
        } else {
            freeName(dest, it.name).outputStream().use { o -> v.read(parentId, it.name, o) }
        }
    }

    // ───────────────────────── add / import ─────────────────────────

    private fun uniqueName(v: CryptoVault, parentId: String, name: String): String {
        if (v.find(parentId, name) == null) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        while (v.find(parentId, "$base ($n)$ext") != null) n++
        return "$base ($n)$ext"
    }

    private fun displayName(u: Uri): String {
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it.replace('/', '_') }
            }
        } catch (e: Exception) { }
        return u.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun addFromUris(uris: List<Uri>) {
        val v = vault ?: return
        val id = curId()
        busy("Encrypt ho raha hai…")
        worker.execute {
            var ok = 0
            var err: String? = null
            for ((i, u) in uris.withIndex()) {
                val name = displayName(u)
                runOnUiThread { busyDlg?.setMessage("Encrypt (${i + 1}/${uris.size})\n$name") }
                try {
                    contentResolver.openInputStream(u)!!.use { ins -> v.write(id, uniqueName(v, id, name), ins) }
                    ok++
                } catch (e: Exception) { err = e.message }
            }
            runOnUiThread {
                unbusy(); refresh()
                Toast.makeText(this, "$ok file vault me gayi" + (if (err != null) "\nError: $err" else ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askImportMode() {
        val n = pendingImport.size
        AlertDialog.Builder(this)
            .setTitle("$n item vault me daalo")
            .setMessage("Move: encrypt hone aur check hone ke baad asli file delete ho jayegi.\nCopy: asli file jaisi hai waisi rahegi (wo khuli rahegi).")
            .setPositiveButton("Move (safe)") { _, _ -> runImport(true) }
            .setNeutralButton("Copy") { _, _ -> runImport(false) }
            .setNegativeButton("Cancel") { _, _ -> pendingImport = emptyList(); refresh() }
            .setOnCancelListener { pendingImport = emptyList(); refresh() }
            .show()
    }

    private fun runImport(move: Boolean) {
        val v = vault ?: return
        val id = curId()
        val list = pendingImport
        pendingImport = emptyList()
        busy("Encrypt ho raha hai…")
        worker.execute {
            val st = intArrayOf(0, 0)          // [ok, fail]
            val errs = StringBuilder()
            for (f in list) importRec(v, id, f, move, st, errs)
            runOnUiThread {
                unbusy(); refresh()
                AlertDialog.Builder(this)
                    .setTitle(if (st[1] == 0) "✅ ${st[0]} file vault me gayi" else "${st[0]} gayi, ${st[1]} fail")
                    .setMessage(if (st[1] == 0) (if (move) "Asli files hata di gayi hain." else "Asli files abhi bhi khuli padi hain.") else errs.toString().take(600))
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private class Counter : OutputStream() {
        var n = 0L
        override fun write(b: Int) { n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { n += len }
    }

    private fun importRec(v: CryptoVault, parentId: String, f: File, move: Boolean, st: IntArray, errs: StringBuilder) {
        try {
            if (f.isDirectory) {
                val ex = v.find(parentId, f.name)
                val did = if (ex != null && ex.isDir) ex.dirId!! else v.mkdir(parentId, uniqueName(v, parentId, f.name))
                f.listFiles()?.forEach { importRec(v, did, it, move, st, errs) }
                if (move) { val left = f.list(); if (left == null || left.isEmpty()) f.delete() }
            } else {
                val name = uniqueName(v, parentId, f.name)
                runOnUiThread { busyDlg?.setMessage("Encrypt ho raha hai\n$name") }
                FileInputStream(f).use { ins -> v.write(parentId, name, ins) }
                if (move) {
                    // asli file tabhi hatao jab vault wali copy poori decrypt hoke size match kare
                    val c = Counter()
                    v.read(parentId, name, c)
                    if (c.n != f.length()) throw java.io.IOException("verify fail: size alag")
                    if (!f.delete()) throw java.io.IOException("asli file hata nahi payi")
                }
                st[0]++
            }
        } catch (e: Exception) {
            st[1]++
            errs.append(f.name).append(": ").append(e.message).append('\n')
        }
    }

    // ───────────────────────── busy dialog ─────────────────────────

    private fun busy(msg: String) {
        busyDlg?.dismiss()
        busyDlg = AlertDialog.Builder(this).setMessage(msg).setCancelable(false).create()
        busyDlg?.show()
    }

    private fun unbusy() {
        busyDlg?.dismiss()
        busyDlg = null
    }

    companion object {
        /** File manager se vault kholne ke liye. `paths` khaali ho to sirf vault browse. */
        fun launch(act: Activity, dir: File, paths: List<File> = emptyList()) {
            val i = Intent(act, VaultActivity::class.java).putExtra("dir", dir.absolutePath)
            if (paths.isNotEmpty()) i.putExtra("import", paths.map { it.absolutePath }.toTypedArray())
            act.startActivity(i)
        }
    }
}
