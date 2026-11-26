package com.example.simplebrowser.deploy

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.simplebrowser.DlSettings
import com.example.simplebrowser.R
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SftpException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto Deploy: phone ke Download folder me `server*.zip` aate hi use SFTP se server ke folder (default /root/app) me daal do.
 *
 * - Chrome se `server(1).zip` jaise naam se aaye to bhi chalega (naam pattern settings me badal sakte ho).
 * - File poori download hone ke baad hi uthti hai (size 2 baar same + zip khulti ho).
 * - Server par pehle `<naam>.part` ban kar jati hai, poori hone par purani file hata kar `<naam>` me rename hoti hai,
 *   taaki server ka watcher aadhi file na utha le.
 * - Login SFTP ka alag hai (host / user / password) — WebDAV ya File Manager login ki zaroorat nahi.
 * - VERSION NAAM: zip ke andar kuch nahi badalta. App khud naam deta hai: server1.zip, server2.zip, server3.zip...
 *   (AI ne zip ka naam kuch bhi diya ho, farak nahi). Same content dobara aaye to wahi purana number milta hai.
 * - Download folder me sabse nayi `server<N>.zip` rehti hai (yahin se AI ko do). Purani saari versions
 *   `Download/ServerVersions/` me bachi rehti hain — kuch delete nahi hota (sirf bilkul same content ki duplicate hatti hai).
 * - Server par bhi `server<N>.zip` naam se jati hai. Server ka watcher sirf wahi zip leta hai jiska N
 *   `/root/last_version` se bada ho, aur li hui zip server ke `versions/` folder me bachi rehti hai.
 *   Upload fail ho to phone par kuch delete / rename nahi hota.
 */
object AutoDeploy {
    const val K_ON = "dep_on"
    const val K_SRC = "dep_src"          // phone ka folder (default Download)
    const val K_PAT = "dep_pat"          // phone me file naam pattern, jaise server*.zip
    const val K_LAST = "dep_last"        // last status text
    const val K_HOST = "dep_host"
    const val K_PORT = "dep_port"
    const val K_USER = "dep_user"
    const val K_PASS = "dep_pass"
    const val K_DIR = "dep_dir"          // server par folder
    const val K_NAME = "dep_name"        // server par file ka naam
    private const val K_DONE = "dep_done"
    private const val K_VER = "dep_ver"          // version list: "N|sha256" har line
    const val VER_DIR = "ServerVersions"

    const val DEF_PAT = "server*.zip"
    const val DEF_HOST = "147.135.130.116"
    const val DEF_DIR = "/root/app"
    const val DEF_NAME = "server.zip"

    fun on(ctx: Context): Boolean = DlSettings.getBool(ctx, K_ON, false)
    fun setOn(ctx: Context, v: Boolean) = DlSettings.setBool(ctx, K_ON, v)

    fun srcDir(ctx: Context): String =
        DlSettings.getStr(ctx, K_SRC, "").ifBlank {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        }

    fun pattern(ctx: Context): String = DlSettings.getStr(ctx, K_PAT, DEF_PAT).ifBlank { DEF_PAT }
    private fun host(ctx: Context) = DlSettings.getStr(ctx, K_HOST, DEF_HOST).trim().ifBlank { DEF_HOST }
    private fun port(ctx: Context) = DlSettings.getStr(ctx, K_PORT, "22").trim().toIntOrNull() ?: 22
    private fun user(ctx: Context) = DlSettings.getStr(ctx, K_USER, "root").trim().ifBlank { "root" }
    private fun pass(ctx: Context) = DlSettings.getStr(ctx, K_PASS, "")
    private fun dir(ctx: Context) = DlSettings.getStr(ctx, K_DIR, DEF_DIR).trim().ifBlank { DEF_DIR }
    private fun name(ctx: Context) = DlSettings.getStr(ctx, K_NAME, DEF_NAME).trim().ifBlank { DEF_NAME }

