package com.iherdt.lumaframe

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumMap
import java.util.Random

/**
 * Feeds [QrSearch] synthetic camera frames: a QR for a typical album link, drawn small and
 * off-centre on a cluttered background, blurred and noisy like the Portal's fixed-focus lens.
 * The same frames go through the 1.2.0 scanner's passes ([legacyDecode]) for comparison.
 */
class QrSearchTest {
    private val link = "https://photos.app.goo.gl/AbCdEfGhIjKlMnOp1"

    /** A frame with the code drawn at [px] pixels per module, centred at ([cx], [cy]). */
    private fun frame(
        w: Int,
        h: Int,
        px: Float,
        cx: Int,
        cy: Int,
        blur: Float,
        seed: Long,
        ec: ErrorCorrectionLevel,
    ): Luma {
        val rnd = Random(seed)
        val hints = mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ec)
        val m = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, hints)
        val n = m.width
        val img = FloatArray(w * h)
        // Background: a dim room with furniture-like stripes and blocks.
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = 50f + 25f * Math.sin(x / 90.0).toFloat() + 15f * Math.cos(y / 140.0).toFloat()
                if ((x / 37) % 5 == 0) v += 35f // vertical slats: strong edges in one direction only
                img[y * w + x] = v
            }
        }
        // The "phone screen": a white page with the code on it, 4 modules of quiet zone.
        val side = px * (n + 8)
        val x0 = cx - side / 2
        val y0 = cy - side / 2
        for (y in Math.max(0, y0.toInt())..Math.min(h - 1, (y0 + side).toInt())) {
            for (x in Math.max(0, x0.toInt())..Math.min(w - 1, (x0 + side).toInt())) {
                // 3x3 supersampling so fractional module sizes are drawn honestly.
                var s = 0f
                for (j in 0..2) for (i in 0..2) {
                    val mx = Math.floor(((x + (i + 0.5f) / 3 - x0) / px - 4).toDouble()).toInt()
                    val my = Math.floor(((y + (j + 0.5f) / 3 - y0) / px - 4).toDouble()).toInt()
                    val dark = mx in 0 until n && my in 0 until n && m.get(mx, my)
                    s += if (dark) 25f else 225f
                }
                img[y * w + x] = s / 9
            }
        }
        val soft = if (blur > 0f) gauss(img, w, h, blur) else img
        val out = ByteArray(w * h)
        for (i in out.indices) {
            val v = soft[i] + rnd.nextGaussian().toFloat() * 4f
            out[i] = Math.max(0f, Math.min(255f, v)).toInt().toByte()
        }
        return Luma(out, w, h)
    }

    private fun gauss(
        src: FloatArray,
        w: Int,
        h: Int,
        sigma: Float,
    ): FloatArray {
        val r = Math.ceil(sigma * 2.5).toInt()
        val k = FloatArray(2 * r + 1)
        var sum = 0f
        for (i in -r..r) {
            k[i + r] = Math.exp(-(i * i) / (2.0 * sigma * sigma)).toFloat()
            sum += k[i + r]
        }
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -r..r) s += k[i + r] * src[y * w + Math.max(0, Math.min(w - 1, x + i))]
            tmp[y * w + x] = s
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -r..r) s += k[i + r] * tmp[Math.max(0, Math.min(h - 1, y + i)) * w + x]
            out[y * w + x] = s
        }
        return out
    }

    // ---- the 1.2.0 scanner's passes, without the redundant rotations and mirrors ----

    private val legacyReader = QRCodeReader()
    private val legacyHints =
        EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
        }

    private fun legacyRegion(
        f: Luma,
        l: Int,
        t: Int,
        rw: Int,
        rh: Int,
    ): String? {
        val src = PlanarYUVLuminanceSource(f.data, f.w, f.h, l, t, rw, rh, false)
        for (lum in arrayOf(src, src.invert())) {
            for (bin in arrayOf(HybridBinarizer(lum), GlobalHistogramBinarizer(lum))) {
                try {
                    return legacyReader.decode(BinaryBitmap(bin), legacyHints).text
                } catch (e: Exception) {
                    // next
                } finally {
                    legacyReader.reset()
                }
            }
        }
        return null
    }

    private fun legacyDecode(f: Luma): String? {
        legacyRegion(f, 0, 0, f.w, f.h)?.let { return it }
        for (frac in floatArrayOf(0.6f, 0.4f)) {
            val side = (Math.min(f.w, f.h) * frac).toInt()
            legacyRegion(f, (f.w - side) / 2, (f.h - side) / 2, side, side)?.let { return it }
        }
        return null
    }

    /** Smallest module size (in tenths of a pixel) from which every larger tested size reads. */
    private fun floorPx(
        sizes: List<Float>,
        read: (Float) -> Boolean,
    ): Float {
        var floor = Float.MAX_VALUE
        for (px in sizes.sortedDescending()) {
            if (read(px)) floor = px else break
        }
        return floor
    }

    @Test
    fun readsSmallerCodesThanTheOldPassesOnThe720pCamera() {
        val sizes = listOf(1.4f, 1.6f, 1.8f, 2.0f, 2.2f, 2.5f, 3.0f, 4.0f, 6.0f)
        val blur = 0.9f
        val places = listOf(640 to 360, 400 to 250, 900 to 480, 250 to 560)
        var newWins = 0
        for ((i, p) in places.withIndex()) {
            val old =
                floorPx(sizes) { px ->
                    legacyDecode(frame(1280, 720, px, p.first, p.second, blur, 7L + i, ErrorCorrectionLevel.L)) == link
                }
            val new =
                floorPx(sizes) { px ->
                    QrSearch().scan(frame(1280, 720, px, p.first, p.second, blur, 7L + i, ErrorCorrectionLevel.L)).text == link
                }
            println("720p at ${p.first},${p.second}: old reads from $old px/module, new from $new")
            assertTrue("new floor $new must not be worse than old $old at $p", new <= old)
            if (new < old) newWins++
        }
        assertTrue("the new search should read smaller codes somewhere", newWins >= 1)
    }

    @Test
    fun findsASmallCodeAnywhereInA12MegapixelFrame() {
        // 6 cm code at about 1 m from a 4000 px wide, 96 degree camera: about 4 px per module.
        for ((i, p) in listOf(2000 to 1500, 700 to 600, 3300 to 2400).withIndex()) {
            val f = frame(4000, 3000, 4.0f, p.first, p.second, 1.2f, 21L + i, ErrorCorrectionLevel.L)
            val t0 = System.nanoTime()
            val r = QrSearch().scan(f)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("12MP at ${p.first},${p.second}: ${r.text != null} by ${r.pass} in $ms ms, ${r.passes} passes")
            assertEquals(link, r.text)
            val roi = r.roi!!
            assertTrue(
                "the reported place holds the code",
                p.first in roi.left..(roi.left + roi.w) && p.second in roi.top..(roi.top + roi.h),
            )
        }
    }

    @Test
    fun readsALargeCodeFromTheOverviewAlone() {
        val f = frame(4000, 3000, 30f, 2000, 1500, 2f, 3L, ErrorCorrectionLevel.M)
        val r = QrSearch().scan(f)
        assertEquals(link, r.text)
        assertEquals("overview", r.pass)
    }

    @Test
    fun anEmptyRoomYieldsNothing() {
        val f = frame(1280, 720, 3f, -4000, -4000, 1f, 5L, ErrorCorrectionLevel.L)
        val r = QrSearch().scan(f)
        assertEquals(null, r.text)
    }

    @Test
    fun enlargeAndShrinkKeepGeometry() {
        val f = frame(320, 240, 4f, 160, 120, 0f, 1L, ErrorCorrectionLevel.L)
        val big = QrSearch.enlarge2(f)
        assertEquals(640, big.w)
        assertEquals(480, big.h)
        val back = QrSearch.shrink(big, 2)
        var diff = 0L
        for (i in back.data.indices) diff += Math.abs((back.data[i].toInt() and 0xFF) - (f.data[i].toInt() and 0xFF))
        assertTrue("mean error ${diff / back.data.size}", diff / back.data.size < 6)
    }
}
