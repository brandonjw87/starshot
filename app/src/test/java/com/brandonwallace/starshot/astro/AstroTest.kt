package com.brandonwallace.starshot.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** Pixel 10 Pro XL main camera approximation: 6.9mm f/1.68, ~9.8×7.4mm sensor, 4080×3072 binned RAW. */
val PIXEL_MAIN = LensInfo(6.9, 1.68, 9.8, 7.4, 4080, 3072)
val PIXEL_UW = LensInfo(2.0, 1.7, 6.4, 4.8, 4000, 3000)

class RulesTest {
    @Test fun npfForPixelMain() {
        assertEquals(2.4, PIXEL_MAIN.pixelPitchUm, 0.05)
        assertEquals((35 * 1.68 + 30 * 2.402) / 6.9, AstroMath.npf(PIXEL_MAIN), 0.05)
        assertTrue(AstroMath.npf(PIXEL_MAIN) in 18.0..20.0)
        assertTrue(AstroMath.npf(PIXEL_UW) > AstroMath.npf(PIXEL_MAIN) * 2)
    }
    @Test fun rule500UsesEquivalentFocal() {
        val eq = PIXEL_MAIN.equivFocalMm
        assertTrue(eq in 22.0..27.0)
        assertEquals(500.0 / eq, AstroMath.rule500(PIXEL_MAIN), 1e-9)
    }
    @Test fun npfLongerAtHighDeclination() {
        assertTrue(AstroMath.npf(PIXEL_MAIN, 60.0) > AstroMath.npf(PIXEL_MAIN, 0.0) * 1.9)
    }
    @Test fun driftIsSmallPerSecond() {
        val px = AstroMath.driftPixels(PIXEL_MAIN, 19.0)
        assertTrue("drift $px", px in 2.0..20.0)
    }
}

class AutoModeTest {
    private val longCaps = SensorCaps(10_000, 30_000_000_000, 50, 12800, 1600, true)
    private val shortCaps = SensorCaps(10_000, 1_000_000_000, 50, 12800, 1600, true)

    @Test fun darkSkyUsesNpfCapAndIsoCeiling() {
        val p = AutoMode.plan(longCaps, PIXEL_MAIN, Presets.MILKY_WAY, Metering(0.001, 1_000_000_000, 1600))
        assertEquals(AstroMath.npf(PIXEL_MAIN), p.exposureSec, 0.01)
        assertEquals(AutoMode.isoCeiling(longCaps), p.iso)
        assertTrue(p.align); assertEquals(Blend.SIGMA_CLIP, p.blend)
        assertEquals(kotlin.math.ceil(180 / p.exposureSec).toInt(), p.frames)
    }
    @Test fun sensorCapLimitsPerFrameAndAddsFrames() {
        val p = AutoMode.plan(shortCaps, PIXEL_MAIN, Presets.MILKY_WAY, null)
        assertEquals(1.0, p.exposureSec, 1e-6)
        assertEquals(180, p.frames)
        assertTrue(p.iso <= AutoMode.isoCeiling(shortCaps))
    }
    @Test fun brightSceneDropsIsoThenExposure() {
        val moon = Metering(0.5, 10_000_000, 100) // very bright
        val p = AutoMode.plan(longCaps, PIXEL_MAIN, Presets.MOON, moon)
        assertEquals(50, p.iso)
        assertTrue(p.exposureSec < 0.05)
        assertTrue(p.frames in 3..400)
    }
    @Test fun starTrailsNoAlignLighten() {
        val p = AutoMode.plan(longCaps, PIXEL_MAIN, Presets.STAR_TRAILS, null)
        assertEquals(false, p.align); assertEquals(Blend.LIGHTEN, p.blend)
        assertEquals(30.0, p.exposureSec, 1e-6)
        assertEquals(40, p.frames)
        assertTrue(p.darkFrames > 0)
    }
    @Test fun singleIsOneFrameAtSensorMax() {
        val p = AutoMode.plan(longCaps, PIXEL_MAIN, Presets.SINGLE, null)
        assertEquals(1, p.frames); assertEquals(30.0, p.exposureSec, 1e-6)
    }
    @Test fun timelapseInterval() {
        val p = AutoMode.plan(longCaps, PIXEL_MAIN, Presets.TIMELAPSE, null)
        assertTrue(p.intervalMs >= (p.exposureSec * 1000).toLong() + 1500)
        assertTrue(p.frames >= 2)
    }
    @Test fun isoCeilingWithoutAnalogInfo() {
        assertEquals(3200, AutoMode.isoCeiling(SensorCaps(1, 2, 100, 6400, 0, false)))
        assertEquals(800, AutoMode.isoCeiling(SensorCaps(1, 2, 100, 800, 0, false)))
    }
}

