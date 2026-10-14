package com.brandonwallace.starshot.astro

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Streaming stacker for planar float frames. MEAN and LIGHTEN keep only accumulators.
 * SIGMA_CLIP keeps sum/sum² and spools 16-bit copies of frames to [scratchDir] (or memory) for a second clipping pass.
 * NaN pixels (outside the aligned area) are ignored.
 */
class Stacker(
    val w: Int, val h: Int, val ch: Int, val blend: Blend,
    private val scratchDir: File? = null, private val kappa: Float = 2.5f,
) {
    private val plane = w * h
    private val n = plane * ch
    private val acc = FloatArray(n) { if (blend == Blend.LIGHTEN) Float.NEGATIVE_INFINITY else 0f }
    private val acc2: FloatArray? = if (blend == Blend.SIGMA_CLIP) FloatArray(n) else null
    private val count = ShortArray(plane)
    private val mem = ArrayList<ByteArray>()
    private var spool: File? = null
    private var spoolOut: BufferedOutputStream? = null
    var frames = 0; private set

    fun add(frame: FloatArray) {
        require(frame.size == n) { "frame size mismatch" }
        for (p in 0 until plane) if (!frame[p].isNaN() && count[p] < Short.MAX_VALUE) count[p]++
        for (i in 0 until n) {
            val v = frame[i]
            if (v.isNaN()) continue
            if (blend == Blend.LIGHTEN) { if (v > acc[i]) acc[i] = v } else {
                acc[i] += v
                acc2?.let { it[i] += v * v }
            }
        }
        if (blend == Blend.SIGMA_CLIP) store(frame)
        frames++
    }

    private fun store(frame: FloatArray) {
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = frame[i]
            val code = if (v.isNaN()) 0xFFFF else (v.coerceIn(0f, RANGE) / RANGE * 65534f + 0.5f).toInt()
            bytes[2 * i] = (code shr 8).toByte(); bytes[2 * i + 1] = code.toByte()
        }
        val dir = scratchDir
        if (dir == null) { mem += bytes; return }
        if (spoolOut == null) {
            dir.mkdirs()
            spool = File.createTempFile("stack", ".bin", dir)
            spoolOut = BufferedOutputStream(FileOutputStream(spool!!), 1 shl 20)
        }
        spoolOut!!.write(bytes)
    }

    private inline fun forEachStored(block: (ByteArray) -> Unit) {
        if (scratchDir == null) { mem.forEach(block); return }
        spoolOut?.close(); spoolOut = null
        val buf = ByteArray(n * 2)
        DataInputStream(FileInputStream(spool!!).buffered(1 shl 20)).use { inp ->
            repeat(frames) { inp.readFully(buf); block(buf) }
        }
    }

    fun result(): FloatArray {
        val out = FloatArray(n)
        when {
            blend == Blend.LIGHTEN -> for (i in 0 until n) out[i] = if (acc[i] == Float.NEGATIVE_INFINITY) 0f else acc[i]
            blend == Blend.MEAN || frames < 3 -> meanInto(out)
            else -> {
                val sq = acc2!!
                for (i in 0 until n) {
                    val c = count[i % plane].toInt()
                    if (c == 0) { acc[i] = 0f; sq[i] = 0f; continue }
                    val m = acc[i] / c
                    acc[i] = m
                    sq[i] = sqrt(max(0f, sq[i] / c - m * m))
                }
                val cs = FloatArray(n)
                val cc = ShortArray(n)
                forEachStored { bytes ->
                    for (i in 0 until n) {
                        val code = ((bytes[2 * i].toInt() and 0xFF) shl 8) or (bytes[2 * i + 1].toInt() and 0xFF)
                        if (code == 0xFFFF) continue
                        val v = code / 65534f * RANGE
                        if (abs(v - acc[i]) <= kappa * sq[i] + 1e-4f) { cs[i] += v; cc[i]++ }
                    }
                }
                for (i in 0 until n) out[i] = if (cc[i] > 0) cs[i] / cc[i] else acc[i]
            }
        }
        close()
        return out
    }

    private fun meanInto(out: FloatArray) {
        for (i in 0 until n) { val c = count[i % plane].toInt(); out[i] = if (c > 0) acc[i] / c else 0f }
    }

    fun close() {
        spoolOut?.close(); spoolOut = null
        spool?.delete(); spool = null
        mem.clear()
    }

    companion object { const val RANGE = 4f }
}
