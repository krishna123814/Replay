package com.example.simplebrowser.files

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Server par chal rahi / hui copy-move jobs ki list (`/xfer/list`), har ~2 s par refresh. */
class TransfersActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var adapter: JobAdapter
    private lateinit var emptyTv: TextView
    private lateinit var subTv: TextView

    private var jobs: List<XferJob> = emptyList()
    private var polling = false
    private var pollGen = 0

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.hasCreds()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized) return
        polling = true
        pollGen++
        poll(pollGen)
    }

    override fun onPause() {
        super.onPause()
        polling = false
        pollGen++
        ui.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        ui.removeCallbacksAndMessages(null)
        io.shutdown()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(0xFFF5F5F5.toInt())
        header.setPadding(dp(4), 0, dp(4), 0)

        val back = TextView(this)
        back.text = "←"
        back.textSize = 22f
        back.setTextColor(Color.BLACK)
        back.gravity = Gravity.CENTER
        back.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        back.setOnClickListener { finish() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        col.setPadding(dp(4), dp(8), dp(4), dp(8))
        val title = TextView(this)
        title.text = "Transfers"
        title.setTextColor(Color.BLACK)
        title.textSize = 17f
        title.typeface = Typeface.DEFAULT_BOLD
        subTv = TextView(this)
        subTv.setTextColor(Color.BLACK)
        subTv.textSize = 12f
        subTv.text = "Server wale transfer server par chalte hain, phone wale app me"
        col.addView(title)
        col.addView(subTv)

        header.addView(back)
        header.addView(col)

        adapter = JobAdapter()
        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.setPadding(0, dp(6), 0, dp(6))
        rv.clipToPadding = false

        emptyTv = TextView(this)
        emptyTv.setTextColor(Color.BLACK)
        emptyTv.gravity = Gravity.CENTER
        emptyTv.setPadding(dp(24), dp(24), dp(24), dp(24))
        emptyTv.text = "Koi transfer nahi.\n(Server restart par history chali jaati hai)"

        val body = FrameLayout(this)
        body.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        body.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(emptyTv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(body)
        setContentView(root)
    }

    private fun poll(gen: Int) {
        if (!polling || gen != pollGen || isFinishing || isDestroyed) return
        io.execute {
            var list: List<XferJob>? = null
            var err: String? = null
            var auth = false
            try {
                list = Xfer.list()
            } catch (e: DavAuthException) {
                auth = true
            } catch (e: Exception) {
                err = e.message ?: "error"
            }
            // phone wali jobs (app ke andar) server list ke saath mila do
            val mine = LocalJobs.list()
            val merged: List<XferJob>? =
                if (list != null) (list + mine).sortedByDescending { it.created }
                else if (mine.isNotEmpty()) mine
                else null
            val got: List<XferJob>? = merged
            val bad: String? = err
            val authFail: Boolean = auth
            runOnUiThread {
                if (!polling || gen != pollGen || isFinishing || isDestroyed) return@runOnUiThread
                if (authFail) {
                    Toast.makeText(this, "Login galat / expire — dobara login karo", Toast.LENGTH_LONG).show()
                    Session.clearPass(this)
                    startActivity(
                        Intent(this, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                    return@runOnUiThread
                }
                if (got != null) {
                    jobs = got
                    adapter.notifyDataSetChanged()
                    val run = got.count { it.active }
                    subTv.text = if (run > 0) "$run chal rahi / queue me" else "Server wale transfer server par chalte hain, phone wale app me"
                    emptyTv.visibility = if (got.isEmpty()) View.VISIBLE else View.GONE
                    if (got.isEmpty()) emptyTv.text = "Koi transfer nahi.\n(Server restart par history chali jaati hai)"
                    ui.postDelayed({ poll(gen) }, 2000)
                } else {
                    if (jobs.isEmpty()) {
                        emptyTv.visibility = View.VISIBLE
                        emptyTv.text = "Transfers nahi mile\n${bad ?: ""}"
                    }
                    ui.postDelayed({ poll(gen) }, 4000)
                }
            }
        }
    }

    private fun cancelJob(j: XferJob) {
        io.execute {
            try {
                Xfers.cancel(j.id)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "❌ ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun details(j: XferJob) {
        val sb = StringBuilder()
        sb.append(j.stateLabel()).append("\n").append(j.line())
        if (j.created > 0) sb.append("\nShuru: ").append(fmtDate(j.created * 1000))
        if (j.finished > 0) sb.append("\nKhatam: ").append(fmtDate(j.finished * 1000))
        if (j.current.isNotEmpty()) sb.append("\nAbhi: ").append(j.current)
        if (j.errors.isNotEmpty()) {
            sb.append("\n\nErrors:\n")
            sb.append(j.errors.take(15).joinToString("\n") { "• $it" })
        }
        AlertDialog.Builder(this)
            .setTitle(j.title())
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    inner class VH(
        root: View,
        val title: TextView,
        val state: TextView,
        val bar: ProgressBar,
        val line: TextView,
        val cancel: TextView
    ) : RecyclerView.ViewHolder(root)

    inner class JobAdapter : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int = jobs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(this@TransfersActivity)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            val bg = GradientDrawable()
            bg.setColor(0xFFF0F0F0.toInt())
            bg.cornerRadius = dp(12).toFloat()
            card.background = bg
            val lp = RecyclerView.LayoutParams(MATCH, WRAP)
            lp.setMargins(dp(12), dp(5), dp(12), dp(5))
            card.layoutParams = lp

            val row = LinearLayout(this@TransfersActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val title = TextView(this@TransfersActivity)
            title.setTextColor(Color.BLACK)
            title.textSize = 15f
            title.typeface = Typeface.DEFAULT_BOLD
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            title.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            val cancel = TextView(this@TransfersActivity)
            cancel.text = "✕ Cancel"
            cancel.setTextColor(0xFFE65100.toInt())
            cancel.textSize = 13f
            cancel.setPadding(dp(10), dp(6), dp(4), dp(6))
            row.addView(title)
            row.addView(cancel)

            val state = TextView(this@TransfersActivity)
            state.setTextColor(Color.BLACK)
            state.textSize = 12f

            val bar = ProgressBar(this@TransfersActivity, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = 100
            bar.progressTintList = ColorStateList.valueOf(0xFFEF6C00.toInt())
            val blp = LinearLayout.LayoutParams(MATCH, WRAP)
            blp.topMargin = dp(6)

            val line = TextView(this@TransfersActivity)
            line.setTextColor(Color.BLACK)
            line.textSize = 12f
            line.maxLines = 2
            line.ellipsize = TextUtils.TruncateAt.END

            card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            card.addView(state)
            card.addView(bar, blp)
            card.addView(line)
            return VH(card, title, state, bar, line, cancel)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val j = jobs[position]
            h.title.text = j.title()
            val pct = j.pct
            h.state.text = j.stateLabel() + (if (pct >= 0) " · $pct%" else "")
            if (pct < 0 && j.active) {
                h.bar.isIndeterminate = true
            } else {
                h.bar.isIndeterminate = false
                h.bar.progress = if (pct < 0) 0 else pct
            }
            var l = j.line()
            if (j.active && j.current.isNotEmpty()) l += "\n" + j.current
            if (j.errors.isNotEmpty()) l += "\n⚠ ${j.errors.size} error (dabao)"
            h.line.text = l
            h.cancel.visibility = if (j.active) View.VISIBLE else View.GONE
            h.cancel.setOnClickListener { cancelJob(j) }
            h.itemView.setOnClickListener { details(j) }
        }
    }
}
