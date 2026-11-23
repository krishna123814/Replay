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
import android.widget.CheckBox
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
 */
object AutoDeploy {
    const val K_ON = "dep_on"
    const val K_SRC = "dep_src"          // phone ka folder (default Download)
    const val K_PAT = "dep_pat"          // phone me file naam pattern, jaise server*.zip
    const val K_DEL = "dep_del"          // upload ke baad phone se file hatao
    const val K_LAST = "dep_last"        // last status text
    const val K_HOST = "dep_host"
    const val K_PORT = "dep_port"
    const val K_USER = "dep_user"
    const val K_PASS = "dep_pass"
    const val K_DIR = "dep_dir"          // server par folder
    const val K_NAME = "dep_name"        // server par file ka naam
    private const val K_DONE = "dep_done"

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
    fun delOn(ctx: Context): Boolean = DlSettings.getBool(ctx, K_DEL, true)
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
        if (on(ctx)) start(ctx)
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
     * Ek round: Download folder dekho, jo nayi stable zip mile use upload karo.
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
        val done = doneSet(ctx)
        var allOk = true
        for (f in list.sortedBy { it.lastModified() }) {
            val sig = f.name + "|" + f.length() + "|" + f.lastModified()
            if (done.contains(sig)) continue
            val s = seen[f.name]
            if (s == null || s[0] != f.length() || s[1] != f.lastModified()) {
                seen[f.name] = longArrayOf(f.length(), f.lastModified(), 1L)
                continue
            }
            s[2] = s[2] + 1
            if (s[2] < 2L) continue                       // size 2 round (~3 sec) same ho tab hi
            val bad = zipProblem(f)
            if (bad != null) {
                if (s[2] == 8L) {                         // ~20 sec baad bhi kharab to bata do
                    setLast(ctx, "⚠️ ${f.name}: $bad")
                    result(ctx, "⚠️ ${f.name} upload nahi hui", bad)
                }
                continue
            }
            if (upload(ctx, f, sig)) seen.remove(f.name) else allOk = false
        }
        return allOk
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

    private fun upload(ctx: Context, f: File, sig: String): Boolean {
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
            val remote = name(ctx)
            setLast(ctx, "⬆ ${f.name} upload ho rahi hai…")
            c = connect(host(ctx), port(ctx), user(ctx), pass(ctx))
            val ch = c.sftp
            ch.cd(remoteDir)
            val part = "$remote.part"
            ch.put(f.absolutePath, part, ChannelSftp.OVERWRITE)
            val exists = try { ch.stat(remote); true } catch (e: SftpException) { false }
            if (exists) ch.rm(remote)
            ch.rename(part, remote)
            markDone(ctx, sig)
            var extra = ""
            if (delOn(ctx)) {
                extra = if (f.delete()) " (phone se hata di)" else " (phone se hata nahi payi)"
            }
            setLast(ctx, "✅ ${f.name} → ${remoteDir.trimEnd('/')}/$remote$extra")
            result(ctx, "✅ Server par pahunch gayi", "${f.name} → $remoteDir/$remote")
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
        val cDel = CheckBox(act)
        cDel.text = "Upload ke baad phone se file hata do"
        cDel.isChecked = delOn(act)
        box.addView(cDel)

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
                DlSettings.setBool(act, K_DEL, cDel.isChecked)
                Toast.makeText(act, "Save ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Band", null)
            .setOnDismissListener { onClose() }
            .show()
    }
}
