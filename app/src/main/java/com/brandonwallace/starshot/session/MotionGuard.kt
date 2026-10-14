package com.brandonwallace.starshot.session

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Waits until the accelerometer reports the phone has been still for [windowMs]. */
class MotionGuard(ctx: Context) {
    private val sm = ctx.getSystemService(SensorManager::class.java)

    suspend fun awaitStill(windowMs: Long = 1500, timeoutMs: Long = 20_000, threshold: Float = 0.12f): Boolean {
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val samples = ArrayDeque<FloatArray>() // t(ms), x, y, z
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        val t = e.timestamp / 1_000_000f
                        samples.addLast(floatArrayOf(t, e.values[0], e.values[1], e.values[2]))
                        while (samples.isNotEmpty() && t - samples.first()[0] > windowMs) samples.removeFirst()
                        if (samples.size < 10 || t - samples.first()[0] < windowMs * 0.9f) return
                        var still = true
                        for (axis in 1..3) {
                            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                            for (s in samples) { lo = minOf(lo, s[axis]); hi = maxOf(hi, s[axis]) }
                            if (hi - lo > threshold) { still = false; break }
                        }
                        if (still) { sm.unregisterListener(this); if (cont.isActive) cont.resume(true) }
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        } ?: false
    }
}
