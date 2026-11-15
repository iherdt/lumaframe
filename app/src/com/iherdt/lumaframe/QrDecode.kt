package com.iherdt.lumaframe

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector
import com.iherdt.lumaframe.QrFilters.crop
import com.iherdt.lumaframe.QrFilters.curve
import com.iherdt.lumaframe.QrFilters.enlarge
import com.iherdt.lumaframe.QrFilters.enlarge2
import com.iherdt.lumaframe.QrFilters.sharpen
import com.iherdt.lumaframe.QrFilters.shrink
import com.iherdt.lumaframe.QrFilters.shrinkBright
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

/** What a [QrReader] found in one picture: the [text], or only the place of an unreadable code. */
internal class QrHit(
    @JvmField val text: String?,
    /** Bounding box of the code in the picture that was read. */
    @JvmField val box: Roi,
)

/** A decoder that takes a whole grey picture and finds a QR code anywhere in it. */
internal fun interface QrReader {
    fun read(img: Luma): QrHit?
}

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
internal class QrSearch(
    /**
     * The fast whole-picture decoder (zxing-cpp on the device). With it the search follows
     * [scanWhole]; without it (plain JVM tests, or if the native library fails to load) it
     * falls back to the ZXing passes below.
     */
    private val whole: QrReader? = null,
) {
    private val decoder = Decoder()
    private var tracked: Roi? = null
    private var trackedUntilFrame = 0
    private var frameNo = 0
    private var polarity = false
    private var polarityUntilFrame = 0

    /** Forget the last seen position (e.g. after the camera changed its zoom). */
    fun reset() {
        tracked = null
    }

    fun scan(frame: Luma): QrResult = if (whole != null) scanWhole(frame, whole) else scanPatches(frame)

    /**
     * The search with a fast whole-picture decoder. Tuned on real frames of a phone showing a
     * Google Photos album code (light dots on a dark sheet, ring corners) to a Portal+ camera.
     * What defeats a decoder there is not size but glare: the screen is far brighter than the
     * room the camera exposes for, so each dot blooms into its neighbours. Shrinking the
     * bright parts again ([shrinkBright]) is what makes those frames readable, best after a
     * mild sharpening; how much to shrink depends on distance and screen brightness, so a few
     * strengths are tried on every frame. Out of 23 recorded frames with the code in view,
     * no variant alone read more than 15 and together they read 19.
     */
    private fun scanWhole(
        frame: Luma,
        reader: QrReader,
    ): QrResult {
        frameNo++
        var passes = 0
        var located: Roi? = null
        var locatedPass = ""
        val full = Roi(0, 0, frame.w, frame.h)

        fun attempt(
            name: String,
            img: Luma,
            origin: Roi,
            scale: Int,
        ): String? {
            passes++
            val hit = reader.read(img) ?: return null
            val b = hit.box
            val place =
                clamp(Roi(origin.left + b.left / scale, origin.top + b.top / scale, b.w / scale, b.h / scale), frame)
            if (hit.text != null) {
                tracked = place
                trackedUntilFrame = frameNo + TRACK_FRAMES
                return hit.text
            }
            if (located == null) {
                located = place
                locatedPass = name
            }
            return null
        }

        fun done(
            text: String,
            name: String,
        ) = QrResult(text, tracked, name, passes)

        // Every frame: sharpened with the bloom shrunk by two pixels (the best single reading
        // of a glaring screen; it read 15 of the 16 recorded frames that were read at all),
        // and the picture as it is (printed codes and dim screens need no help, and shrinking
        // would close the light modules of a small ordinary code).
        val sharp1 = shrinkBright(sharpen(frame, 4, 3), 1)
        attempt("sharp-bloom2", shrinkBright(sharp1, 1), full, 1)?.let { return done(it, "sharp-bloom2") }
        attempt("plain", frame, full, 1)?.let { return done(it, "plain") }

        // Every frame as well: the close look. A phone a hand's length away puts the code at
        // three to six camera pixels per module, where one pixel of bloom is already a third
        // of a module and shrinking by whole pixels is too coarse. So the square where a code
        // was last seen (else the middle of the frame) is sharpened, enlarged three times and
        // shrunk there, in thirds of a camera pixel. Commercial readers do the same under
        // names like "scale up until the module reaches a target size". On simulated frames
        // across distance, brightness and blur this one look read every frame the others read
        // and a sixth to a quarter more. The strength rotates because the right amount depends
        // on how bright the screen is; one turn in five uses a steep tone curve instead of
        // sharpening, which keeps only the cores of the dots.
        val t = tracked
        val near =
            if (t != null && frameNo <= trackedUntilFrame) {
                val want = Math.max(CLOSE_SIDE, Math.max(t.w, t.h) * 17 / 10)
                val side = Math.min(Math.min(frame.w, frame.h), want)
                clamp(Roi(t.cx - side / 2, t.cy - side / 2, side, side), frame)
            } else {
                val side = Math.min(Math.min(frame.w, frame.h), CLOSE_SIDE)
                Roi((frame.w - side) / 2, (frame.h - side) / 2, side, side)
            }
        val f = Math.min(CLOSE_MAX_FACTOR, CLOSE_TARGET / near.w)
        if (f >= 2) {
            val src = crop(frame, near)
            val turn = frameNo % 5
            if (turn == 4) {
                val img = shrinkBright(curve(enlarge(src, f)), f - 1)
                attempt("close-curve", img, near, f)?.let { return done(it, "close-curve") }
            } else {
                val big = enlarge(sharpen(src, 4, 3), f)
                // Shrink by one, two or three camera pixels' worth.
                val strength =
                    when (turn) {
                        2 -> 2
                        0 -> 3
                        else -> 1
                    }
                val name = if (strength == 1) "close" else "close-bloom$strength"
                attempt(name, shrinkBright(big, strength * f), near, f)?.let { return done(it, name) }
            }
        }

        // Every other frame one more whole-frame strength, in rotation: these read recorded
        // frames of a large, strongly glaring code that the looks above missed.
        when (frameNo % 6) {
            0 -> attempt("bloom2", shrinkBright(frame, 2), full, 1)?.let { return done(it, "bloom2") }
            2 -> attempt("bloom3", shrinkBright(frame, 3), full, 1)?.let { return done(it, "bloom3") }
            4 -> attempt("sharp-bloom1", sharp1, full, 1)?.let { return done(it, "sharp-bloom1") }
        }

        val seen = located
        if (seen != null) {
            tracked = seen
            trackedUntilFrame = frameNo + TRACK_FRAMES
        }
        return QrResult(null, seen, locatedPass, passes)
    }

    private fun scanPatches(frame: Luma): QrResult {
        frameNo++
        var passes = 0
        var located: Roi? = null
        var locatedPass = ""
        // Light-on-dark codes are what Google Photos shows on a dark-mode phone, so both
        // polarities get equal time until one of them finds a code; then that one is kept
        // for as long as the code stays in sight.
        val inverted = if (frameNo <= polarityUntilFrame) polarity else frameNo % 2 == 0
        // How soft the picture is depends on how close the phone is held, so the sharpening
        // strength alternates too (on a different beat than the polarity).
        val strong = (frameNo / 2) % 2 == 0

        fun attempt(
            name: String,
            img: Luma,
            origin: Roi,
        ): String? {
            passes++
            val r = decode(img, inverted)
            if (r.located) {
                polarity = inverted
                polarityUntilFrame = frameNo + TRACK_FRAMES
            }
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
            val img = prepare(frame, roi)
            attempt("tracked", img, roi)?.let { return done(it, "tracked") }
            attempt("tracked-sharp", sharpen(img, strong), roi)?.let { return done(it, "tracked-sharp") }
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
            attempt("patch$i-sharp", sharpen(img, strong), roi)?.let { return done(it, "patch$i-sharp") }
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

    internal class Attempt(val text: String?, val located: Boolean)

    internal fun decode(
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
            val det =
                try {
                    Detector(BinaryBitmap(bin).blackMatrix).detect(HINTS)
                } catch (e: ReaderException) {
                    continue // no finder patterns in this black-and-white version
                }
            located = true
            try {
                return Attempt(decoder.decode(det.bits, HINTS).text, true)
            } catch (e: ReaderException) {
                // Corners found, content unreadable from the black-and-white picture.
            }
            QrGray.read(img, inverted, det)?.let { return Attempt(it, true) }
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

        /**
         * Side of the square that gets the close look in [scanWhole]: around a tracked code, else
         * in the middle of the frame. A tighter square enlarged further read no more frames, and
         * this one forgives a place that is a little off.
         */
        const val CLOSE_SIDE = 360

        /** The close look enlarges its square to about this many pixels a side... */
        const val CLOSE_TARGET = 1080

        /** ...but by no more than this factor. */
        const val CLOSE_MAX_FACTOR = 4

        /** Frames to keep looking at the place a code was last seen. */
        const val TRACK_FRAMES = 12

        private val HINTS: Map<DecodeHintType, Any> =
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
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
                // Never smaller than MIN_PATCH: a dotted or soft code lights up only in parts, and
                // a patch that holds half a code reads nothing.
                val side = Math.max((Math.max(x1 - x0, y1 - y0) + 1) * b * 3 / 2, MIN_PATCH)
                out.add(Roi(cx - side / 2, cy - side / 2, side, side))
            }
            return out
        }

        private const val BLOCK = 16
        private const val MIN_PATCH = 224
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
