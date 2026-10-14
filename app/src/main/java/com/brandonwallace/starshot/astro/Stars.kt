package com.brandonwallace.starshot.astro

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Star(val x: Double, val y: Double, val flux: Double)

object StarDetector {
    /** Robust background (median) and noise (1.4826·MAD) from a subsample. */
    fun backgroundStats(img: FloatArray, w: Int, h: Int): Pair<Float, Float> {
        val n = w * h
        val step = max(1, n / 250_000)
        val s = FloatArray((n + step - 1) / step)
        var k = 0
        var i = 0
        while (i < n) { val v = img[i]; s[k++] = if (v.isNaN()) 0f else v; i += step }
        val arr = s.copyOf(k); arr.sort()
        val med = arr[k / 2]
        for (j in 0 until k) arr[j] = abs(arr[j] - med)
        arr.sort()
        return med to max(1.4826f * arr[k / 2], 1e-6f)
    }

    /** Detect point sources: local maxima above bg + k·σ with multi-pixel support (rejects hot pixels). */
    fun detect(img: FloatArray, w: Int, h: Int, maxStars: Int = 80, k: Float = 5f): List<Star> {
        val (bg, sd) = backgroundStats(img, w, h)
        val thr = bg + k * sd
        val low = bg + 2f * sd
        val out = ArrayList<Star>()
        for (y in 3 until h - 3) {
            val row = y * w
            for (x in 3 until w - 3) {
                val idx = row + x
                val v = img[idx]
                if (!(v > thr)) continue
                var isMax = true
                var support = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val j = idx + dy * w + dx
                    val nv = img[j]
                    if (nv > v || (nv == v && j < idx)) isMax = false
                    if (nv > low) support++
                }
                if (!isMax || support < 2) continue
                var sx = 0.0; var sy = 0.0; var sum = 0.0
                for (dy in -2..2) for (dx in -2..2) {
                    val p = img[idx + dy * w + dx] - bg
                    if (p > 0) { sx += p * dx; sy += p * dy; sum += p }
                }
                if (sum <= 0) continue
                out.add(Star(x + sx / sum, y + sy / sum, sum))
            }
        }
        out.sortByDescending { it.flux }
        return if (out.size > maxStars) out.subList(0, maxStars).toList() else out
    }
}

/** x' = a·x − b·y + tx ; y' = b·x + a·y + ty  (rotation + uniform scale + translation). */
data class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double) {
    fun mapX(x: Double, y: Double) = a * x - b * y + tx
    fun mapY(x: Double, y: Double) = b * x + a * y + ty
    val scale: Double get() = hypot(a, b)
    val rotationDeg: Double get() = Math.toDegrees(atan2(b, a))
    fun inverse(): Similarity {
        val d = a * a + b * b
        return Similarity(a / d, -b / d, -(a * tx + b * ty) / d, (b * tx - a * ty) / d)
    }
    companion object {
        val IDENTITY = Similarity(1.0, 0.0, 0.0, 0.0)
        fun of(rotDeg: Double, dx: Double, dy: Double, scale: Double = 1.0): Similarity {
            val r = Math.toRadians(rotDeg)
            return Similarity(scale * kotlin.math.cos(r), scale * kotlin.math.sin(r), dx, dy)
        }
    }
}

data class AlignResult(val transform: Similarity, val inliers: Int, val rmsPx: Double)

/** Star-field registration: translation voting (seeded with the previous transform) then iterative LSQ similarity fit. */
object Aligner {
    fun estimate(
        ref: List<Star>, cur: List<Star>, hint: Similarity = Similarity.IDENTITY,
        tol: Double = 3.0, minInliers: Int = 5,
    ): AlignResult? {
        if (ref.size < 3 || cur.size < 3) return null
        val need = min(minInliers, min(ref.size, cur.size))
        var bestT = hint
        var best = -1
        for (r in ref.take(25)) for (c in cur.take(25)) {
            val t = hint.copy(tx = hint.tx + r.x - hint.mapX(c.x, c.y), ty = hint.ty + r.y - hint.mapY(c.x, c.y))
            val cnt = countMatches(t, ref, cur, tol)
            if (cnt > best) { best = cnt; bestT = t }
        }
        if (best < need) return null
        var t = bestT
        repeat(5) {
            val pairs = match(t, ref, cur, tol)
            if (pairs.size < need) return null
            t = fit(pairs)
        }
        val pairs = match(t, ref, cur, tol)
        if (pairs.size < need || abs(t.scale - 1.0) > 0.03) return null
        var se = 0.0
        for ((c, r) in pairs) { val dx = t.mapX(c.x, c.y) - r.x; val dy = t.mapY(c.x, c.y) - r.y; se += dx * dx + dy * dy }
        return AlignResult(t, pairs.size, sqrt(se / pairs.size))
    }

