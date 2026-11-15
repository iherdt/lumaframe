package com.portalhacks.frame

import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap

/** A grey frame: [w]x[h] luma bytes, row-major, no padding. */
internal class Luma(
    @JvmField val data: ByteArray,
    @JvmField val w: Int,
    @JvmField val h: Int,
)

/** A square-ish region of a frame, in that frame's pixels. */
internal class Roi(
    @JvmField val left: Int,
    @JvmField val top: Int,
    @JvmField val w: Int,
    @JvmField val h: Int,
) {
    val cx get() = left + w / 2
    val cy get() = top + h / 2
}

/** What one frame yielded: the decoded [text], or where a code was seen but not yet read. */
internal class QrResult(
    @JvmField val text: String?,
    /** Where a code was read or located (frame pixels), or null when nothing was seen. */
    @JvmField val roi: Roi?,
    /** Name of the pass that produced [text] or [roi], for the log. */
    @JvmField val pass: String,
    @JvmField val passes: Int,
)

/**
 * The QR search over one camera frame, free of Android types so it runs in a plain JVM test.
 *
 * ZXing reads a QR in any rotation and reads mirrored codes by itself, so rotating or mirroring
 * the frame only burns time. What it cannot do is read a code whose modules are under about
 * three pixels, and it gets slow on a large frame. So the search works at two scales:
 *
 *  - an *overview*, the whole frame shrunk to about a megapixel, which reads any code that is
 *    large in the picture and shows where a small one probably is (see [candidates]);
 *  - *native crops* around the places worth a closer look: where a code was last seen, the
 *    most code-like patches of the overview, and the middle of the frame. A crop from a
 *    low-resolution camera is enlarged first, which gives ZXing whole pixels per module.
 *
 * One instance per scanner; [scan] is called from a single worker thread.
 */
internal class QrSearch {
    private val reader = QRCodeReader()
    private var tracked: Roi? = null
    private var trackedUntilFrame = 0
    private var frameNo = 0

    /** Forget the last seen position (e.g. after the camera changed its zoom). */
    fun reset() {
        tracked = null
    }

    fun scan(frame: Luma): QrResult {
        frameNo++
        var passes = 0
        var located: Roi? = null
        var locatedPass = ""
        // An inverted (light on dark) code is rare; look for it on every third frame only.
        val inverted = frameNo % 3 == 0

        fun attempt(
            name: String,
            img: Luma,
            origin: Roi,
        ): String? {
            passes++
            val r = decode(img, inverted)
            if (r.text != null) {
                tracked = origin
                trackedUntilFrame = frameNo + TRACK_FRAMES
                return r.text
            }
            if (r.located && located == null) {
                located = origin
                locatedPass = name
            }
            return null
        }

        fun done(
            text: String,
            name: String,
        ) = QrResult(text, tracked, name, passes)

        // 1. Where a code was just seen: the cheapest place to look again.
        val t = tracked
        if (t != null && frameNo <= trackedUntilFrame) {
            val roi = clamp(t, frame)
            attempt("tracked", prepare(frame, roi), roi)?.let { return done(it, "tracked") }
        }

        // 2. The whole frame at about a megapixel.
        val f = Math.max(1, Math.round(frame.w / OVERVIEW_W.toFloat()))
        val overview = if (f > 1) shrink(frame, f) else frame
        val whole = Roi(0, 0, frame.w, frame.h)
        attempt("overview", overview, whole)?.let { return done(it, "overview") }

        // 3. The most code-like patches of the overview, at full resolution.
        for ((i, c) in candidates(overview).withIndex()) {
            val roi = clamp(Roi(c.left * f, c.top * f, c.w * f, c.h * f), frame)
            val img = prepare(frame, roi)
            attempt("patch$i", img, roi)?.let { return done(it, "patch$i") }
            // A very small patch holds a very small code: one more doubling sometimes reads
            // what the first did not (and sometimes the reverse, so both are tried).
            if (Math.max(roi.w, roi.h) <= ENLARGE_BELOW / 2) {
                attempt("patch${i}x4", enlarge2(img), roi)?.let { return done(it, "patch${i}x4") }
            }
        }

        // 4. The middle of the frame, where people aim.
        val side = Math.min(Math.min(frame.w, frame.h), Math.max(NATIVE_MAX, frame.h / 2))
        val centre = Roi((frame.w - side) / 2, (frame.h - side) / 2, side, side)
        attempt("centre", prepare(frame, centre), centre)?.let { return done(it, "centre") }

        val seen = located
        if (seen != null) {
            // Finder patterns were found but the code did not read: look there first next time.
            tracked = seen
            trackedUntilFrame = frameNo + TRACK_FRAMES
        }
        return QrResult(null, seen, locatedPass, passes)
    }