object Synth {
    fun field(rnd: Random, w: Int, h: Int, n: Int): List<Star> =
        List(n) { Star(rnd.nextDouble(10.0, w - 10.0), rnd.nextDouble(10.0, h - 10.0), rnd.nextDouble(0.3, 1.0)) }

    fun render(stars: List<Star>, w: Int, h: Int, t: Similarity, noise: Float, rnd: Random, bg: Float = 0.1f): FloatArray {
        val img = FloatArray(w * h) { bg + noise * rnd.nextGaussianF() }
        for (s in stars) {
            val x = t.mapX(s.x, s.y); val y = t.mapY(s.x, s.y)
            for (dy in -4..4) for (dx in -4..4) {
                val px = x.toInt() + dx; val py = y.toInt() + dy
                if (px !in 0 until w || py !in 0 until h) continue
                val r2 = (px - x) * (px - x) + (py - y) * (py - y)
                img[py * w + px] += (s.flux * exp(-r2 / (2 * 1.3 * 1.3))).toFloat()
            }
        }
        return img
    }
    private fun Random.nextGaussianF(): Float {
        val u = nextDouble(1e-12, 1.0); val v = nextDouble()
        return (sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)).toFloat()
    }
}

class AlignTest {
    private val w = 400; private val h = 300

    @Test fun detectsStarsAccurately() {
        val rnd = Random(1)
        val stars = Synth.field(rnd, w, h, 40)
        val img = Synth.render(stars, w, h, Similarity.IDENTITY, 0.01f, rnd)
        val found = StarDetector.detect(img, w, h)
        assertTrue("found ${found.size}", found.size >= 35)
        val brightest = stars.maxBy { it.flux }
        val near = found.minBy { (it.x - brightest.x) * (it.x - brightest.x) + (it.y - brightest.y) * (it.y - brightest.y) }
        assertEquals(brightest.x, near.x, 0.3); assertEquals(brightest.y, near.y, 0.3)
    }

    @Test fun hotPixelIsNotAStar() {
        val img = FloatArray(w * h) { 0.1f }
        img[150 * w + 200] = 1f
        assertEquals(0, StarDetector.detect(img, w, h).size)
    }

    @Test fun recoversKnownShiftAndRotation() {
        val rnd = Random(7)
        val stars = Synth.field(rnd, w, h, 60)
        val ref = StarDetector.detect(Synth.render(stars, w, h, Similarity.IDENTITY, 0.01f, rnd), w, h)
        val truth = Similarity.of(0.6, 7.3, -4.2)          // field moved by this
        val cur = StarDetector.detect(Synth.render(stars, w, h, truth, 0.01f, rnd), w, h)
        val r = Aligner.estimate(ref, cur)
        assertNotNull(r)
        val inv = truth.inverse()                             // cur → ref is the inverse
        assertEquals(inv.rotationDeg, r!!.transform.rotationDeg, 0.05)
        assertEquals(inv.tx, r.transform.tx, 0.3); assertEquals(inv.ty, r.transform.ty, 0.3)
        assertTrue(r.rmsPx < 0.3)
    }

