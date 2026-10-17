package com.example.simplebrowser.files

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File

/** Image viewer: swipe, pinch / double-tap zoom, rotate, slideshow, share, info, delete, dusre app me kholo. */
class ImageViewerActivity : AppCompatActivity() {

    private val items = ArrayList<Entry>()
    private val rotation = HashMap<String, Int>()
    private lateinit var pager: ViewPager2
    private lateinit var topBar: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var playBtn: TextView
    private var barsVisible = true
    private var slideshow = false
    private val handler = Handler(Looper.getMainLooper())

    private val slideTick = object : Runnable {
        override fun run() {
            if (!slideshow || isFinishing) return
            val n = items.size
            if (n > 1) pager.setCurrentItem((pager.currentItem + 1) % n, true)
            handler.postDelayed(this, 3500L)
        }
    }

    inner class PageVH(
        val box: FrameLayout,
        val iv: ZoomImageView,
        val pb: ProgressBar
    ) : RecyclerView.ViewHolder(box)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items.addAll(Queue.images)
        if (items.isEmpty()) {
            finish()
            return
        }
        val start = intent.getIntExtra("index", 0).coerceIn(0, items.size - 1)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        pager = ViewPager2(this)
        pager.offscreenPageLimit = 1
        pager.adapter = object : RecyclerView.Adapter<PageVH>() {
            override fun getItemCount(): Int = items.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
                val box = FrameLayout(this@ImageViewerActivity)
                box.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                val iv = ZoomImageView(this@ImageViewerActivity)
                iv.onTap = { toggleBars() }
                box.addView(iv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                val pb = ProgressBar(this@ImageViewerActivity)
                box.addView(pb, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                ))
                return PageVH(box, iv, pb)
            }

            override fun onBindViewHolder(holder: PageVH, position: Int) {
                val e = items[position]
                holder.iv.setImageDrawable(null)
                holder.iv.tag = e.url
                holder.pb.visibility = View.VISIBLE
                Thumbs.loadFull(e.url, 2000) { bmp ->
                    if (holder.iv.tag == e.url) {
                        holder.pb.visibility = View.GONE
                        if (bmp != null) {
                            holder.iv.setImageBitmap(rotated(bmp, rotation[e.url] ?: 0))
                        } else {
                            Toast.makeText(this@ImageViewerActivity, "Image load nahi hui: ${e.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        root.addView(pager, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        topBar = TextView(this)
        topBar.setTextColor(Color.WHITE)
        topBar.textSize = 14f
        topBar.maxLines = 1
        topBar.setBackgroundColor(0x88000000.toInt())
        topBar.setPadding(dp(14), dp(36), dp(14), dp(10))
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER_VERTICAL
        bottomBar.setBackgroundColor(0x88000000.toInt())
        bottomBar.setPadding(dp(4), dp(4), dp(4), dp(18))
        bottomBar.addView(barBtn("⟲") { rotate(-90) })
        bottomBar.addView(barBtn("⟳") { rotate(90) })
        playBtn = barBtn("▶") { toggleSlideshow() }
        bottomBar.addView(playBtn)
        bottomBar.addView(barBtn("📤") { share() })
        bottomBar.addView(barBtn("ℹ") { info() })
        bottomBar.addView(barBtn("↗") { Opener.external(this, curEntry()) })
        bottomBar.addView(barBtn("🗑") { confirmDelete() })
        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        setContentView(root)
        pager.setCurrentItem(start, false)
        updateTitle(start)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTitle(position)
            }
        })
    }

    private fun barBtn(t: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = t
        b.textSize = 22f
        b.setTextColor(Color.WHITE)
        b.gravity = Gravity.CENTER
        b.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun curEntry(): Entry = items[pager.currentItem.coerceIn(0, items.size - 1)]

    private fun updateTitle(pos: Int) {
        if (items.isEmpty()) return
        val p = pos.coerceIn(0, items.size - 1)
        topBar.text = "${p + 1}/${items.size}   ${items[p].name}"
    }

    private fun rotated(b: Bitmap, deg: Int): Bitmap {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) return b
        val m = Matrix()
        m.postRotate(d.toFloat())
        return try {
            Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        } catch (e: Throwable) {
            b
        }
    }

    private fun rotate(by: Int) {
        val e = curEntry()
        rotation[e.url] = (((rotation[e.url] ?: 0) + by) % 360 + 360) % 360
        pager.adapter?.notifyItemChanged(pager.currentItem)
    }

    private fun toggleSlideshow() {
        slideshow = !slideshow
        playBtn.text = if (slideshow) "⏸" else "▶"
        handler.removeCallbacks(slideTick)
        if (slideshow) {
            handler.postDelayed(slideTick, 3500L)
            if (barsVisible) toggleBars()
            Toast.makeText(this, "Slideshow shuru (screen dabao to ruk jayega)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSlideshow() {
        if (!slideshow) return
        slideshow = false
        playBtn.text = "▶"
        handler.removeCallbacks(slideTick)
    }

    private fun share() {
        val e = curEntry()
        if (LocalFs.isLocal(e.url)) {
            sendFile(LocalFs.fileOf(e.url), e)
            return
        }
        Toast.makeText(this, "Share ke liye download ho raha hai…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val f = Opener.downloadToCache(this, e)
                runOnUiThread { if (!isFinishing) sendFile(f, e) }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, "Download nahi hua: ${ex.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun sendFile(f: File, e: Entry) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND)
                .setType(Opener.mimeFor(e.name, Kind.IMAGE))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, e.name))
        } catch (ex: Exception) {
            Toast.makeText(this, "Share nahi hua: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun info() {
        val e = curEntry()
        val where = if (LocalFs.isLocal(e.url)) LocalFs.fileOf(e.url).absolutePath else Storages.pathOf(if (e.url.startsWith(Storages.root(Store.HF))) Store.HF else Store.AIC, e.url)
        val msg = "Naam: ${e.name}\n" +
            (if (e.size > 0) "Size: ${fmtSize(e.size)}\n" else "") +
            "Badla: ${fmtDate(e.modified)}\n\n$where"
        AlertDialog.Builder(this).setTitle("Info").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun confirmDelete() {
        val e = curEntry()
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage(e.name + "\n\nWapas nahi aayegi.")
            .setPositiveButton("Delete") { _, _ -> doDelete(e) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete(e: Entry) {
        Thread {
            val err: String? = try {
                Fs.delete(e.url)
                null
            } catch (ex: Exception) {
                ex.message ?: "error"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (err != null) {
                    Toast.makeText(this, "❌ $err", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val i = items.indexOfFirst { it.url == e.url }
                if (i >= 0) {
                    items.removeAt(i)
                    pager.adapter?.notifyItemRemoved(i)
                }
                Queue.dirty = true
                if (items.isEmpty()) finish() else updateTitle(pager.currentItem)
                Toast.makeText(this, "Delete ho gayi", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun toggleBars() {
        if (slideshow && barsVisible.not()) stopSlideshow()
        barsVisible = !barsVisible
        topBar.visibility = if (barsVisible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (barsVisible) View.VISIBLE else View.GONE
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (barsVisible) c.show(WindowInsetsCompat.Type.systemBars())
        else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStop() {
        super.onStop()
        stopSlideshow()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
