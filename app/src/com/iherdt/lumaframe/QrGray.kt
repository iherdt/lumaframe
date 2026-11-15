package com.iherdt.lumaframe

import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.DetectorResult
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.decoder.Decoder

/**
 * A second reading of a code whose corners ZXing found but whose content it could not decode.
 *
 * ZXing turns the picture black and white first and then picks one pixel per module. At two
 * or three camera pixels per module that throws most of the information away, and it is at its
 * worst on the dotted codes Google Photos shows: light dots on a dark sheet with gaps between
 * them, which soften into a low-contrast grey texture. Here the grey values are kept: each
 * module is measured at its centre (where a dot is brightest), compared with its neighbours
 * rather than with a block threshold, and the grid is tried at a few sub-module shifts because
 * corners found on a soft picture are rarely exact. Error correction then says which try is right.
 */
internal object QrGray {
    private val decoder = Decoder()

    /** Grid shifts in modules, most likely first. */
    private val SHIFTS = floatArrayOf(0f, 0.25f, -0.25f)

    /**
     * Read the code located by [det] from the grey picture [img]. [inverted] is true when the
     * code was found on the negative (a light-on-dark code). Returns the text, or null.
     */
    fun read(
        img: Luma,
        inverted: Boolean,
        det: DetectorResult,
    ): String? {
        val n = det.bits.width
        val p = det.points
        if (p.size < 3 || n < 21) return null
        val grey = FloatArray(n * n)
        val centres = FloatArray(2 * n)
        for (sy in SHIFTS) {
            for (sx in SHIFTS) {
                val t = transform(n, p, sx, sy)
                if (!sample(img, t, n, inverted, grey, centres)) continue
                for (mode in 0..2) {
                    try {
                        return decoder.decode(threshold(grey, n, mode)).text
                    } catch (e: ReaderException) {
                        // wrong grid or threshold; the next one may fit
                    }
                }
            }
        }
        return null
    }

    /**
     * The module grid to picture mapping, as ZXing's detector builds it from the three finder
     * centres (and the alignment pattern when it was found), shifted by ([sx], [sy]) modules.
     */
    private fun transform(
        n: Int,
        p: Array<com.google.zxing.ResultPoint>,
        sx: Float,
        sy: Float,
    ): PerspectiveTransform {
        val bl = p[0]
        val tl = p[1]
        val tr = p[2]
        val far = n - 3.5f
        val brX: Float
        val brY: Float
        val srcBr: Float
        if (p.size > 3) {
            brX = p[3].x
            brY = p[3].y
            srcBr = far - 3f
        } else {
            brX = tr.x - tl.x + bl.x
            brY = tr.y - tl.y + bl.y
            srcBr = far
        }
        return PerspectiveTransform.quadrilateralToQuadrilateral(
            3.5f - sx, 3.5f - sy, far - sx, 3.5f - sy, srcBr - sx, srcBr - sy, 3.5f - sx, far - sy,
            tl.x, tl.y, tr.x, tr.y, brX, brY, bl.x, bl.y,
        )
    }

    /** Darkness (0 light .. 255 dark) at every module centre; false if the grid leaves the picture. */
    private fun sample(
        img: Luma,
        t: PerspectiveTransform,
        n: Int,
        inverted: Boolean,
        out: FloatArray,
        row: FloatArray,
    ): Boolean {
        val d = img.data
        val w = img.w
        val maxX = img.w - 1.001f
        val maxY = img.h - 1.001f
        for (my in 0 until n) {
            for (mx in 0 until n) {
                row[2 * mx] = mx + 0.5f
                row[2 * mx + 1] = my + 0.5f
            }
            t.transformPoints(row)
            for (mx in 0 until n) {
                val x = row[2 * mx]
                val y = row[2 * mx + 1]
                if (x < -1f || y < -1f || x > img.w || y > img.h) return false
                val cx = Math.max(0f, Math.min(maxX, x))
                val cy = Math.max(0f, Math.min(maxY, y))
                val x0 = cx.toInt()
                val y0 = cy.toInt()
                val fx = cx - x0
                val fy = cy - y0
                val i = y0 * w + x0
                val a = d[i].toInt() and 0xFF
                val b = d[i + 1].toInt() and 0xFF
                val c = d[i + w].toInt() and 0xFF
                val e = d[i + w + 1].toInt() and 0xFF
                val v = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + e * fx) * fy
                // In the picture a normal code's modules are dark; an inverted code's are light.
                out[my * n + mx] = if (inverted) v else 255f - v
            }
        }
        return true
    }

    /**
     * Modules from darkness values. Mode 0 compares each module with the mean of its 7x7
     * neighbourhood, mode 1 with the middle of that neighbourhood's range (better where a
     * few modules stand alone), mode 2 with the mean of the whole code.
     */
    private fun threshold(
        g: FloatArray,
        n: Int,
        mode: Int,
    ): BitMatrix {
        val bits = BitMatrix(n)
        var all = 0f
        if (mode == 2) {
            for (v in g) all += v
            all /= g.size
        }
        val r = 3
        for (y in 0 until n) {
            for (x in 0 until n) {
                val v = g[y * n + x]
                val limit =
                    if (mode == 2) {
                        all
                    } else {
                        var sum = 0f
                        var cnt = 0
                        var lo = Float.MAX_VALUE
                        var hi = -Float.MAX_VALUE
                        for (j in Math.max(0, y - r)..Math.min(n - 1, y + r)) {
                            for (i in Math.max(0, x - r)..Math.min(n - 1, x + r)) {
                                val u = g[j * n + i]
                                sum += u
                                cnt++
                                if (u < lo) lo = u
                                if (u > hi) hi = u
                            }
                        }
                        if (mode == 0) sum / cnt else (lo + hi) / 2
                    }
                if (v > limit) bits.set(x, y)
            }
        }
        return bits
    }
}