    @Test fun largeDriftWorksWithHint() {
        val rnd = Random(9)
        val stars = Synth.field(rnd, w, h, 60)
        val ref = StarDetector.detect(Synth.render(stars, w, h, Similarity.IDENTITY, 0.01f, rnd), w, h)
        val truth = Similarity.of(1.5, 30.0, 12.0)
        val cur = StarDetector.detect(Synth.render(stars, w, h, truth, 0.01f, rnd), w, h)
        val hint = Similarity.of(-1.4, -29.0, -13.0)
        val r = Aligner.estimate(ref, cur, hint)!!
        val inv = truth.inverse()
        assertEquals(inv.rotationDeg, r.transform.rotationDeg, 0.05)
        assertEquals(inv.tx, r.transform.tx, 0.4)
    }

    @Test fun inverseRoundTrips() {
        val t = Similarity.of(12.0, 5.0, -3.0, 1.01)
        val i = t.inverse()
        val x = 123.0; val y = -45.0
        assertEquals(x, i.mapX(t.mapX(x, y), t.mapY(x, y)), 1e-9)
        assertEquals(y, i.mapY(t.mapX(x, y), t.mapY(x, y)), 1e-9)
    }
}

class StackTest {
    private val w = 200; private val h = 150

    private fun std(a: FloatArray, w: Int, h: Int, x0: Int, y0: Int, s: Int): Double {
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in y0 until y0 + s) for (x in x0 until x0 + s) { val v = a[y * w + x].toDouble(); if (!v.isNaN()) { sum += v; sq += v * v; n++ } }
        val m = sum / n; return sqrt(sq / n - m * m)
    }

    @Test fun alignedSigmaClipStackReducesNoiseAndKeepsStarsSharp() {
        val rnd = Random(3)
        val stars = Synth.field(rnd, w, h, 30)
        val noise = 0.05f
        val nFrames = 16
        val stacker = Stacker(w, h, 1, Blend.SIGMA_CLIP)
        var refStars: List<Star>? = null
        var hint = Similarity.IDENTITY
        val out = FloatArray(w * h)
        var single: FloatArray? = null
        for (k in 0 until nFrames) {
            val truth = Similarity.of(0.05 * k, 0.8 * k, 0.4 * k)
            val f = Synth.render(stars, w, h, truth, noise, rnd)
            if (k == 5) f[70 * w + 100] = 3f // satellite/cosmic-ray hit in one frame
            if (single == null) single = f
            val det = StarDetector.detect(f, w, h)
            if (refStars == null) { refStars = det; stacker.add(f); continue }
            val r = Aligner.estimate(refStars, det, hint)!!
            hint = r.transform
            Warp.warp(f, w, h, 1, r.transform, out)
            stacker.add(out)
        }
        val res = stacker.result()
        val n0 = std(single!!, w, h, 5, 5, 20)
        val n1 = std(res, w, h, 5, 5, 20)
        // ideal 1/sqrt(16) = 0.25 (bilinear resampling smooths a little more)
        assertTrue("noise $n0 -> $n1", n1 < n0 * 0.35)
        // star peak preserved (no trailing): brightest star peak within 25% of single-frame peak
        val b = stars.maxBy { it.flux }
        val peak0 = single[b.y.toInt() * w + b.x.toInt()]
        val peak1 = res[b.y.toInt() * w + b.x.toInt()]
        assertTrue("peak $peak0 -> $peak1", peak1 > peak0 * 0.7)
        // transient rejected (location in reference frame approx same since shifts are small)
        assertTrue(res.slice(69 * w + 95 until 71 * w + 105).all { it < 1f })
    }

    @Test fun lightenIsMaxAndMeanIsMean() {
        val a = floatArrayOf(0.1f, 0.5f, Float.NaN, 0.2f)
        val b = floatArrayOf(0.3f, 0.1f, 0.4f, 0.2f)
        val l = Stacker(2, 2, 1, Blend.LIGHTEN).apply { add(a); add(b) }.result()
        assertEquals(listOf(0.3f, 0.5f, 0.4f, 0.2f), l.toList())
        val m = Stacker(2, 2, 1, Blend.MEAN).apply { add(a); add(b) }.result()
        assertEquals(0.2f, m[0], 1e-6f); assertEquals(0.4f, m[2], 1e-6f)
    }

    @Test fun sigmaClipSpoolsToDisk() {
        val dir = createTempDir()
        val s = Stacker(4, 1, 1, Blend.SIGMA_CLIP, dir)
        repeat(9) { s.add(floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f)) }
        s.add(floatArrayOf(0.2f, 3.0f, 0.2f, 0.2f))
        val r = s.result()
        assertEquals(0.2f, r[1], 1e-3f)
        assertTrue(dir.listFiles()!!.isEmpty())
        dir.delete()
    }
}

