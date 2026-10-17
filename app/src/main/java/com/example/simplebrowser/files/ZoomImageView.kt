package com.example.simplebrowser.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** Pinch-zoom + pan + double-tap zoom wala ImageView (ViewPager2 ke andar chalta hai). */
class ZoomImageView(ctx: Context) : AppCompatImageView(ctx) {

    var onTap: (() -> Unit)? = null

    private val m = Matrix()
    private val vals = FloatArray(9)
    private var cur = 1f // base fit-scale ke relative

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (cur * detector.scaleFactor).coerceIn(1f, 6f)
            val f = target / cur
            cur = target
            m.postScale(f, f, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = m
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (cur > 1.05f) 1f else 2.5f
            val f = target / cur
            cur = target
            m.postScale(f, f, e.x, e.y)
            fixTranslation()
            imageMatrix = m
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (cur > 1.05f) {
                m.postTranslate(-distanceX, -distanceY)
                fixTranslation()
                imageMatrix = m
                return true
            }
            return false
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        val s = min(width / dw, height / dh)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
        cur = 1f
        imageMatrix = m
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        m.getValues(vals)
        val s = vals[Matrix.MSCALE_X]
        val w = d.intrinsicWidth * s
        val h = d.intrinsicHeight * s
        var tx = vals[Matrix.MTRANS_X]
        var ty = vals[Matrix.MTRANS_Y]
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
        vals[Matrix.MTRANS_X] = tx
        vals[Matrix.MTRANS_Y] = ty
        m.setValues(vals)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        // zoom hone par ya 2 ungli par ViewPager2 ko swipe na lene do
        parent?.requestDisallowInterceptTouchEvent(cur > 1.05f || event.pointerCount > 1)
        return true
    }
}
