package com.example.simplebrowser.deploy

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.example.simplebrowser.DlSettings
import com.example.simplebrowser.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto Deploy (GitHub): phone ke Download folder me `SimpleBrowser*.zip` (ya `Simple Browser(1).zip` jaisa naam) aate hi use GitHub repo me daal do.
 *
 * - Zip jaisi hai waisi hi repo me ek file ban kar jati hai (khol kar commit nahi hoti).
 * - Chrome se `SimpleBrowser(1).zip` jaise naam se aaye to bhi chalega.
 * - File poori download hone ke baad hi uthti hai (size 2 baar same + zip khulti ho).
 * - Upload safal hone ke baad hi purani `SimpleBrowser*.zip` delete hoti hain aur nayi ka naam
 *   repo wali file ke naam (default `SimpleBrowser.zip`) kar diya jata hai. Fail ho to kuch delete / rename nahi hota.
 * - Login GitHub token se (fine-grained token, sirf usi repo par "Contents: Read and write").
 */
object GitDeploy {
    const val K_ON = "gh_on"
    const val K_SRC = "gh_src"            // phone ka folder (default Download)
    const val K_PAT = "gh_pat"            // phone me file naam pattern
    const val K_LAST = "gh_last"          // last status text
    const val K_REPO = "gh_repo"          // owner/repo
    const val K_BRANCH = "gh_branch"
    const val K_PATH = "gh_path"          // repo ke andar file ka path
    const val K_TOKEN = "gh_token"
    private const val K_DONE = "gh_done"

    const val DEF_PAT = "Simple*Browser*.zip"      // SimpleBrowser.zip, SimpleBrowser(1).zip, Simple Browser(1).zip sab
    const val DEF_REPO = "krishna123814/Replay"
    const val DEF_BRANCH = "main"
    const val DEF_PATH = "SimpleBrowser.zip"
    private const val MAX_BYTES = 25L * 1024 * 1024     // API se bhejne ki hamari seema (memory me base64 banta hai)

    fun on(ctx: Context): Boolean = DlSettings.getBool(ctx, K_ON, false)
    fun setOn(ctx: Context, v: Boolean) = DlSettings.setBool(ctx, K_ON, v)

    fun srcDir(ctx: Context): String =
        DlSettings.getStr(ctx, K_SRC, "").ifBlank {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        }

    fun pattern(ctx: Context): String = DlSettings.getStr(ctx, K_PAT, DEF_PAT).ifBlank { DEF_PAT }
    private fun repo(ctx: Context) = DlSettings.getStr(ctx, K_REPO, DEF_REPO).trim().ifBlank { DEF_REPO }
    private fun branch(ctx: Context) = DlSettings.getStr(ctx, K_BRANCH, DEF_BRANCH).trim()
    private fun path(ctx: Context) = DlSettings.getStr(ctx, K_PATH, DEF_PATH).trim().trim('/').ifBlank { DEF_PATH }
    private fun token(ctx: Context) = DlSettings.getStr(ctx, K_TOKEN, "").trim()
    private fun finalName(ctx: Context) = path(ctx).substringAfterLast('/')

