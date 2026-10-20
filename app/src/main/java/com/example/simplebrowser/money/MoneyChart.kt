package com.example.simplebrowser.money

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** 12k / 1.5L jaisa chhota number (graph ke axis ke liye). */
fun mmCompact(v: Double): String {
    val n = Math.round(v)
    val a = Math.abs(n)
    val sg = if (n < 0) "-" else ""
    fun clean(d: Double, dec: Int): String {
        val s = String.format(java.util.Locale.US, "%." + dec + "f", d)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
    if (a >= 100000) return sg + clean(a / 100000.0, if (a >= 1000000) 0 else 1) + "L"
    if (a >= 1000) return sg + clean(a / 1000.0, if (a >= 10000) 0 else 1) + "k"
    return sg + a.toString()
}

private fun niceMax(mx: Double): Double {
    if (!(mx > 0)) return 1.0
    val p = Math.pow(10.0, Math.floor(Math.log10(mx)))
    val f = mx / p
    val m = if (f <= 1) 1.0 else if (f <= 2) 2.0 else if (f <= 4) 4.0 else if (f <= 8) 8.0 else 10.0
    return m * p
}

class ChartSeries(val name: String, val color: Int, val vals: List<Double?>, val colorFn: ((Double) -> Int)? = null)

/** Bar / line graph (400x210 ke virtual canvas par bana, screen width ke hisaab se scale hota hai). */
class ChartView(ctx: Context) : View(ctx) {
    var kind = "bar"
    var labels: List<String> = emptyList()
    var series: List<ChartSeries> = emptyList()
    var fmt: (Double) -> String = { mmCompact(it) }
    var ref = 0.0
    var refLbl = ""

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, Math.round(w * 210f / 400f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val W = 400f
        val H = 210f
        val L = 40f
        val R = 8f
        val T = 14f
        val B = 24f
        val pw = W - L - R
        val ph = H - T - B
        val n = labels.size
        if (n == 0) return
        canvas.save()
        val s = width / W
        canvas.scale(s, s)

        var mx = ref
        for (se in series) for (v in se.vals) if (v != null && v > mx) mx = v
        mx = niceMax(mx * 1.05)
        val gw = pw / n
        fun yOf(v: Double): Float = (T + ph - ph * v / mx).toFloat()

        // grid + y labels
        for (k in 0..4) {
            val yy = T + ph - ph * k / 4f
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.6f
            p.color = Color.parseColor("#2A2E39")
            p.pathEffect = null
            canvas.drawLine(L, yy, W - R, yy, p)
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#848DA0")
            p.textSize = 8.5f
            p.textAlign = Paint.Align.RIGHT
            canvas.drawText(fmt(mx * k / 4.0), L - 4, yy + 3, p)
        }
        // x labels
        val step = if (n > 12) 2 else 1
        p.textSize = 9f
        p.textAlign = Paint.Align.CENTER
        p.color = Color.parseColor("#848DA0")
        for (i in 0 until n) if (i % step == 0) canvas.drawText(labels[i], L + i * gw + gw / 2, H - 8, p)

        if (kind == "bar") {
            val ns = Math.max(series.size, 1)
            val bw = gw * 0.74f / ns
            for ((si, se) in series.withIndex()) {
                for ((i, v) in se.vals.withIndex()) {
                    if (v == null || i >= n) continue
                    val x = L + i * gw + gw * 0.13f + si * bw
                    val y = yOf(v)
                    p.style = Paint.Style.FILL
                    p.color = se.colorFn?.invoke(v) ?: se.color
                    val bottom = T + ph
                    canvas.drawRoundRect(RectF(x, y, x + Math.max(bw - 1, 1f), Math.max(bottom, y)), 1.5f, 1.5f, p)
                    if (ns == 1 && n <= 12 && v > 0) {
                        p.color = Color.parseColor("#D1D4DC")
                        p.textSize = 7.5f
                        p.textAlign = Paint.Align.CENTER
                        canvas.drawText(fmt(v), x + bw / 2, y - 3, p)
                    }
                }
            }
        } else {
            for (se in series) {
                val path = Path()
                var cnt = 0
                p.style = Paint.Style.FILL
                p.color = se.color
                for ((i, v) in se.vals.withIndex()) {
                    if (v == null || i >= n) continue
                    val x = L + i * gw + gw / 2
                    val y = yOf(v)
                    if (cnt == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    cnt++
                    canvas.drawCircle(x, y, 2.4f, p)
                }
                if (cnt > 1) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 1.8f
                    p.strokeJoin = Paint.Join.ROUND
                    canvas.drawPath(path, p)
                    p.style = Paint.Style.FILL
                }
            }
        }

        if (ref > 0) {
            val y = yOf(ref)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f
            p.color = Color.parseColor("#FFB74D")
            p.pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
            canvas.drawLine(L, y, W - R, y, p)
            p.pathEffect = null
            p.style = Paint.Style.FILL
            p.textSize = 8f
            p.textAlign = Paint.Align.RIGHT
            canvas.drawText(refLbl, W - R, y - 3, p)
        }
        canvas.restore()
    }
}
