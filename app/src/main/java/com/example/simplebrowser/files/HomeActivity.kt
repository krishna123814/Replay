package com.example.simplebrowser.files

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Login ke baad ka pehla screen: do storage (AIC Disk, HF Bucket) + Transfers. */
class HomeActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var aicSub: TextView
    private lateinit var hfSub: TextView
    private lateinit var hfCard: LinearLayout
    private lateinit var clipTv: TextView
    private lateinit var localSub: TextView

    private val storagePerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            refreshLocal()
            if (ok) open(Store.LOCAL)
        }

    /** null = HF theek (ya abhi pata nahi); String = HF band hai, ye sabab. */
    private var hfReason: String? = null

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)

        if (!Session.hasCreds()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (!::hfSub.isInitialized) return
        refreshClip()
        refreshLocal()
        probeHf()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun buildUi() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = if (intent.getBooleanExtra("pick", false)) "📎 File chuno" else "📁 File Manager"
        title.textSize = 26f
        title.setTextColor(Color.BLACK)
        title.typeface = Typeface.DEFAULT_BOLD
        title.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val more = TextView(this)
        more.text = "⋮"
        more.textSize = 24f
        more.setTextColor(Color.BLACK)
        more.gravity = Gravity.CENTER
        more.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        more.setOnClickListener { showMenu() }
        top.addView(title)
        top.addView(more)
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val host = Session.baseUrl.toHttpUrlOrNull()?.host ?: ""

        aicSub = TextView(this)
        val aicCard = card(Store.AIC, aicSub) { open(Store.AIC) }
        aicSub.text = "VPS ki disk (/data) · $host"

        hfSub = TextView(this)
        hfCard = card(Store.HF, hfSub) {
            val r = hfReason
            if (r != null) {
                Toast.makeText(this, "❌ $r", Toast.LENGTH_LONG).show()
            } else {
                open(Store.HF)
            }
        }
        hfSub.text = "Check ho raha hai…"

        localSub = TextView(this)
        val localCard = card(Store.LOCAL, localSub) { openLocal() }

        col.addView(aicCard)
        col.addView(hfCard)
        col.addView(localCard)

        clipTv = TextView(this)
        clipTv.setTextColor(0xFFE65100.toInt())
        clipTv.textSize = 13f
        clipTv.setPadding(dp(4), dp(8), dp(4), dp(8))
        col.addView(clipTv, LinearLayout.LayoutParams(MATCH, WRAP))

        val tr = Button(this)
        tr.text = "📊 Transfers"
        tr.setOnClickListener { startActivity(Intent(this, TransfersActivity::class.java)) }
        col.addView(tr, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.WHITE)
        scroll.isFillViewport = true
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun card(store: Store, sub: TextView, onClick: () -> Unit): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(20), dp(24), dp(20), dp(24))
        val bg = GradientDrawable()
        bg.setColor(0xFFF0F0F0.toInt())
        bg.cornerRadius = dp(16).toFloat()
        c.background = bg
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.layoutParams = lp

        val icon = TextView(this)
        icon.text = store.icon
        icon.textSize = 40f
        icon.setPadding(0, 0, dp(16), 0)

        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val name = TextView(this)
        name.text = store.label
        name.textSize = 20f
        name.setTextColor(Color.BLACK)
        name.typeface = Typeface.DEFAULT_BOLD
        sub.setTextColor(Color.BLACK)
        sub.textSize = 13f
        txt.addView(name)
        txt.addView(sub)

        c.addView(icon)
        c.addView(txt)
        c.setOnClickListener { onClick() }
        return c
    }

    private fun refreshLocal() {
        if (!::localSub.isInitialized) return
        localSub.text = if (LocalFs.hasAccess(this)) {
            try {
                val sf = StatFs(LocalFs.rootDir().path)
                "Phone ki files · " + fmtSize(sf.availableBytes) + " khaali"
            } catch (e: Exception) {
                "Phone ki files"
            }
        } else {
            "Phone ki files · permission chahiye (dabao)"
        }
    }

    private fun openLocal() {
        if (LocalFs.hasAccess(this)) {
            open(Store.LOCAL)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Phone storage ki permission")
            .setMessage(
                if (Build.VERSION.SDK_INT >= 30)
                    "Phone ki saari files dikhane ke liye 'All files access' chahiye.\n\nSettings khulegi: MyFiles ke aage switch ON karo, phir wapas aao."
                else
                    "Phone ki files dikhane ke liye storage permission chahiye."
            )
            .setPositiveButton("Allow") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val pickMode: Boolean get() = intent.getBooleanExtra("pick", false)

    private val pickBrowser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                setResult(RESULT_OK, r.data)
                finish()
            }
        }

    private fun open(store: Store) {
        val i = Intent(this, BrowserActivity::class.java).putExtra("store", store.id)
        if (pickMode) {
            i.putExtra("pick", true).putExtra("pick_multi", intent.getBooleanExtra("pick_multi", false))
            pickBrowser.launch(i)
        } else {
            startActivity(i)
        }
    }

    private fun refreshClip() {
        val c = Session.clip
        clipTv.text = if (c.isEmpty()) "" else
            "📋 ${c.size} item clipboard me (${if (Session.clipMove) "Move" else "Copy"}) — storage kholo, folder me jao aur 'Paste here' dabao"
    }

    private fun probeHf() {
        io.execute {
            val reason = Storages.hfDisabledReason()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                hfReason = reason
                if (reason == null) {
                    hfSub.text = "Hugging Face Storage Bucket"
                    hfCard.alpha = 1f
                } else {
                    hfSub.text = "❌ $reason"
                    hfCard.alpha = 0.5f
                }
            }
        }
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setItems(
                arrayOf("🎬 Kholne ka tarika: " + Opener.labels[Opener.mode(this)], "🚪 Logout / server badlo")
            ) { _, i -> if (i == 0) pickOpenMode() else logout() }
            .show()
    }

    private fun pickOpenMode() {
        AlertDialog.Builder(this)
            .setTitle("Photo / video / music kaise khole?")
            .setSingleChoiceItems(Opener.labels, Opener.mode(this)) { d, which ->
                Opener.setMode(this, which)
                d.dismiss()
            }
            .show()
    }

    private fun logout() {
        stopService(Intent(this, PlaybackService::class.java))
        Session.clearPass(this)
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
