package com.example.simplebrowser.files

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Grid cell: width ke barabar height. */
class SquareFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

class FileAdapter(
    private val ctx: Context,
    private val onClick: (Entry) -> Unit,
    private val onLong: (Entry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<Entry> = emptyList()
    var grid: Boolean = false
    var thumbPx: Int = 160
    var videoThumbs: Boolean = true

    // Multi-select: selecting = mode ON; selected = chuni hui entries ke url (BrowserActivity ka live set)
    var selecting: Boolean = false
    var selected: Set<String> = emptySet()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    inner class ListVH(
        root: View,
        val icon: TextView,
        val thumb: ImageView,
        val name: TextView,
        val info: TextView,
        val check: TextView
    ) : RecyclerView.ViewHolder(root)

    inner class GridVH(
        root: View,
        val icon: TextView,
        val thumb: ImageView,
        val name: TextView,
        val badge: TextView,
        val check: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun getItemViewType(position: Int): Int = if (grid) 1 else 0

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 1) buildGrid() else buildList()

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val e = items[position]
        if (holder is ListVH) {
            holder.icon.text = Kinds.emoji(e.kind)
            holder.name.text = e.name
            holder.info.text =
                if (e.isDir) fmtDate(e.modified) else fmtSize(e.size) + "  ·  " + fmtDate(e.modified)
            bindThumb(holder.thumb, e)
        } else if (holder is GridVH) {
            holder.icon.text = Kinds.emoji(e.kind)
            holder.name.text = e.name
            holder.badge.visibility = if (e.kind == Kind.VIDEO) View.VISIBLE else View.GONE
            bindThumb(holder.thumb, e)
        }
        val sel = selecting && e.url in selected
        val chk = if (holder is ListVH) holder.check else (holder as GridVH).check
        chk.visibility = if (selecting) View.VISIBLE else View.GONE
        chk.text = if (sel) "✓" else ""
        chk.background = checkBg(sel)
        holder.itemView.foreground = if (sel) ColorDrawable(0x55FFB300) else null
        holder.itemView.setOnClickListener { onClick(e) }
        holder.itemView.setOnLongClickListener {
            onLong(e)
            true
        }
    }

    private fun bindThumb(iv: ImageView, e: Entry) {
        if (e.kind == Kind.IMAGE || e.kind == Kind.VIDEO) {
            Thumbs.load(iv, e.url, e.kind, thumbPx, videoThumbs)
        } else {
            iv.tag = null
            iv.setImageDrawable(null)
        }
    }

    private fun checkBg(on: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        if (on) {
            g.setColor(0xFFFFB300.toInt())
        } else {
            g.setColor(0x66000000)
            g.setStroke(ctx.dp(1), Color.WHITE)
        }
        return g
    }

    private fun checkView(): TextView {
        val t = TextView(ctx)
        t.textSize = 12f
        t.setTextColor(Color.BLACK)
        t.gravity = Gravity.CENTER
        t.visibility = View.GONE
        return t
    }

    private fun buildList(): ListVH {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
        row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        row.setBackgroundResource(tv.resourceId)

        val box = FrameLayout(ctx)
        box.layoutParams = LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56))
        val bg = GradientDrawable()
        bg.setColor(0xFFEEEEEE.toInt())
        bg.cornerRadius = ctx.dp(8).toFloat()
        box.background = bg
        box.clipToOutline = true
        box.outlineProvider = ViewOutlineProvider.BACKGROUND

        val icon = TextView(ctx)
        icon.textSize = 26f
        icon.gravity = Gravity.CENTER
        box.addView(icon, FrameLayout.LayoutParams(MATCH, MATCH))

        val thumb = ImageView(ctx)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        box.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp.leftMargin = ctx.dp(12)
        col.layoutParams = lp

        val name = TextView(ctx)
        name.setTextColor(Color.BLACK)
        name.textSize = 15f
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END

        val info = TextView(ctx)
        info.setTextColor(Color.BLACK)
        info.textSize = 12f
        info.maxLines = 1

        val check = checkView()
        val clp = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.TOP or Gravity.START)
        clp.setMargins(ctx.dp(3), ctx.dp(3), 0, 0)
        box.addView(check, clp)

        col.addView(name)
        col.addView(info)
        row.addView(box)
        row.addView(col)
        return ListVH(row, icon, thumb, name, info, check)
    }

    private fun buildGrid(): GridVH {
        val cell = SquareFrame(ctx)
        val lp = RecyclerView.LayoutParams(MATCH, WRAP)
        lp.setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
        cell.layoutParams = lp
        val bg = GradientDrawable()
        bg.setColor(0xFFEEEEEE.toInt())
        bg.cornerRadius = ctx.dp(6).toFloat()
        cell.background = bg
        cell.clipToOutline = true
        cell.outlineProvider = ViewOutlineProvider.BACKGROUND

        val icon = TextView(ctx)
        icon.textSize = 34f
        icon.gravity = Gravity.CENTER
        cell.addView(icon, FrameLayout.LayoutParams(MATCH, MATCH))

        val thumb = ImageView(ctx)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        cell.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))

        val name = TextView(ctx)
        name.setTextColor(Color.BLACK)
        name.textSize = 11f
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        name.setBackgroundColor(0xCCFFFFFF.toInt())
        name.setPadding(ctx.dp(4), ctx.dp(2), ctx.dp(4), ctx.dp(2))
        cell.addView(name, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        val badge = TextView(ctx)
        badge.text = "▶"
        badge.setTextColor(Color.WHITE)
        badge.textSize = 12f
        badge.setBackgroundColor(0x99000000.toInt())
        badge.setPadding(ctx.dp(5), ctx.dp(1), ctx.dp(5), ctx.dp(1))
        cell.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))

        val check = checkView()
        val clp = FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.TOP or Gravity.START)
        clp.setMargins(ctx.dp(5), ctx.dp(5), 0, 0)
        cell.addView(check, clp)

        return GridVH(cell, icon, thumb, name, badge, check)
    }
}
