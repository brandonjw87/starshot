package com.brandonwallace.starshot.astro

import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Physical description of a lens + sensor readout used for star-trail limits. */
data class LensInfo(
    val focalMm: Double,
    val aperture: Double,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val pixelsWide: Int,
    val pixelsHigh: Int,
) {
    val pixelPitchUm: Double get() = sensorWidthMm * 1000.0 / pixelsWide
    val cropFactor: Double get() = 43.267 / hypot(sensorWidthMm, sensorHeightMm)
    val equivFocalMm: Double get() = focalMm * cropFactor
    val horizontalFovDeg: Double get() = Math.toDegrees(2 * atan(sensorWidthMm / (2 * focalMm)))
}

object AstroMath {
    /** Classic 500 rule (35mm-equivalent focal length). */
    fun rule500(lens: LensInfo): Double = 500.0 / lens.equivFocalMm

    /** Simplified NPF rule: t = (35·N + 30·p) / f, scaled by 1/cos(declination). */
    fun npf(lens: LensInfo, declinationDeg: Double = 0.0): Double {
        val t = (35.0 * lens.aperture + 30.0 * lens.pixelPitchUm) / lens.focalMm
        return t / max(0.2, cos(Math.toRadians(declinationDeg)))
    }

    /** Pixels an equatorial star drifts across the sensor in [seconds]. */
    fun driftPixels(lens: LensInfo, seconds: Double): Double {
        val degPerSec = 360.0 / 86164.0905
        return degPerSec * seconds * lens.pixelsWide / lens.horizontalFovDeg
    }
}

enum class ShootMode(val label: String) {
    STACK("Stack"), STAR_TRAILS("Star Trails"), SINGLE("Single Long"), TIMELAPSE("Timelapse")
}

enum class Blend { MEAN, SIGMA_CLIP, LIGHTEN }

data class SensorCaps(
    val minExposureNs: Long,
    val maxExposureNs: Long,
    val minIso: Int,
    val maxIso: Int,
    val maxAnalogIso: Int,
    val hasRaw: Boolean,
    val manualSensor: Boolean = true,
)

data class ShotPlan(
    val exposureNs: Long,
    val iso: Int,
    val frames: Int,
    val intervalMs: Long,
    val align: Boolean,
    val blend: Blend,
    val darkFrames: Int,
) {
    val exposureSec: Double get() = exposureNs / 1e9
    val totalSeconds: Double get() = frames * max(exposureSec, intervalMs / 1000.0)
}

/** Result of a metering frame: median linear signal (0..1 of full well) at a known exposure. */
data class Metering(val medianLinear: Double, val exposureNs: Long, val iso: Int)

data class Preset(
    val name: String,
    val mode: ShootMode,
    val targetTotalSec: Double,
    val targetBackground: Double,
    val stretch: Float,
    val intervalSec: Double = 0.0,
)

object Presets {
    val MILKY_WAY = Preset("Milky Way", ShootMode.STACK, 180.0, 0.12, 14f)
    val STAR_TRAILS = Preset("Star Trails", ShootMode.STAR_TRAILS, 1200.0, 0.10, 8f)
    val MOON = Preset("Moon", ShootMode.STACK, 4.0, 0.30, 1.2f)
    val CITY = Preset("City Lights", ShootMode.STACK, 60.0, 0.20, 3f)
    val SINGLE = Preset("Single Long", ShootMode.SINGLE, 0.0, 0.12, 8f)
    val TIMELAPSE = Preset("Timelapse", ShootMode.TIMELAPSE, 1800.0, 0.12, 10f, 20.0)
    val all = listOf(MILKY_WAY, STAR_TRAILS, MOON, CITY, SINGLE, TIMELAPSE)
}

object AutoMode {
    /** Brightness (signal per second per ISO) assumed for a dark rural sky when no metering is available. */
    const val DARK_SKY_BRIGHTNESS = 0.12 / (15.0 * 1600.0)

    /** Highest ISO worth using: a little past max analog gain; beyond that only digital gain (no SNR gain). */
    fun isoCeiling(caps: SensorCaps): Int {
        val pref = if (caps.maxAnalogIso > 0) max(caps.maxAnalogIso, 800) else 3200
        return min(pref, 6400).coerceIn(caps.minIso, caps.maxIso)
    }

    fun perFrameCapSec(caps: SensorCaps, lens: LensInfo, mode: ShootMode): Double {
        val sensorMax = caps.maxExposureNs / 1e9
        return when (mode) {
            ShootMode.STACK -> min(sensorMax, AstroMath.npf(lens))
            ShootMode.STAR_TRAILS, ShootMode.TIMELAPSE -> min(sensorMax, 30.0)
            ShootMode.SINGLE -> sensorMax
        }
    }

    /** Sigma clipping spools every frame (~19 MB each at Pixel half-res) to disk; above this, use a plain mean. */
    const val MAX_SIGMA_FRAMES = 60

    fun stackBlend(frames: Int): Blend = if (frames in 5..MAX_SIGMA_FRAMES) Blend.SIGMA_CLIP else Blend.MEAN

    fun brightness(m: Metering?): Double =
        if (m == null) DARK_SKY_BRIGHTNESS else max(m.medianLinear, 1e-6) / (m.exposureNs / 1e9 * m.iso)

    fun plan(caps: SensorCaps, lens: LensInfo, preset: Preset, meter: Metering?): ShotPlan {
        val b = brightness(meter)
        val minT = caps.minExposureNs / 1e9
        var t = perFrameCapSec(caps, lens, preset.mode)
        val isoCap = isoCeiling(caps)
        var iso = preset.targetBackground / (b * t)
        if (iso > isoCap) iso = isoCap.toDouble()
        if (iso < caps.minIso) {
            iso = caps.minIso.toDouble()
            t = (preset.targetBackground / (b * iso)).coerceIn(minT, t)
        }
        val isoI = iso.roundToInt().coerceIn(caps.minIso, caps.maxIso)
        val tNs = (t * 1e9).toLong().coerceIn(caps.minExposureNs, caps.maxExposureNs)
        val tS = tNs / 1e9
        return when (preset.mode) {
            ShootMode.STACK -> {
                val n = ceil(preset.targetTotalSec / tS).toInt().coerceIn(3, 400)
                ShotPlan(tNs, isoI, n, 0, true, stackBlend(n), 0)
            }
            ShootMode.STAR_TRAILS -> {
                val n = ceil(preset.targetTotalSec / tS).toInt().coerceIn(2, 3000)
                ShotPlan(tNs, isoI, n, 0, false, Blend.LIGHTEN, if (tS >= 1.0) 4 else 0)
            }
            ShootMode.SINGLE -> ShotPlan(tNs, isoI, 1, 0, false, Blend.MEAN, if (tS >= 1.0) 1 else 0)
            ShootMode.TIMELAPSE -> {
                val interval = max(preset.intervalSec * 1000.0, tS * 1000.0 + 1500.0).toLong()
                val n = ceil(preset.targetTotalSec * 1000.0 / interval).toInt().coerceIn(2, 5000)
                ShotPlan(tNs, isoI, n, interval, false, Blend.LIGHTEN, 0)
            }
        }
    }
}
