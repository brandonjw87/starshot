package com.brandonwallace.starshot.astro

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.asinh
import kotlin.math.max

/** cfa: 0=RGGB 1=GRBG 2=GBRG 3=BGGR (Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT). black indexed row*2+col. */
data class RawMeta(
    val width: Int, val height: Int, val cfa: Int,
    val black: FloatArray, val white: Float, val wbGains: FloatArray,
)

object RawDevelop {
    private val LUT = arrayOf(intArrayOf(0, 1, 1, 2), intArrayOf(1, 0, 2, 1), intArrayOf(1, 2, 0, 1), intArrayOf(2, 1, 1, 0))

    fun colorAt(cfa: Int, row: Int, col: Int): Int = if (cfa !in 0..3) 1 else LUT[cfa][(row and 1) * 2 + (col and 1)]

    /** Replace isolated hot pixels with the mean of their 4 same-colour neighbours. Returns count fixed. */
    fun fixHotPixels(raw: ShortArray, meta: RawMeta): Int {
        val w = meta.width; val h = meta.height
        val jump = 0.06f * (meta.white - meta.black.average().toFloat())
        var fixed = 0
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            val v = raw[i].toInt() and 0xFFFF
            val a = raw[i - 2].toInt() and 0xFFFF; val b = raw[i + 2].toInt() and 0xFFFF
            val c = raw[i - 2 * w].toInt() and 0xFFFF; val d = raw[i + 2 * w].toInt() and 0xFFFF
            val mx = max(max(a, b), max(c, d))
            val blk = meta.black[(y and 1) * 2 + (x and 1)]
            if (v - mx > jump && (v - blk) > 3f * (mx - blk)) { raw[i] = ((a + b + c + d) / 4).toShort(); fixed++ }
        }
        return fixed
    }

    /** 2×2 superpixel debayer → planar linear RGB at half resolution, black-subtracted, normalised, white-balanced. */
    fun superpixel(raw: ShortArray, meta: RawMeta): FloatArray {
        val w2 = meta.width / 2; val h2 = meta.height / 2; val plane = w2 * h2
        val out = FloatArray(plane * 3)
        val inv = FloatArray(4) { 1f / max(1f, meta.white - meta.black[it]) }
        val col = IntArray(4) { colorAt(meta.cfa, it shr 1, it and 1) }
        val g0 = meta.wbGains[0]; val g1 = meta.wbGains[1]; val g2 = meta.wbGains[2]
        for (y in 0 until h2) {
            for (x in 0 until w2) {
                var r = 0f; var g = 0f; var b = 0f
                for (s in 0..3) {
                    val i = (2 * y + (s shr 1)) * meta.width + 2 * x + (s and 1)
                    val v = ((raw[i].toInt() and 0xFFFF) - meta.black[s]) * inv[s]
                    when (col[s]) { 0 -> r += v; 1 -> g += 0.5f * v; else -> b += v }
                }
                val o = y * w2 + x
                out[o] = r * g0; out[plane + o] = g * g1; out[2 * plane + o] = b * g2
            }
        }
        return out
    }

    fun luminance(rgb: FloatArray, plane: Int): FloatArray =
        FloatArray(plane) { 0.25f * rgb[it] + 0.5f * rgb[plane + it] + 0.25f * rgb[2 * plane + it] }

    /** In-place 3×3 colour matrix (row-major) on planar RGB. */
    fun applyMatrix(rgb: FloatArray, plane: Int, m: FloatArray) {
        for (i in 0 until plane) {
            val r = rgb[i]; val g = rgb[plane + i]; val b = rgb[2 * plane + i]
            rgb[i] = m[0] * r + m[1] * g + m[2] * b
            rgb[plane + i] = m[3] * r + m[4] * g + m[5] * b
            rgb[2 * plane + i] = m[6] * r + m[7] * g + m[8] * b
        }
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /** XYZ(D50) → linear sRGB (Bradford adapted). */
    val XYZ_D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f,
    )

    fun medianOfSample(values: FloatArray, step: Int = 7): Double {
        val s = ArrayList<Float>(values.size / step + 1)
        var i = 0
        while (i < values.size) { if (!values[i].isNaN()) s += values[i]; i += step }
        if (s.isEmpty()) return 0.0
        s.sort(); return s[s.size / 2].toDouble()
    }
}