    private class Attempt(val text: String?, val located: Boolean)

    private fun decode(
        img: Luma,
        inverted: Boolean,
    ): Attempt {
        val src: LuminanceSource = PlanarYUVLuminanceSource(img.data, img.w, img.h, 0, 0, img.w, img.h, false)
        val lum = if (inverted) src.invert() else src
        var located = false
        // Hybrid suits sharp frames and uneven light; the global histogram sometimes wins on
        // soft, low-contrast ones. Only pay for the second when the first saw nothing readable.
        for (pass in 0..1) {
            val bin = if (pass == 0) HybridBinarizer(lum) else GlobalHistogramBinarizer(lum)
            try {
                return Attempt(reader.decode(BinaryBitmap(bin), HINTS).text, true)
            } catch (e: ChecksumException) {
                located = true
            } catch (e: FormatException) {
                located = true
            } catch (e: Exception) {
                // nothing found by this binarizer
            } finally {
                reader.reset()
            }
        }
        return Attempt(null, located)
    }

    /** Cut [roi] out of [frame] at a size ZXing handles well: shrink big crops, enlarge small ones. */
    private fun prepare(
        frame: Luma,
        roi: Roi,
    ): Luma {
        val cut = crop(frame, roi)
        val longest = Math.max(cut.w, cut.h)
        return when {
            longest > NATIVE_MAX * 3 / 2 -> shrink(cut, Math.round(longest / NATIVE_MAX.toFloat()))
            // A crop this small holds a small code: whole pixels per module help the finder.
            longest <= ENLARGE_BELOW -> enlarge2(cut)
            else -> cut
        }
    }

    private fun clamp(
        r: Roi,
        frame: Luma,
    ): Roi {
        val w = Math.min(r.w, frame.w)
        val h = Math.min(r.h, frame.h)
        val l = Math.max(0, Math.min(r.left, frame.w - w))
        val t = Math.max(0, Math.min(r.top, frame.h - h))
        return Roi(l, t, w, h)
    }

    companion object {
        /** Width the whole frame is shrunk to for the first pass. */
        const val OVERVIEW_W = 1000

        /** Longest side handed to ZXing for a close look. */
        const val NATIVE_MAX = 1100

        /** Crops no longer than this are doubled before decoding. */
        const val ENLARGE_BELOW = 520

        /** Frames to keep looking at the place a code was last seen. */
        const val TRACK_FRAMES = 12

        private val HINTS: Map<DecodeHintType, Any> =
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
            }

        fun crop(
            src: Luma,
            r: Roi,
        ): Luma {
            if (r.left == 0 && r.top == 0 && r.w == src.w && r.h == src.h) return src
            val out = ByteArray(r.w * r.h)
            for (y in 0 until r.h) {
                System.arraycopy(src.data, (r.top + y) * src.w + r.left, out, y * r.w, r.w)
            }
            return Luma(out, r.w, r.h)
        }

        /** Box-average [src] down by the whole factor [f]. */
        fun shrink(
            src: Luma,
            f: Int,
        ): Luma {
            if (f <= 1) return src
            val w = src.w / f
            val h = src.h / f
            val out = ByteArray(w * h)
            val d = src.data
            val n = f * f
            for (y in 0 until h) {
                val o = y * w
                for (x in 0 until w) {
                    var s = 0
                    var row = y * f * src.w + x * f
                    repeat(f) {
                        for (i in 0 until f) s += d[row + i].toInt() and 0xFF
                        row += src.w
                    }
                    out[o + x] = (s / n).toByte()
                }
            }
            return Luma(out, w, h)
        }

        /** Double [src] with bilinear interpolation. */
        fun enlarge2(src: Luma): Luma {
            val w = src.w * 2
            val h = src.h * 2
            val out = ByteArray(w * h)
            val d = src.data
            for (y in 0 until h) {
                // Sample position in source pixels: (y - 0.5) / 2, kept in quarter units.
                val qy = Math.max(0, Math.min((src.h - 1) * 4, 2 * y - 1))
                val y0 = qy shr 2
                val y1 = Math.min(src.h - 1, y0 + 1)
                val fy = qy and 3
                for (x in 0 until w) {
                    val qx = Math.max(0, Math.min((src.w - 1) * 4, 2 * x - 1))
                    val x0 = qx shr 2
                    val x1 = Math.min(src.w - 1, x0 + 1)
                    val fx = qx and 3
                    val a = d[y0 * src.w + x0].toInt() and 0xFF
                    val b = d[y0 * src.w + x1].toInt() and 0xFF
                    val c = d[y1 * src.w + x0].toInt() and 0xFF
                    val e = d[y1 * src.w + x1].toInt() and 0xFF
                    val top = a * (4 - fx) + b * fx
                    val bot = c * (4 - fx) + e * fx
                    out[y * w + x] = ((top * (4 - fy) + bot * fy + 8) shr 4).toByte()
                }
            }
            return Luma(out, w, h)
        }