    fun last(ctx: Context): String = DlSettings.getStr(ctx, K_LAST, "")
    fun setLast(ctx: Context, msg: String) {
        val t = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date())
        DlSettings.setStr(ctx, K_LAST, "$t  $msg")
    }

    // ───────────────────────── helpers ─────────────────────────

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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun encPath(p: String): String = p.split('/').joinToString("/") { enc(it) }

    // ───────────────────────── GitHub API ─────────────────────────

    private class Resp(val code: Int, val body: String)

    private fun call(method: String, url: String, token: String, body: ByteArray?): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 120000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "SimpleBrowser-AutoDeploy")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val st = if (code >= 400) c.errorStream else c.inputStream
            val txt = st?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, txt)
        } finally {
            try { c.disconnect() } catch (_: Throwable) { }
        }
    }

    private fun why(r: Resp): String {
        val m = try { JSONObject(r.body).optString("message", "") } catch (_: Exception) { "" }
        val hint = when (r.code) {
            401 -> "token galat ya expire"
            403 -> "token ko permission nahi ya limit"
            404 -> "repo / branch / path nahi mila (ya token ko is repo ka access nahi)"
            409, 422 -> "file badal gayi, dobara koshish hogi"
            else -> ""
        }
        return "HTTP ${r.code}" + (if (hint.isNotEmpty()) " — $hint" else "") + (if (m.isNotEmpty()) " ($m)" else "")
    }

    private fun contentsUrl(repo: String, path: String) =
        "https://api.github.com/repos/$repo/contents/" + encPath(path)

    // ───────────────────────── scan + upload ─────────────────────────

    /**
     * Ek round: Download folder me jitni `SimpleBrowser*.zip` hain unme se sirf SABSE NAYI dekhi jati hai.
     * Stable + sahi zip ho to GitHub par jati hai; safal hone ke baad baaki purani delete aur ye `SimpleBrowser.zip` ban jati hai.
     * true = sab theek, false = upload fail (service 30s ruk kar dobara koshish karegi).
     */
    fun tick(ctx: Context, seen: HashMap<String, LongArray>): Boolean {
        if (!AutoDeploy.hasAllFiles()) {
            setLast(ctx, "❌ \"All files access\" permission do (Settings se)")
            return true
        }
        val dir = File(srcDir(ctx))
        val rx = globRegex(pattern(ctx))
        val list = dir.listFiles { f -> f.isFile && rx.matches(f.name) } ?: return true
        val f = list.maxByOrNull { it.lastModified() } ?: return true   // sabse nayi
        val fin = finalName(ctx)
        val sig = f.name + "|" + f.length() + "|" + f.lastModified()
        val done = doneSet(ctx)

        // Ye file pehle hi upload ho chuki hai: bas safai (purani hatao, naam theek karo) agar baaki ho.
        if (done.contains(sig)) {
            if (list.size > 1 || f.name != fin) tidy(ctx, f, list, fin)
            return true
        }

        val s = seen[f.name]
        if (s == null || s[0] != f.length() || s[1] != f.lastModified()) {
            seen[f.name] = longArrayOf(f.length(), f.lastModified(), 1L)
            return true
        }
        s[2] = s[2] + 1
        if (s[2] < 2L) return true                    // size 2 round (~3 sec) same ho tab hi
        val bad = zipProblem(f) ?: if (f.length() > MAX_BYTES) "file 25 MB se badi hai" else null
        if (bad != null) {
            if (s[2] == 8L) {                         // ~20 sec baad bhi kharab to bata do
                setLast(ctx, "⚠️ ${f.name}: $bad")
                result(ctx, "⚠️ ${f.name} GitHub par nahi gayi", bad)
            }
            return true
        }
        val ok = upload(ctx, f, sig, list, fin)
        if (ok) seen.clear()
        return ok
    }

    /** Upload ke baad: `keep` ke alawa baaki matching purani files delete, phir `keep` ka naam `fin` karo. */
    private fun tidy(ctx: Context, keep: File, all: Array<File>, fin: String): String {
        var removed = 0
        for (o in all) {
            if (o.absolutePath == keep.absolutePath) continue
            if (o.delete()) removed++
        }
        val parts = ArrayList<String>()
        if (removed > 0) parts.add("$removed purani hata di")
        if (keep.name != fin) {
            val target = File(keep.parentFile, fin)
            val len = keep.length()
            val mod = keep.lastModified()
            if (target.exists() && !target.delete()) {
                parts.add("naam badal nahi payi")
            } else if (keep.renameTo(target)) {
                markDone(ctx, fin + "|" + len + "|" + mod)
                parts.add("naam $fin kiya")
            } else {
                parts.add("naam badal nahi payi")
            }
        }
        return parts.joinToString(", ")
    }

    private fun upload(ctx: Context, f: File, sig: String, all: Array<File>, fin: String): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lion:gitdeploy")
        wl.acquire(10L * 60 * 1000)
        try {
            val tok = token(ctx)
            if (tok.isEmpty()) {
                setLast(ctx, "❌ GitHub token daalo (⚙ settings me)")
                return false
            }
            val repo = repo(ctx)
            if (!repo.contains('/')) {
                setLast(ctx, "❌ Repo daalo, jaise owner/repo (⚙ settings me)")
                return false
            }
            val path = path(ctx)
            val br = branch(ctx)
            setLast(ctx, "⬆ ${f.name} GitHub par ja rahi hai…")

            val base = contentsUrl(repo, path)
            val getUrl = if (br.isEmpty()) base else base + "?ref=" + enc(br)
            val g = call("GET", getUrl, tok, null)
            var sha: String? = null
            if (g.code == 200) {
                val t = g.body.trim()
                if (t.startsWith("[")) {
                    setLast(ctx, "❌ $path GitHub par folder hai, file nahi")
                    return false
                }
                val sh = JSONObject(t).optString("sha", "")
                if (sh.isNotEmpty()) sha = sh
            } else if (g.code != 404) {
                setLast(ctx, "❌ GitHub fail: " + why(g) + " (30 sec baad dobara)")
                return false
            }

            val stamp = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(Date())
            val j = JSONObject()
            j.put("message", "Update $fin ($stamp)")
            j.put("content", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            if (br.isNotEmpty()) j.put("branch", br)
            if (sha != null) j.put("sha", sha)
            val p = call("PUT", base, tok, j.toString().toByteArray(Charsets.UTF_8))
            if (p.code != 200 && p.code != 201) {
                setLast(ctx, "❌ Upload fail: " + why(p) + " (30 sec baad dobara)")
                return false
            }

            markDone(ctx, sig)
            val note = tidy(ctx, f, all, fin)
            val extra = if (note.isEmpty()) "" else " ($note)"
            val where = "$repo/$path" + (if (br.isEmpty()) "" else " [$br]")
            setLast(ctx, "✅ ${f.name} → $where$extra")
            result(ctx, "✅ GitHub par pahunch gayi", "${f.name} → $where")
            return true
        } catch (e: Exception) {
            val m = (e.message ?: e.javaClass.simpleName)
            setLast(ctx, "❌ Upload fail: $m (30 sec baad dobara)")
            return false
        } catch (e: OutOfMemoryError) {
            setLast(ctx, "❌ Memory kam padi, file badi hai (30 sec baad dobara)")
            return false
        } finally {
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
            nm.notify(80, n)
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

        fun field(v: String, pw: Boolean = false): EditText {
            val e = EditText(act)
            e.setSingleLine(true)
            e.textSize = 14f
            if (pw) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            e.setText(v)
            box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return e
        }

        fun cleanRepo(s: String): String {
            var r = s.trim()
            r = r.removePrefix("https://github.com/").removePrefix("http://github.com/").removePrefix("github.com/")
            r = r.removeSuffix("/").removeSuffix(".git")
            return r
        }

        label("Phone ka folder (yahan se file uthegi)")
        val eSrc = field(srcDir(act))
        label("Phone me file ka naam (* = kuch bhi)")
        val ePat = field(pattern(act))
        label("GitHub repo (owner/repo)")
        val eRepo = field(repo(act))
        label("Branch (khali = repo ka default)")
        val eBr = field(branch(act))
        label("Repo me file ka path (jaise SimpleBrowser.zip ya backup/SimpleBrowser.zip)")
        val ePath = field(path(act))
        label("GitHub token (fine-grained, Contents: Read and write)")
        val eTok = field(token(act), pw = true)

        if (!AutoDeploy.hasAllFiles()) {
            val b = Button(act)
            b.text = "📂 All files access do"
            b.setOnClickListener { AutoDeploy.askAllFiles(act) }
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
            val r = cleanRepo(eRepo.text.toString())
            val b = eBr.text.toString().trim()
            val tk = eTok.text.toString().trim()
            st.text = "Test chal raha hai…"
            Thread {
                var msg = ""
                try {
                    if (tk.isEmpty()) {
                        msg = "❌ Token daalo"
                    } else if (!r.contains('/')) {
                        msg = "❌ Repo owner/repo format me daalo"
                    } else {
                        val rr = call("GET", "https://api.github.com/repos/$r", tk, null)
                        if (rr.code != 200) {
                            msg = "❌ " + why(rr)
                        } else {
                            val o = JSONObject(rr.body)
                            val perm = o.optJSONObject("permissions")
                            val push = if (perm == null) "write access upload par pata chalega" else if (perm.optBoolean("push", false)) "write access ✔" else "write access NAHI ✘"
                            msg = "✅ Repo mila: ${o.optString("full_name", r)} ($push)"
                            if (b.isNotEmpty()) {
                                val br = call("GET", "https://api.github.com/repos/$r/branches/" + enc(b), tk, null)
                                msg += if (br.code == 200) "\n✅ Branch $b mili" else "\n❌ Branch $b: " + why(br)
                            }
                        }
                    }
                } catch (e: Exception) {
                    msg = "❌ " + (e.message ?: e.javaClass.simpleName)
                }
                Handler(Looper.getMainLooper()).post { st.text = msg }
            }.start()
        }
        box.addView(test)
        box.addView(st)

        val sv = ScrollView(act)
        sv.addView(box)

        AlertDialog.Builder(act)
            .setTitle("🐙 Auto Deploy (GitHub)")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                DlSettings.setStr(act, K_SRC, eSrc.text.toString().trim())
                DlSettings.setStr(act, K_PAT, ePat.text.toString().trim().ifBlank { DEF_PAT })
                DlSettings.setStr(act, K_REPO, cleanRepo(eRepo.text.toString()).ifBlank { DEF_REPO })
                DlSettings.setStr(act, K_BRANCH, eBr.text.toString().trim())
                DlSettings.setStr(act, K_PATH, ePath.text.toString().trim().trim('/').ifBlank { DEF_PATH })
                DlSettings.setStr(act, K_TOKEN, eTok.text.toString().trim())
                Toast.makeText(act, "Save ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Band", null)
            .setOnDismissListener { onClose() }
            .show()
    }
}