object Stretch {
    /** Linear planar RGB → display ARGB using a background-anchored asinh stretch that preserves colour ratios. */
    fun toArgb(rgb: FloatArray, w: Int, h: Int, strength: Float): IntArray {
        val plane = w * h
        val lum = RawDevelop.luminance(rgb, plane)
        val step = max(1, plane / 200_000)
        val s = ArrayList<Float>(plane / step + 1)
        var i = 0
        while (i < plane) { val v = lum[i]; if (!v.isNaN()) s += v; i += step }
        s.sort()
        val black = if (s.isEmpty()) 0f else s[(s.size * 0.02).toInt()]
        val top = if (s.isEmpty()) 1f else s[((s.size - 1) * 0.9995).toInt()]
        val white = max(1e-6f, top - black)
        val k = max(0.01f, strength)
        val norm = asinh(k)
        val out = IntArray(plane)
        for (p in 0 until plane) {
            val l = ((lum[p] - black) / white).coerceIn(0f, 1f)
            val f = if (l <= 1e-7f) 0f else asinh(k * l) / norm / l
            fun ch(v: Float) = (((v - black) / white * f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            out[p] = (0xFF shl 24) or (ch(rgb[p]) shl 16) or (ch(rgb[plane + p]) shl 8) or ch(rgb[2 * plane + p])
        }
        return out
    }
}

object TiffWriter {
    /** Baseline little-endian 16-bit/channel RGB TIFF, uncompressed, linear data 0..1 → 0..65535. */
    fun write16(out: OutputStream, rgb: FloatArray, w: Int, h: Int, orientation: Int = 1, description: String = "StarShot stack") {
        val desc = (description.padEnd(5) + "\u0000").toByteArray(Charsets.US_ASCII)
        val soft = "StarShot 1.0\u0000".toByteArray(Charsets.US_ASCII)
        val tags = 16
        var off = 8 + 2 + tags * 12 + 4
        val bpsOff = off; off += 6
        val xresOff = off; off += 8
        val yresOff = off; off += 8
        val descOff = off; off += desc.size
        val softOff = off; off += soft.size
        if (off % 2 == 1) off++
        val dataOff = off
        val bb = ByteBuffer.allocate(dataOff).order(ByteOrder.LITTLE_ENDIAN)
        bb.put('I'.code.toByte()); bb.put('I'.code.toByte()); bb.putShort(42); bb.putInt(8)
        bb.putShort(tags.toShort())
        fun tag(id: Int, type: Int, count: Int, value: Int) {
            bb.putShort(id.toShort()); bb.putShort(type.toShort()); bb.putInt(count)
            if (type == 3 && count == 1) { bb.putShort(value.toShort()); bb.putShort(0) } else bb.putInt(value)
        }
        tag(256, 4, 1, w); tag(257, 4, 1, h); tag(258, 3, 3, bpsOff); tag(259, 3, 1, 1); tag(262, 3, 1, 2)
        tag(270, 2, desc.size, descOff); tag(273, 4, 1, dataOff); tag(274, 3, 1, orientation); tag(277, 3, 1, 3)
        tag(278, 4, 1, h); tag(279, 4, 1, w * h * 6); tag(282, 5, 1, xresOff); tag(283, 5, 1, yresOff)
        tag(284, 3, 1, 1); tag(296, 3, 1, 2); tag(305, 2, soft.size, softOff)
        bb.putInt(0)
        repeat(3) { bb.putShort(16) }
        bb.putInt(300); bb.putInt(1); bb.putInt(300); bb.putInt(1)
        bb.put(desc); bb.put(soft)
        out.write(bb.array())
        val plane = w * h
        val row = ByteArray(w * 6)
        for (y in 0 until h) {
            var k = 0
            for (x in 0 until w) {
                val p = y * w + x
                for (c in 0 until 3) {
                    val v = rgb[c * plane + p]
                    val q = if (v.isNaN()) 0 else (v.coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
                    row[k++] = q.toByte(); row[k++] = (q shr 8).toByte()
                }
            }
            out.write(row)
        }
        out.flush()
    }
}
