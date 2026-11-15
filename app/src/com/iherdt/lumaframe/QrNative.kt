package com.iherdt.lumaframe

import android.graphics.Bitmap
import android.util.Log
import zxingcpp.BarcodeReader
import java.nio.ByteBuffer

/**
 * The scanner's decoder on the device: zxing-cpp, which finds and reads a QR code anywhere in
 * a whole grey picture in a few milliseconds and copes with the ring-cornered, dotted codes
 * Google Photos shows (the Java ZXing in `libs/` reads none of those from this camera).
 *
 * Not thread-safe; used from the single decode thread.
 */
internal class QrNative private constructor(private val reader: BarcodeReader) : QrReader {
    private var bitmap: Bitmap? = null

    override fun read(img: Luma): QrHit? {
        var bmp = bitmap
        if (bmp == null || bmp.width != img.w || bmp.height != img.h) {
            bmp?.recycle()
            // ALPHA_8 is one byte per pixel, which the native side takes as luminance.
            bmp = Bitmap.createBitmap(img.w, img.h, Bitmap.Config.ALPHA_8)
            bitmap = bmp
        }
        bmp!!.copyPixelsFromBuffer(ByteBuffer.wrap(img.data, 0, img.w * img.h))
        var seen: QrHit? = null
        for (r in reader.read(bmp)) {
            val p = r.position
            val l = minOf(p.topLeft.x, p.topRight.x, p.bottomLeft.x, p.bottomRight.x)
            val t = minOf(p.topLeft.y, p.topRight.y, p.bottomLeft.y, p.bottomRight.y)
            val right = maxOf(p.topLeft.x, p.topRight.x, p.bottomLeft.x, p.bottomRight.x)
            val bottom = maxOf(p.topLeft.y, p.topRight.y, p.bottomLeft.y, p.bottomRight.y)
            val box = Roi(l, t, Math.max(1, right - l), Math.max(1, bottom - t))
            val text = r.text
            val bad = r.error != null && r.error!!.type != BarcodeReader.ErrorType.NONE
            if (!bad && !text.isNullOrEmpty()) return QrHit(text, box)
            if (seen == null) seen = QrHit(null, box)
        }
        return seen
    }

    fun close() {
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        private const val TAG = "Lumaframe"

        /** The native decoder, or null when its library cannot be loaded on this device. */
        fun create(): QrNative? =
            try {
                val options =
                    BarcodeReader.Options().apply {
                        formats = setOf(BarcodeReader.Format.QR_CODE)
                        tryHarder = true
                        tryRotate = true
                        tryInvert = true
                        tryDownscale = true
                        // Codes that were found but failed their checksum still tell where to look.
                        returnErrors = true
                    }
                QrNative(BarcodeReader(options))
            } catch (e: Throwable) {
                Log.w(TAG, "QR: native decoder unavailable, using ZXing", e)
                null
            }
    }
}
