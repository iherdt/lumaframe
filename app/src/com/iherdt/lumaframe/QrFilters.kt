package com.iherdt.lumaframe

/**
 * The picture operations the scanner's search applies before decoding: cut out, shrink,
 * enlarge, sharpen, and the two corrections for a glaring phone screen ([shrinkBright] and
 * [curve]). All work on grey pictures ([Luma]) and return new ones.
 */
internal object QrFilters {
    fun crop(
        src: Luma,
        r: Roi,
    ): Luma {
        // A cut-out as large as the picture is the picture.
        if (r.w == src.w && r.h == src.h) return src
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

    /**
     * Undo some lens softness: add back a multiple of the difference between the picture
     * and a box-blurred copy of it (an unsharp mask). The fixed-focus lens is set for
     * people across a room, so a phone held close is always a little out of focus. The
     * mild setting reads the smallest codes, the [strong] one the softest pictures.
     */
    fun sharpen(
        src: Luma,
        strong: Boolean,
    ): Luma = if (strong) sharpen(src, 6, 16) else sharpen(src, 4, 6)

    /** Unsharp mask with a box of radius [r] and a gain of [gain2] / 2. */
    fun sharpen(
        src: Luma,
        r: Int,
        gain2: Int,
    ): Luma {
        val w = src.w
        val h = src.h
        if (w <= 2 * r + 1 || h <= 2 * r + 1) return src
        val d = src.data
        val tmp = IntArray(w * h) // horizontal box sums
        val span = 2 * r + 1
        for (y in 0 until h) {
            val o = y * w
            var s = 0
            for (x in -r..r) s += d[o + Math.max(0, x)].toInt() and 0xFF
            for (x in 0 until w) {
                tmp[o + x] = s
                val entering = d[o + Math.min(w - 1, x + r + 1)].toInt() and 0xFF
                val leaving = d[o + Math.max(0, x - r)].toInt() and 0xFF
                s += entering - leaving
            }
        }
        val out = ByteArray(w * h)
        val col = IntArray(w)
        for (y in -r..r) {
            val o = Math.max(0, y) * w
            for (x in 0 until w) col[x] += tmp[o + x]
        }
        val area = span * span
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                val v = d[o + x].toInt() and 0xFF
                val blur = col[x] / area
                val sharp = v + (v - blur) * gain2 / 2
                out[o + x] = Math.max(0, Math.min(255, sharp)).toByte()
            }
            val add = Math.min(h - 1, y + r + 1) * w
            val sub = Math.max(0, y - r) * w
            for (x in 0 until w) col[x] += tmp[add + x] - tmp[sub + x]
        }
        return Luma(out, w, h)
    }

    /**
     * Shrink the bright parts of [src] by [r] pixels (a grey erosion: each pixel becomes the
     * darkest value within [r]). A phone screen is far brighter than the room the camera
     * exposes for, so every light dot of a light-on-dark code blooms into its neighbours
     * and the gaps between dots close up. This opens them again.
     */
    fun shrinkBright(
        src: Luma,
        r: Int,
    ): Luma {
        if (r <= 0) return src
        val w = src.w
        val h = src.h
        if (r == 1 && w > 2 && h > 2) return shrinkBright1(src.data, w, h)
        // Rows, then columns. Each line is a running minimum over 2r+1 values, done with
        // the van Herk / Gil-Werman scheme (minima forward and backward within blocks of
        // 2r+1), which costs three comparisons per pixel whatever the radius.
        val rows = ByteArray(w * h)
        minAlongLines(src.data, rows, h, w, w, 1, r)
        val out = ByteArray(w * h)
        minAlongLines(rows, out, w, h, 1, w, r)
        return Luma(out, w, h)
    }

    /**
     * Running minimum of radius [r] along each of [lines] lines of [len] values. Line k starts
     * at k * [lineStep] and its values are [step] apart, so the same code does rows and columns.
     */
    private fun minAlongLines(
        src: ByteArray,
        dst: ByteArray,
        lines: Int,
        len: Int,
        lineStep: Int,
        step: Int,
        r: Int,
    ) {
        val span = 2 * r + 1
        val n = len + 2 * r
        // The line padded with white on both ends, so the edges are not darkened.
        val line = IntArray(n) { 255 }
        val fwd = IntArray(n)
        val bwd = IntArray(n)
        for (k in 0 until lines) {
            val o = k * lineStep
            for (i in 0 until len) line[r + i] = src[o + i * step].toInt() and 0xFF
            // Block minima: fwd from the start of each block of span up to the position, bwd
            // from the position to the block's end. The minimum of the window starting at i
            // is then min(bwd[i], fwd[i + span - 1]).
            for (i in 0 until n) {
                fwd[i] = if (i % span == 0) line[i] else Math.min(fwd[i - 1], line[i])
            }
            for (i in n - 1 downTo 0) {
                bwd[i] = if (i == n - 1 || (i + 1) % span == 0) line[i] else Math.min(bwd[i + 1], line[i])
            }
            for (i in 0 until len) dst[o + i * step] = Math.min(bwd[i], fwd[i + span - 1]).toByte()
        }
    }

    /** [shrinkBright] by one pixel, written out flat because it runs on every frame. */
    private fun shrinkBright1(
        d: ByteArray,
        w: Int,
        h: Int,
    ): Luma {
        val tmp = ByteArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            var a = d[o].toInt() and 0xFF
            var b = a
            for (x in 0 until w) {
                val c = d[o + if (x < w - 1) x + 1 else x].toInt() and 0xFF
                var m = if (a < b) a else b
                if (c < m) m = c
                tmp[o + x] = m.toByte()
                a = b
                b = c
            }
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            val up = (if (y > 0) y - 1 else y) * w
            val dn = (if (y < h - 1) y + 1 else y) * w
            for (x in 0 until w) {
                val a = tmp[up + x].toInt() and 0xFF
                val b = tmp[o + x].toInt() and 0xFF
                val c = tmp[dn + x].toInt() and 0xFF
                var m = if (a < b) a else b
                if (c < m) m = c
                out[o + x] = m.toByte()
            }
        }
        return Luma(out, w, h)
    }

    /**
     * A steep tone curve (the cube of the brightness): everything but the brightest parts
     * goes dark. On a glaring screen the glow between two dots is a little less bright than
     * the dots themselves, and this turns that small difference into a clear gap.
     */
    fun curve(src: Luma): Luma {
        val n = src.w * src.h
        val out = ByteArray(n)
        val d = src.data
        for (i in 0 until n) out[i] = CUBE[d[i].toInt() and 0xFF]
        return Luma(out, src.w, src.h)
    }

    private val CUBE = ByteArray(256) { (it * it * it / (255 * 255)).toByte() }

    /** Enlarge [src] by the whole factor [f] with bilinear interpolation. */
    fun enlarge(
        src: Luma,
        f: Int,
    ): Luma {
        if (f <= 1) return src
        if (f == 2) return enlarge2(src)
        val w = src.w * f
        val h = src.h * f
        val den = 2 * f
        // Sample position of output x in source pixels: (x + 0.5) / f - 0.5, in units of 1 / (2f).
        val x0 = IntArray(w)
        val fx = IntArray(w)
        for (x in 0 until w) {
            val q = Math.max(0, Math.min((src.w - 1) * den, 2 * x + 1 - f))
            x0[x] = q / den
            fx[x] = q % den
        }
        val out = ByteArray(w * h)
        val d = src.data
        val half = den * den / 2
        val full = den * den
        for (y in 0 until h) {
            val q = Math.max(0, Math.min((src.h - 1) * den, 2 * y + 1 - f))
            val r0 = (q / den) * src.w
            val r1 = Math.min(src.h - 1, q / den + 1) * src.w
            val fy = q % den
            val o = y * w
            for (x in 0 until w) {
                val a0 = x0[x]
                val a1 = Math.min(src.w - 1, a0 + 1)
                val wx = fx[x]
                val top = (d[r0 + a0].toInt() and 0xFF) * (den - wx) + (d[r0 + a1].toInt() and 0xFF) * wx
                val bot = (d[r1 + a0].toInt() and 0xFF) * (den - wx) + (d[r1 + a1].toInt() and 0xFF) * wx
                out[o + x] = ((top * (den - fy) + bot * fy + half) / full).toByte()
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
}