    private fun countMatches(t: Similarity, ref: List<Star>, cur: List<Star>, tol: Double): Int {
        val tol2 = tol * tol
        var n = 0
        for (c in cur) {
            val x = t.mapX(c.x, c.y); val y = t.mapY(c.x, c.y)
            for (r in ref) { val dx = r.x - x; val dy = r.y - y; if (dx * dx + dy * dy <= tol2) { n++; break } }
        }
        return n
    }

    fun match(t: Similarity, ref: List<Star>, cur: List<Star>, tol: Double): List<Pair<Star, Star>> {
        val used = HashSet<Int>()
        val out = ArrayList<Pair<Star, Star>>()
        val tol2 = tol * tol
        for (c in cur) {
            val x = t.mapX(c.x, c.y); val y = t.mapY(c.x, c.y)
            var bi = -1; var bd = tol2
            for ((i, r) in ref.withIndex()) {
                val dx = r.x - x; val dy = r.y - y; val d = dx * dx + dy * dy
                if (d <= bd && i !in used) { bd = d; bi = i }
            }
            if (bi >= 0) { used += bi; out += c to ref[bi] }
        }
        return out
    }

    /** Closed-form least-squares similarity mapping first (cur) → second (ref). */
    fun fit(pairs: List<Pair<Star, Star>>): Similarity {
        val n = pairs.size.toDouble()
        val mcx = pairs.sumOf { it.first.x } / n; val mcy = pairs.sumOf { it.first.y } / n
        val mrx = pairs.sumOf { it.second.x } / n; val mry = pairs.sumOf { it.second.y } / n
        var n1 = 0.0; var n2 = 0.0; var den = 0.0
        for ((c, r) in pairs) {
            val xc = c.x - mcx; val yc = c.y - mcy; val xr = r.x - mrx; val yr = r.y - mry
            n1 += xc * xr + yc * yr; n2 += xc * yr - yc * xr; den += xc * xc + yc * yc
        }
        if (den <= 0) return Similarity(1.0, 0.0, mrx - mcx, mry - mcy)
        val a = n1 / den; val b = n2 / den
        return Similarity(a, b, mrx - (a * mcx - b * mcy), mry - (b * mcx + a * mcy))
    }
}

object Warp {
    /** Resample planar image so out(x,y) = src(T⁻¹(x,y)), T maps src → reference coords. NaN where undefined. */
    fun warp(src: FloatArray, w: Int, h: Int, ch: Int, srcToRef: Similarity, out: FloatArray) {
        val inv = srcToRef.inverse()
        val plane = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = inv.a * x - inv.b * y + inv.tx
                val sy = inv.b * x + inv.a * y + inv.ty
                val o = y * w + x
                if (sx < 0 || sy < 0 || sx > w - 1 || sy > h - 1) {
                    for (c in 0 until ch) out[c * plane + o] = Float.NaN
                    continue
                }
                val x0 = sx.toInt(); val y0 = sy.toInt()
                val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
                val fx = (sx - x0).toFloat(); val fy = (sy - y0).toFloat()
                val i00 = y0 * w + x0; val i01 = y0 * w + x1; val i10 = y1 * w + x0; val i11 = y1 * w + x1
                for (c in 0 until ch) {
                    val b = c * plane
                    out[b + o] = (src[b + i00] * (1 - fx) + src[b + i01] * fx) * (1 - fy) +
                        (src[b + i10] * (1 - fx) + src[b + i11] * fx) * fy
                }
            }
        }
    }
}
