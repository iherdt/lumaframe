package com.iherdt.lumaframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The scanner's viewfinder for the Camera2 path: draws the small grey frames [QrCamera] hands
 * over (already mirrored, like a mirror the user aims in) and a box around a code that has
 * been located but not read yet, so the user knows to hold still.
 */
internal class ViewfinderView(context: Context) : View(context) {
    private var frame: Bitmap? = null
    private var box: RectF? = null // in 0..1 of the mirrored frame
    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 4f).toFloat()
            color = 0xFF4CD964.toInt()
        }

    fun setFrame(b: Bitmap) {
        frame = b
        invalidate()
    }

    /** Mark [roi] (in a [frameW]x[frameH] camera frame), or clear the mark with null. */
    fun setBox(
        roi: Roi?,
        frameW: Int,
        frameH: Int,
    ) {
        box =
            if (roi == null || frameW <= 0 || frameH <= 0 || (roi.w >= frameW && roi.h >= frameH)) {
                null // the whole frame is no place to point at
            } else {
                val l = roi.left.toFloat() / frameW
                val r = (roi.left + roi.w).toFloat() / frameW
                RectF(1f - r, roi.top.toFloat() / frameH, 1f - l, (roi.top + roi.h).toFloat() / frameH)
            }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = frame ?: return
        // Fit the whole frame inside the view.
        val scale = Math.min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(b, null, dst, bitmapPaint)
        val bx = box ?: return
        val r = Ui.dp(context, 10f).toFloat()
        canvas.drawRoundRect(
            dst.left + bx.left * w,
            dst.top + bx.top * h,
            dst.left + bx.right * w,
            dst.top + bx.bottom * h,
            r,
            r,
            boxPaint,
        )
    }

    companion object {
        /** Grey bytes to a bitmap the canvas can draw. Any thread. */
        fun toBitmap(f: Luma): Bitmap {
            val px = IntArray(f.w * f.h)
            for (i in px.indices) {
                val v = f.data[i].toInt() and 0xFF
                px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            return Bitmap.createBitmap(px, f.w, f.h, Bitmap.Config.ARGB_8888)
        }
    }
}
