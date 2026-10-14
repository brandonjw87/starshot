package com.brandonwallace.starshot.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import com.brandonwallace.starshot.astro.RawDevelop
import com.brandonwallace.starshot.astro.RawMeta
import java.nio.ByteOrder
import kotlin.math.pow

object FrameDecode {
    fun rawMeta(opt: CameraOption, result: CaptureResult, w: Int, h: Int): RawMeta {
        val ch = opt.chars
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size == 4 }?.copyOf()
            ?: ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p -> FloatArray(4) { p.getOffsetForIndex(it % 2, it / 2).toFloat() } }
            ?: FloatArray(4) { 64f }
        val white = (result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val gains = if (neutral != null && neutral.size == 3) {
            val n = FloatArray(3) { neutral[it].toFloat().coerceAtLeast(1e-3f) }
            FloatArray(3) { n[1] / n[it] }
        } else result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
            val g = (it.greenEven + it.greenOdd) / 2f
            floatArrayOf(it.red / g, 1f, it.blue / g)
        } ?: floatArrayOf(2f, 1f, 1.6f)
        return RawMeta(w, h, cfa, black, white, gains)
    }

    /** Forward matrix (WB'd camera → XYZ D50) composed with XYZ → linear sRGB, or null. */
    fun cameraToSrgb(opt: CameraOption): FloatArray? {
        val fm = opt.chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) ?: return null
        val m = FloatArray(9) { fm.getElement(it % 3, it / 3).toFloat() }
        return RawDevelop.multiply(RawDevelop.XYZ_D50_TO_SRGB, m)
    }

    fun rawToShorts(image: Image): ShortArray {
        val plane = image.planes[0]
        val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val w = image.width; val h = image.height
        val out = ShortArray(w * h)
        if (plane.rowStride == w * 2) { buf.rewind(); buf.asShortBuffer().get(out) }
        else for (y in 0 until h) { buf.position(y * plane.rowStride); buf.asShortBuffer().get(out, y * w, w) }
        return out
    }

    private val SRGB_LIN = FloatArray(256) { (it / 255.0).pow(2.2).toFloat() }

    /** YUV fallback (no RAW): half-res planar linear RGB. */
    fun yuvToRgbHalf(image: Image): Triple<FloatArray, Int, Int> {
        val w2 = image.width / 2; val h2 = image.height / 2; val plane = w2 * h2
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val out = FloatArray(plane * 3)
        for (y in 0 until h2) for (x in 0 until w2) {
            val r0 = 2 * y * yP.rowStride + 2 * x * yP.pixelStride
            val yy = ((yb.get(r0).toInt() and 0xFF) + (yb.get(r0 + yP.pixelStride).toInt() and 0xFF) +
                (yb.get(r0 + yP.rowStride).toInt() and 0xFF) + (yb.get(r0 + yP.rowStride + yP.pixelStride).toInt() and 0xFF)) / 4f
            val u = (ub.get(y * uP.rowStride + x * uP.pixelStride).toInt() and 0xFF) - 128f
            val v = (vb.get(y * vP.rowStride + x * vP.pixelStride).toInt() and 0xFF) - 128f
            val o = y * w2 + x
            out[o] = SRGB_LIN[(yy + 1.402f * v).toInt().coerceIn(0, 255)]
            out[plane + o] = SRGB_LIN[(yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)]
            out[2 * plane + o] = SRGB_LIN[(yy + 1.772f * u).toInt().coerceIn(0, 255)]
        }
        return Triple(out, w2, h2)
    }
}