class DevelopTest {
    @Test fun superpixelRggb() {
        // 2x2 RGGB block: R=1064 G=564/564 B=314 with black 64 white 1064
        val meta = RawMeta(2, 2, 0, floatArrayOf(64f, 64f, 64f, 64f), 1064f, floatArrayOf(2f, 1f, 1.5f))
        val raw = shortArrayOf(1064, 564, 564, 314)
        val rgb = RawDevelop.superpixel(raw, meta)
        assertEquals(2f, rgb[0], 1e-5f); assertEquals(0.5f, rgb[1], 1e-5f); assertEquals(0.375f, rgb[2], 1e-5f)
    }
    @Test fun bggrColorLookup() {
        assertEquals(2, RawDevelop.colorAt(3, 0, 0)); assertEquals(0, RawDevelop.colorAt(3, 1, 1))
        assertEquals(1, RawDevelop.colorAt(1, 0, 0)); assertEquals(0, RawDevelop.colorAt(1, 0, 1))
    }
    @Test fun hotPixelFixed() {
        val w = 8; val h = 8
        val raw = ShortArray(w * h) { 100 }
        raw[4 * w + 4] = 1000
        val meta = RawMeta(w, h, 0, floatArrayOf(64f, 64f, 64f, 64f), 1023f, floatArrayOf(1f, 1f, 1f))
        assertEquals(1, RawDevelop.fixHotPixels(raw, meta))
        assertEquals(100, raw[4 * w + 4].toInt())
    }
    @Test fun matrixMultiplyIdentity() {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(RawDevelop.XYZ_D50_TO_SRGB.toList(), RawDevelop.multiply(RawDevelop.XYZ_D50_TO_SRGB, id).toList())
    }
    @Test fun stretchMonotonicAndOpaque() {
        val w = 10; val h = 10; val p = w * h
        val rgb = FloatArray(p * 3) { (it % p) / p.toFloat() }
        val argb = Stretch.toArgb(rgb, w, h, 10f)
        assertTrue(argb.all { (it ushr 24) == 0xFF })
        assertTrue((argb[90] and 0xFF) >= (argb[10] and 0xFF))
    }
    @Test fun tiffHeaderAndSize() {
        val w = 3; val h = 2
        val rgb = FloatArray(w * h * 3) { 0.5f }
        val bos = ByteArrayOutputStream()
        TiffWriter.write16(bos, rgb, w, h, 6, "test description")
        val b = bos.toByteArray()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals('I'.code.toByte(), b[0]); assertEquals(42.toShort(), bb.getShort(2))
        assertEquals(16.toShort(), bb.getShort(8))
        // strip offset tag (index 6) value + data length = file size
        val stripOff = bb.getInt(10 + 6 * 12 + 8)
        assertEquals(stripOff + w * h * 6, b.size)
        assertEquals(32768, bb.getShort(stripOff).toInt() and 0xFFFF)
        assertTrue(abs(stripOff % 2) == 0)
    }
}