        /**
         * Up to two square patches of [img] that look most like a QR code: dense fine detail
         * running in both directions at once (a code has as many horizontal as vertical edges;
         * blinds, shelves and text lines mostly have one). Returned in [img] pixels.
         */
        fun candidates(img: Luma): List<Roi> {
            val b = BLOCK
            val gw = img.w / b
            val gh = img.h / b
            if (gw < 3 || gh < 3) return emptyList()
            val score = IntArray(gw * gh)
            val d = img.data
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    var ex = 0
                    var ey = 0
                    var y = gy * b
                    val yEnd = y + b - 2
                    while (y < yEnd) {
                        var i = y * img.w + gx * b
                        val iEnd = i + b - 2
                        while (i < iEnd) {
                            val v = d[i].toInt() and 0xFF
                            val dx = v - (d[i + 2].toInt() and 0xFF)
                            val dy = v - (d[i + 2 * img.w].toInt() and 0xFF)
                            ex += if (dx < 0) -dx else dx
                            ey += if (dy < 0) -dy else dy
                            i += 2
                        }
                        y += 2
                    }
                    score[gy * gw + gx] = Math.min(ex, ey)
                }
            }
            val out = ArrayList<Roi>(2)
            val used = BooleanArray(gw * gh)
            var floor = 0
            repeat(2) {
                var best = -1
                var bestV = 0
                for (i in score.indices) {
                    if (!used[i] && score[i] > bestV) {
                        bestV = score[i]
                        best = i
                    }
                }
                // Per sample pair a mean step of about 6 grey levels; flatter than that is no code.
                val samples = (b / 2 - 1) * (b / 2 - 1)
                if (best < 0 || bestV < samples * 6 || bestV < floor) return out
                if (floor == 0) floor = bestV / 3
                // Grow over the neighbouring blocks that are at least half as busy.
                var x0 = best % gw
                var x1 = x0
                var y0 = best / gw
                var y1 = y0
                val thr = bestV / 2
                var grew = true
                while (grew && (x1 - x0) < MAX_BLOCKS && (y1 - y0) < MAX_BLOCKS) {
                    grew = false
                    if (x0 > 0 && colAbove(score, gw, x0 - 1, y0, y1, thr)) {
                        x0--
                        grew = true
                    }
                    if (x1 < gw - 1 && colAbove(score, gw, x1 + 1, y0, y1, thr)) {
                        x1++
                        grew = true
                    }
                    if (y0 > 0 && rowAbove(score, gw, y0 - 1, x0, x1, thr)) {
                        y0--
                        grew = true
                    }
                    if (y1 < gh - 1 && rowAbove(score, gw, y1 + 1, x0, x1, thr)) {
                        y1++
                        grew = true
                    }
                }
                for (gy in Math.max(0, y0 - 1)..Math.min(gh - 1, y1 + 1)) {
                    for (gx in Math.max(0, x0 - 1)..Math.min(gw - 1, x1 + 1)) used[gy * gw + gx] = true
                }
                // A square around the busy blocks, with room for the quiet zone.
                val cx = (x0 + x1 + 1) * b / 2
                val cy = (y0 + y1 + 1) * b / 2
                val side = Math.max((Math.max(x1 - x0, y1 - y0) + 1) * b * 3 / 2, b * 4)
                out.add(Roi(cx - side / 2, cy - side / 2, side, side))
            }
            return out
        }

        private const val BLOCK = 16
        private const val MAX_BLOCKS = 24

        private fun colAbove(
            s: IntArray,
            gw: Int,
            x: Int,
            y0: Int,
            y1: Int,
            thr: Int,
        ): Boolean {
            var n = 0
            for (y in y0..y1) if (s[y * gw + x] >= thr) n++
            return n * 2 > (y1 - y0 + 1)
        }

        private fun rowAbove(
            s: IntArray,
            gw: Int,
            y: Int,
            x0: Int,
            x1: Int,
            thr: Int,
        ): Boolean {
            var n = 0
            for (x in x0..x1) if (s[y * gw + x] >= thr) n++
            return n * 2 > (x1 - x0 + 1)
        }
    }
}