    fun last(ctx: Context): String = DlSettings.getStr(ctx, K_LAST, "")
    fun setLast(ctx: Context, msg: String) {
        val t = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date())
        DlSettings.setStr(ctx, K_LAST, "$t  $msg")
    }

    fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    fun askAllFiles(act: Activity) {
        try {
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${act.packageName}"))
            )
        } catch (e: Exception) {
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
            }
        }
    }

    fun start(ctx: Context) {
        DeployWatchdog.schedule(ctx)   // service mar jaye to bhi ~15 min me kaam hota rahe
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AutoDeployService::class.java))
        } catch (e: Throwable) {
            setLast(ctx, "Service start nahi hui: ${e.message}")
        }
    }

    fun stop(ctx: Context) {
        try {
            ctx.stopService(Intent(ctx, AutoDeployService::class.java))
        } catch (_: Throwable) {
        }
    }

    /** Boot / app update ke baad: ON saved ho to service wapas chalao. */
    fun startIfOn(ctx: Context) {
        if (on(ctx) || GitDeploy.on(ctx) || GitDeploy.watching(ctx)) start(ctx)
    }

    /** App process bani (kisi bhi wajah se) -> sirf watchdog lagao; foreground service background se nahi chalate. */
    fun scheduleWatchdogIfOn(ctx: Context) {
        if (on(ctx) || GitDeploy.on(ctx) || GitDeploy.watching(ctx)) DeployWatchdog.schedule(ctx)
    }

    // ───────────────────────── scan + upload ─────────────────────────

    private fun globRegex(p: String): Regex {
        val sb = StringBuilder()
        for (ch in p.trim()) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    private fun doneSet(ctx: Context): MutableList<String> =
        DlSettings.getStr(ctx, K_DONE, "").split('\n').filter { it.isNotEmpty() }.toMutableList()

    private fun markDone(ctx: Context, sig: String) {
        val l = doneSet(ctx)
        l.add(sig)
        while (l.size > 50) l.removeAt(0)
        DlSettings.setStr(ctx, K_DONE, l.joinToString("\n"))
    }

    private fun zipProblem(f: File): String? = try {
        java.util.zip.ZipFile(f).use { if (it.size() == 0) "zip khaali hai" else null }
    } catch (e: Exception) {
        "zip poori nahi / kharab hai"
    }

    /**
     * Ek round: Download folder me jitni `server*.zip` hain unme se sirf SABSE NAYI dekhi jati hai.
     * Wo stable + sahi zip ho to upload hoti hai; safal hone ke baad baaki purani delete aur ye `server.zip` ban jati hai.
     * `seen` = file ka naam -> [size, modified, kitni baar same dikhi]. true = sab theek, false = upload fail (service 30s ruk kar dobara koshish karegi).
     */
    fun tick(ctx: Context, seen: HashMap<String, LongArray>): Boolean {
        if (!hasAllFiles()) {
            setLast(ctx, "❌ \"All files access\" permission do (Settings se)")
            return true
        }
        val dir = File(srcDir(ctx))
        val rx = globRegex(pattern(ctx))
        val list = dir.listFiles { f -> f.isFile && rx.matches(f.name) } ?: return true
        val f = list.maxByOrNull { it.lastModified() } ?: return true   // sabse nayi
        val finalName = name(ctx)
        val sig = f.name + "|" + f.length() + "|" + f.lastModified()
        val done = doneSet(ctx)

        // Ye file pehle hi upload ho chuki hai: bas safai (purani hatao, naam theek karo) agar baaki ho.
        if (done.contains(sig)) {
            val verRx = Regex("^" + Regex.escape(stem(finalName)) + "\\d+\\." + Regex.escape(ext(finalName)) + "$", RegexOption.IGNORE_CASE)
            if (list.size > 1 || !verRx.matches(f.name)) tidy(ctx, f, list, finalName)
            return true
        }

        val s = seen[f.name]
        if (s == null || s[0] != f.length() || s[1] != f.lastModified()) {
            seen[f.name] = longArrayOf(f.length(), f.lastModified(), 1L)
            return true
        }
        s[2] = s[2] + 1
        if (s[2] < 2L) return true                    // size 2 round (~3 sec) same ho tab hi
        val bad = zipProblem(f)
        if (bad != null) {
            if (s[2] == 8L) {                         // ~20 sec baad bhi kharab to bata do
                setLast(ctx, "⚠️ ${f.name}: $bad")
                result(ctx, "⚠️ ${f.name} upload nahi hui", bad)
            }
            return true
        }
        val ok = upload(ctx, f, sig, list, finalName)
        if (ok) seen.clear()
        return ok
    }

    // ───────────────────────── version naam ─────────────────────────

    private fun stem(finalName: String): String = finalName.substringBeforeLast('.', finalName)
    private fun ext(finalName: String): String = finalName.substringAfterLast('.', "zip")

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verList(ctx: Context): MutableList<Pair<Int, String>> =
        DlSettings.getStr(ctx, K_VER, "").split('\n').mapNotNull {
            val p = it.split('|')
            val n = p.getOrNull(0)?.toIntOrNull()
            val h = p.getOrNull(1)
            if (n != null && !h.isNullOrEmpty()) Pair(n, h) else null
        }.toMutableList()

    /** Download ya ServerVersions me pehle se bani sabse badi version number (settings reset ho gayi ho tab bhi). */
    private fun maxVersionOnDisk(ctx: Context, finalName: String): Int {
        val rx = Regex("^" + Regex.escape(stem(finalName)) + "(\\d+)\\." + Regex.escape(ext(finalName)) + "$", RegexOption.IGNORE_CASE)
        val src = File(srcDir(ctx))
        var m = 0
        for (d in listOf(src, File(src, VER_DIR))) {
            d.listFiles()?.forEach { f -> rx.find(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > m) m = it } }
        }
        return m
    }

    /** Is file ka version number: same content pehle aaya ho to wahi number, nahi to agla naya number. */
    @Synchronized
    private fun versionOf(ctx: Context, f: File, finalName: String): Int {
        val h = sha256(f)
        val l = verList(ctx)
        l.firstOrNull { it.second == h }?.let { return it.first }
        val n = maxOf(l.maxOfOrNull { it.first } ?: 0, maxVersionOnDisk(ctx, finalName)) + 1
        l.add(Pair(n, h))
        DlSettings.setStr(ctx, K_VER, l.joinToString("\n") { it.first.toString() + "|" + it.second })
        return n
    }

    private fun versionName(ctx: Context, f: File, finalName: String): String =
        stem(finalName) + versionOf(ctx, f, finalName) + "." + ext(finalName)

    /**
     * Upload ke baad: nayi `keep` ka naam `server<N>.zip` kar do (Download me yahi latest rehti hai),
     * uski ek copy `ServerVersions/` me rakho, aur baaki purani matching files `ServerVersions/` me bhej do.
     * Kuch delete nahi hota — sirf wo file jiska bilkul same content pehle se ServerVersions me ho.
     * Naam badalne ke baad (naam|size|time) badal jata hai, isliye naya signature bhi "done" me daalte hain.
     * Return: chhota text jo status me dikhta hai.
     */
    private fun tidy(ctx: Context, keep: File, all: Array<File>, finalName: String): String {
        val vdir = File(keep.parentFile, VER_DIR)
        vdir.mkdirs()
        val parts = ArrayList<String>()
        var moved = 0
        // 1) baaki purani files -> ServerVersions
        for (o in all) {
            if (o.absolutePath == keep.absolutePath) continue
            try {
                val dest = File(vdir, versionName(ctx, o, finalName))
                if (dest.exists()) {
                    o.delete()                       // same content pehle se bachi hai
                } else if (o.renameTo(dest) || (runCatching { o.copyTo(dest, false); o.delete() }.isSuccess)) {
                    moved++
                }
            } catch (_: Throwable) {
            }
        }
        if (moved > 0) parts.add("$moved purani ServerVersions me")
        // 2) nayi ka version naam + ServerVersions me copy
        val vname = versionName(ctx, keep, finalName)
        try {
            val cp = File(vdir, vname)
            if (!cp.exists()) keep.copyTo(cp, false)
        } catch (_: Throwable) {
        }
        if (keep.name != vname) {
            val target = File(keep.parentFile, vname)
            val len = keep.length()
            val mod = keep.lastModified()
            if (target.exists() && !target.delete()) {
                parts.add("naam badal nahi payi")
            } else if (keep.renameTo(target)) {
                markDone(ctx, vname + "|" + len + "|" + mod)
                parts.add("naam $vname kiya")
            } else {
                parts.add("naam badal nahi payi")
            }
        }
        return parts.joinToString(", ")
    }

    /** SFTP connect (password se). Host key check band (apna hi server hai). Caller ko disconnect karna hai. */
    private class Conn(val session: com.jcraft.jsch.Session, val sftp: ChannelSftp) {
        fun close() {
            try { sftp.disconnect() } catch (_: Throwable) { }
            try { session.disconnect() } catch (_: Throwable) { }
        }
    }

    private fun connect(h: String, p: Int, u: String, pw: String): Conn {
        val jsch = JSch()
        val s = jsch.getSession(u, h, p)
        s.setPassword(pw)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.connect(15000)
        try {
            val ch = s.openChannel("sftp") as ChannelSftp
            ch.connect(15000)
            return Conn(s, ch)
        } catch (e: Exception) {
            try { s.disconnect() } catch (_: Throwable) { }
            throw e
        }
    }

    private fun upload(ctx: Context, f: File, sig: String, all: Array<File>, finalName: String): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lion:deploy")
        wl.acquire(10L * 60 * 1000)
        var c: Conn? = null
        try {
            if (pass(ctx).isEmpty()) {
                setLast(ctx, "❌ SFTP password daalo (⚙ settings me)")
                return false
            }
            val remoteDir = dir(ctx)
            // Server par bhi version naam se jati hai (server<N>.zip): watcher sirf last se bada number leta hai.
            val vname = versionName(ctx, f, finalName)
            val remote = vname
            setLast(ctx, "⬆ ${f.name} → $vname upload ho rahi hai…")
            c = connect(host(ctx), port(ctx), user(ctx), pass(ctx))
            val ch = c.sftp
            ch.cd(remoteDir)
            val part = "$remote.part"
            ch.put(f.absolutePath, part, ChannelSftp.OVERWRITE)
            val exists = try { ch.stat(remote); true } catch (e: SftpException) { false }
            if (exists) ch.rm(remote)
            ch.rename(part, remote)
            markDone(ctx, sig)
            val note = tidy(ctx, f, all, finalName)
            val extra = if (note.isEmpty()) "" else " ($note)"
            setLast(ctx, "✅ $vname (${f.name}) → ${remoteDir.trimEnd('/')}/$remote$extra")
            result(ctx, "✅ $vname server par pahunch gayi", "${f.name} → $remoteDir/$remote  (version: $vname)")
            return true
        } catch (e: Exception) {
            val m = (e.message ?: e.javaClass.simpleName)
            setLast(ctx, "❌ Upload fail: $m (30 sec baad dobara)")
            return false
        } finally {
            c?.close()
            try {
                if (wl.isHeld) wl.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun result(ctx: Context, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel("deploy_res", "Auto Deploy result", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val n = NotificationCompat.Builder(ctx, "deploy_res")
                .setSmallIcon(R.drawable.ic_notif_lion)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            nm.notify(78, n)
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────── settings dialog ─────────────────────────

    fun showSettings(act: Activity, onClose: () -> Unit) {
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)

        fun label(t: String) {
            val tv = TextView(act)
            tv.text = t
            tv.textSize = 12f
            tv.setPadding(0, pad / 2, 0, 0)
            box.addView(tv)
        }

        fun field(v: String, pw: Boolean = false, num: Boolean = false): EditText {
            val e = EditText(act)
            e.setSingleLine(true)
            e.textSize = 14f
            if (pw) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (num) e.inputType = InputType.TYPE_CLASS_NUMBER
            e.setText(v)
            box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return e
        }

        label("Phone ka folder (yahan se file uthegi)")
        val eSrc = field(srcDir(act))
        label("Phone me file ka naam (* = kuch bhi)")
        val ePat = field(pattern(act))
        label("Server (SFTP) — host")
        val eHost = field(host(act))
        label("Port")
        val ePort = field(port(act).toString(), num = true)
        label("User")
        val eUser = field(user(act))
        label("Password")
        val ePass = field(pass(act), pw = true)
        label("Server par folder")
        val eDir = field(dir(act))
        label("Server par file ka naam (purani same naam wali replace hogi)")
        val eName = field(name(act))
        if (!hasAllFiles()) {
            val b = Button(act)
            b.text = "📂 All files access do"
            b.setOnClickListener { askAllFiles(act) }
            box.addView(b)
        }

        val st = TextView(act)
        st.textSize = 11f
        st.setPadding(0, pad / 2, 0, 0)
        st.setTextIsSelectable(true)
        st.text = last(act).ifBlank { "Abhi koi file nahi uthi" }

        val test = Button(act)
        test.text = "🔌 Connection test"
        test.setOnClickListener {
            val h = eHost.text.toString().trim()
            val p = ePort.text.toString().trim().toIntOrNull() ?: 22
            val u = eUser.text.toString().trim()
            val pw = ePass.text.toString()
            val d = eDir.text.toString().trim()
            st.text = "Test chal raha hai…"
            Thread {
                var msg = ""
                var c: Conn? = null
                try {
                    c = connect(h, p, u, pw)
                    c.sftp.cd(d)
                    msg = "✅ Connect ho gaya, folder $d mil gaya"
                } catch (e: Exception) {
                    msg = "❌ " + (e.message ?: e.javaClass.simpleName)
                } finally {
                    c?.close()
                }
                Handler(Looper.getMainLooper()).post { st.text = msg }
            }.start()
        }
        box.addView(test)
        box.addView(st)

        val sv = ScrollView(act)
        sv.addView(box)

        AlertDialog.Builder(act)
            .setTitle("📦 Auto Deploy")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                DlSettings.setStr(act, K_SRC, eSrc.text.toString().trim())
                DlSettings.setStr(act, K_PAT, ePat.text.toString().trim().ifBlank { DEF_PAT })
                DlSettings.setStr(act, K_HOST, eHost.text.toString().trim().ifBlank { DEF_HOST })
                DlSettings.setStr(act, K_PORT, ePort.text.toString().trim().ifBlank { "22" })
                DlSettings.setStr(act, K_USER, eUser.text.toString().trim().ifBlank { "root" })
                DlSettings.setStr(act, K_PASS, ePass.text.toString())
                var d = eDir.text.toString().trim().ifBlank { DEF_DIR }
                if (!d.startsWith("/")) d = "/$d"
                DlSettings.setStr(act, K_DIR, d)
                DlSettings.setStr(act, K_NAME, eName.text.toString().trim().ifBlank { DEF_NAME })
                Toast.makeText(act, "Save ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Band", null)
            .setOnDismissListener { onClose() }
            .show()
    }
}
