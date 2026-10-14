package com.brandonwallace.starshot.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.location.Location
import android.media.ExifInterface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.brandonwallace.starshot.astro.Aligner
import com.brandonwallace.starshot.astro.AutoMode
import com.brandonwallace.starshot.astro.Blend
import com.brandonwallace.starshot.astro.Metering
import com.brandonwallace.starshot.astro.Preset
import com.brandonwallace.starshot.astro.RawDevelop
import com.brandonwallace.starshot.astro.ShootMode
import com.brandonwallace.starshot.astro.ShotPlan
import com.brandonwallace.starshot.astro.Similarity
import com.brandonwallace.starshot.astro.Stacker
import com.brandonwallace.starshot.astro.Star
import com.brandonwallace.starshot.astro.StarDetector
import com.brandonwallace.starshot.astro.Stretch
import com.brandonwallace.starshot.astro.TiffWriter
import com.brandonwallace.starshot.astro.Warp
import com.brandonwallace.starshot.camera.CameraOption
import com.brandonwallace.starshot.camera.Frame
import com.brandonwallace.starshot.camera.FrameDecode
import com.brandonwallace.starshot.camera.StarCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

data class SessionConfig(
    val option: CameraOption,
    val preset: Preset,
    val auto: Boolean,
    val manualExposureNs: Long,
    val manualIso: Int,
    val manualFrames: Int,
    val intervalSec: Double,
    val countdownSec: Int,
    val voice: Boolean,
    val vibrate: Boolean,
    val maxVolume: Boolean,
    val motionGuard: Boolean,
    val darkFrames: Boolean,
    val keepFrames: Boolean,
    val location: Location?,
    val focusDiopters: Float,
)

sealed interface Phase {
    data object Idle : Phase
    data class Countdown(val sec: Int, val label: String) : Phase
    data class Waiting(val text: String) : Phase
    data class Capturing(val frame: Int, val total: Int, val exposureSec: Double, val iso: Int, val info: String, val startedAt: Long, val totalSec: Double) : Phase
    data class Processing(val text: String) : Phase
    data class Done(val summary: String, val preview: Uri?, val folder: String) : Phase
    data class Failed(val message: String) : Phase
}

/** A developed frame (half-res planar linear RGB) or a raw frame still to be developed on the worker. */
private class DevJob(val raw: ShortArray?, val meta: com.brandonwallace.starshot.astro.RawMeta?, val rgb: FloatArray?, val w: Int, val h: Int, val index: Int)

/** Alignment + stacking of developed frames. */
class StackPipeline(val w: Int, val h: Int, private val plan: ShotPlan, scratch: File) {
    val stacker = Stacker(w, h, 3, plan.blend, if (plan.blend == Blend.SIGMA_CLIP) scratch else null)
    private var refStars: List<Star>? = null
    private var last = Similarity.IDENTITY
    private val warped = if (plan.align) FloatArray(w * h * 3) else null
    var aligned = 0; private set
    var failed = 0; private set
    var info = ""; private set

    fun add(rgb: FloatArray) {
        if (!plan.align) { stacker.add(rgb); info = "${stacker.frames} stacked"; return }
        val stars = StarDetector.detect(RawDevelop.luminance(rgb, w * h), w, h, 80)
        val ref = refStars
        if (ref == null) {
            if (stars.size >= 8) refStars = stars
            stacker.add(rgb); info = "${stars.size} stars in reference"; return
        }
        val r = Aligner.estimate(ref, stars, last)
        if (r != null) { last = r.transform; aligned++; info = "${r.inliers} stars matched, rot %.2f°".format(r.transform.rotationDeg) }
        else { failed++; info = "alignment missed (using previous)" }
        Warp.warp(rgb, w, h, 3, last, warped!!)
        stacker.add(warped)
    }
}

object SessionRunner {
    private const val TAG = "StarShot"
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null
    @Volatile private var camera: StarCamera? = null

    fun camera(ctx: Context): StarCamera = camera ?: synchronized(this) { camera ?: StarCamera(ctx).also { camera = it } }
    val isIdle: Boolean get() = job?.isActive != true

    fun start(ctx: Context, cfg: SessionConfig) {
        if (!isIdle) return
        val app = ctx.applicationContext
        job = scope.launch { run(app, camera(app), cfg) }
    }

    fun cancel() { job?.cancel() }
    fun reset() { if (isIdle) _phase.value = Phase.Idle }

    fun thermalStatus(ctx: Context): Int = ctx.getSystemService(PowerManager::class.java).currentThermalStatus
    fun batteryPct(ctx: Context): Int {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (l < 0) -1 else l * 100 / s
    }

    fun manualPlan(cfg: SessionConfig): ShotPlan {
        val mode = cfg.preset.mode
        val exp = cfg.manualExposureNs.coerceIn(cfg.option.caps.minExposureNs, cfg.option.caps.maxExposureNs)
        val frames = if (mode == ShootMode.SINGLE) 1 else cfg.manualFrames.coerceAtLeast(1)
        val interval = if (mode == ShootMode.TIMELAPSE) max((cfg.intervalSec * 1000).toLong(), exp / 1_000_000 + 1500) else 0L
        val blend = when (mode) { ShootMode.STACK -> AutoMode.stackBlend(frames); ShootMode.SINGLE -> Blend.MEAN; else -> Blend.LIGHTEN }
        return ShotPlan(exp, cfg.manualIso, frames, interval, mode == ShootMode.STACK, blend, if (mode == ShootMode.SINGLE) 1 else 4)
    }

    private fun setPhase(p: Phase) { _phase.value = p }

    private suspend fun run(ctx: Context, cam: StarCamera, cfg: SessionConfig) {
        val opt = cfg.option
        val ann = Announcer(ctx, cfg.voice, cfg.vibrate, cfg.maxVolume)
        CaptureService.start(ctx)
        val scratch = File(ctx.cacheDir, "stack").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        try {
            ann.init()
            val isRaw = cam.prepareCapture(opt)
            for (s in cfg.countdownSec downTo 1) { setPhase(Phase.Countdown(s, "Set the phone down, lens to the sky")); ann.tick(s); delay(1000) }
            ann.go()
            delay(1200) // let the vibration and any bump die out
            if (cfg.motionGuard) {
                setPhase(Phase.Waiting("Motion guard: waiting for the phone to be still"))
                if (!MotionGuard(ctx).awaitStill()) Log.w(TAG, "Motion guard timed out; shooting anyway")
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val store = SessionStore(ctx, "${stamp}_${cfg.preset.name.replace(" ", "")}")

            val plan0 = if (cfg.auto) {
                setPhase(Phase.Waiting("Metering the sky"))
                AutoMode.plan(opt.caps, opt.lens, cfg.preset, meter(cam, opt, cfg))
            } else manualPlan(cfg)
            val darkCount = if (cfg.darkFrames && !plan0.align && cfg.preset.mode != ShootMode.TIMELAPSE) max(plan0.darkFrames, 1) else 0
            val plan = plan0.copy(darkFrames = darkCount)
            Log.i(TAG, "Plan $plan raw=$isRaw camera=${opt.label}")

            var pipeline: StackPipeline? = null
            var firstMeta: com.brandonwallace.starshot.astro.RawMeta? = null
            var actualExp = plan.exposureNs; var actualIso = plan.iso
            var shot = 0
            val start = SystemClock.elapsedRealtime()
            var stopReason: String? = null
            val keptFrames = ArrayList<Uri>()

            coroutineScope {
                val ch = Channel<DevJob>(capacity = 2)
                val worker = launch(Dispatchers.Default) {
                    for (j in ch) {
                        val (rgb, w, h) = develop(j)
                        val p = pipeline ?: StackPipeline(w, h, plan, scratch).also { pipeline = it }
                        p.add(rgb)
                        if (cfg.preset.mode == ShootMode.TIMELAPSE) saveJpeg(store, rgb, w, h, cfg, opt, plan, 1, "tl_%05d.jpg".format(j.index + 1), null, isRaw)
                    }
                }
                for (i in 0 until plan.frames) {
                    ensureActive()
                    val thermal = thermalStatus(ctx)
                    if (thermal >= PowerManager.THERMAL_STATUS_CRITICAL) { stopReason = "phone too hot"; break }
                    val batt = batteryPct(ctx)
                    if (batt in 0..3) { stopReason = "battery critically low"; break }
                    val t0 = SystemClock.elapsedRealtime()
                    setPhase(Phase.Capturing(i + 1, plan.frames, plan.exposureSec, plan.iso, pipeline?.info ?: "", start, plan.totalSeconds))
                    CaptureService.update(ctx, "Frame ${i + 1}/${plan.frames}")
                    val job = cam.capture(plan.exposureNs, plan.iso, cfg.focusDiopters).use { f ->
                        if (i == 0) {
                            f.result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { actualExp = it }
                            f.result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { actualIso = it }
                        }
                        toJob(f, opt, i, store, cfg, plan, keptFrames).also { if (it.meta != null && firstMeta == null) firstMeta = it.meta }
                    }
                    ch.send(job)
                    shot++
                    if (plan.intervalMs > 0 && i < plan.frames - 1) {
                        val wait = plan.intervalMs - (SystemClock.elapsedRealtime() - t0)
                        if (wait > 0) delay(wait)
                    }
                }
                ch.close()
                setPhase(Phase.Processing("Finishing alignment…"))
                worker.join()
            }

            // Dark frames (Single / Star Trails only): subtracted from the final result.
            var masterDark: FloatArray? = null
            if (plan.darkFrames > 0 && shot > 0) {
                ann.say("Lights done. Cover the lens now for dark frames.")
                for (s in 15 downTo 1) { setPhase(Phase.Countdown(s, "Cover the lens for dark frames")); ann.tick(s, speak = s <= 5); delay(1000) }
                val pl = pipeline!!
                val ds = Stacker(pl.w, pl.h, 3, Blend.MEAN)
                for (d in 0 until plan.darkFrames) {
                    setPhase(Phase.Capturing(d + 1, plan.darkFrames, plan.exposureSec, plan.iso, "dark frame", SystemClock.elapsedRealtime(), plan.darkFrames * plan.exposureSec))
                    val j = cam.capture(plan.exposureNs, plan.iso, cfg.focusDiopters).use { f -> toJob(f, opt, -1, null, cfg, plan, null) }
                    ds.add(develop(j).first)
                }
                masterDark = ds.result()
            }

            val pl = pipeline ?: error("No frames were captured")
            setPhase(Phase.Processing("Stacking ${pl.stacker.frames} frames…"))
            val (summary, preview) = withContext(Dispatchers.Default) {
                val res = pl.stacker.result()
                masterDark?.let { d -> for (k in res.indices) res[k] = res[k] - d[k] }
                if (isRaw) FrameDecode.cameraToSrgb(opt)?.let { RawDevelop.applyMatrix(res, pl.w * pl.h, it) }
                for (k in res.indices) if (res[k] < 0f) res[k] = 0f
                val total = shot * actualExp / 1e9
                val desc = "StarShot ${cfg.preset.name}: $shot x ${"%.2f".format(actualExp / 1e9)}s ISO $actualIso = ${"%.0f".format(total)}s" +
                    (if (plan.align) ", aligned ${pl.aligned}/${max(0, shot - 1)}" else "") + ", ${opt.label}"
                setPhase(Phase.Processing("Writing 16-bit TIFF…"))
                store.write("StarShot_${cfg.preset.name.replace(" ", "")}_16bit.tif", "image/tiff") {
                    TiffWriter.write16(it, res, pl.w, pl.h, opt.exifOrientation, desc)
                }
                setPhase(Phase.Processing("Writing JPEG…"))
                val jpg = saveJpeg(store, res, pl.w, pl.h, cfg, opt, plan.copy(exposureNs = actualExp, iso = actualIso), shot,
                    "StarShot_${cfg.preset.name.replace(" ", "")}.jpg", desc, isRaw)
                if (!cfg.keepFrames && keptFrames.size > 1) keptFrames.drop(1).forEach(store::delete)
                val extra = buildString {
                    if (!isRaw) append("\nRAW not available on this camera: stacked processed frames, no DNG.")
                    if (stopReason != null) append("\nStopped early: $stopReason.")
                    if (plan.align && pl.failed > 0) append("\n${pl.failed} frames could not be star-aligned.")
                    if (masterDark != null) append("\nDark frames subtracted: ${plan.darkFrames}.")
                }
                Pair("$desc\nSaved to ${store.relPath}$extra", jpg)
            }
            ann.done("Star shot complete. Come get your phone.")
            setPhase(Phase.Done(summary, preview, store.relPath))
        } catch (e: CancellationException) {
            setPhase(Phase.Idle)
        } catch (e: Throwable) {
            Log.e(TAG, "Session failed", e)
            ann.error("Capture failed.")
            setPhase(Phase.Failed(e.message ?: e.toString()))
        } finally {
            withContext(NonCancellable) {
                ann.release()
                scratch.listFiles()?.forEach { it.delete() }
                CaptureService.stop(ctx)
                runCatching { cam.close() }
            }
        }
    }

    private suspend fun meter(cam: StarCamera, opt: CameraOption, cfg: SessionConfig): Metering {
        val exp = minOf(opt.caps.maxExposureNs, 1_000_000_000L).coerceAtLeast(opt.caps.minExposureNs)
        val iso = minOf(opt.caps.maxIso, 1600).coerceAtLeast(opt.caps.minIso)
        return cam.capture(exp, iso, cfg.focusDiopters).use { f ->
            val e = f.result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: exp
            val s = f.result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: iso
            val med = if (f.isRaw) {
                val raw = FrameDecode.rawToShorts(f.image)
                val meta = FrameDecode.rawMeta(opt, f.sensorResult, f.image.width, f.image.height)
                val b = meta.black.average().toFloat(); val range = meta.white - b
                RawDevelop.medianOfSample(FloatArray(raw.size / 13) { ((raw[it * 13].toInt() and 0xFFFF) - b) / range })
            } else {
                val (rgb, w, h) = FrameDecode.yuvToRgbHalf(f.image)
                RawDevelop.medianOfSample(RawDevelop.luminance(rgb, w * h))
            }
            Log.i(TAG, "Metering median=$med at ${e / 1e6}ms ISO $s")
            Metering(med, e, s)
        }
    }

    private fun toJob(f: Frame, opt: CameraOption, i: Int, store: SessionStore?, cfg: SessionConfig, plan: ShotPlan, kept: MutableList<Uri>?): DevJob {
        if (f.isRaw) {
            if (store != null) {
                // Always keep frame 1 (reference / the hero frame in Single mode); others while capturing, deleted later if not kept.
                if (i == 0 || cfg.keepFrames || cfg.preset.mode == ShootMode.TIMELAPSE)
                    kept?.add(store.writeDng("frame_%04d.dng".format(i + 1), opt, f, cfg.location))
            }
            val raw = FrameDecode.rawToShorts(f.image)
            val meta = FrameDecode.rawMeta(opt, f.sensorResult, f.image.width, f.image.height)
            return DevJob(raw, meta, null, f.image.width, f.image.height, i)
        }
        val (rgb, w, h) = FrameDecode.yuvToRgbHalf(f.image)
        return DevJob(null, null, rgb, w, h, i)
    }

    private fun develop(j: DevJob): Triple<FloatArray, Int, Int> {
        if (j.raw != null && j.meta != null) {
            RawDevelop.fixHotPixels(j.raw, j.meta)
            return Triple(RawDevelop.superpixel(j.raw, j.meta), j.w / 2, j.h / 2)
        }
        return Triple(j.rgb!!, j.w, j.h)
    }

    private fun saveJpeg(store: SessionStore, rgb: FloatArray, w: Int, h: Int, cfg: SessionConfig, opt: CameraOption,
                         plan: ShotPlan, frames: Int, name: String, desc: String?, isRaw: Boolean): Uri {
        val img = if (desc == null && isRaw) rgb.copyOf().also { c -> FrameDecode.cameraToSrgb(opt)?.let { RawDevelop.applyMatrix(c, w * h, it) } } else rgb
        val argb = Stretch.toArgb(img, w, h, cfg.preset.stretch)
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        val tmp = File.createTempFile("starshot", ".jpg")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 97, it) }
        bmp.recycle()
        runCatching {
            val ex = ExifInterface(tmp.absolutePath)
            ex.setAttribute(ExifInterface.TAG_ORIENTATION, opt.exifOrientation.toString())
            ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "StarShot 1.0")
            ex.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "%.4f".format(Locale.US, plan.exposureSec))
            ex.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, plan.iso.toString())
            ex.setAttribute(ExifInterface.TAG_F_NUMBER, "%.2f".format(Locale.US, opt.lens.aperture))
            ex.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(opt.lens.focalMm * 1000).toInt()}/1000")
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
            ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, desc ?: "StarShot frame")
            ex.setAttribute(ExifInterface.TAG_USER_COMMENT, "frames=$frames exposure=${plan.exposureSec}s iso=${plan.iso} total=${frames * plan.exposureSec}s")
            cfg.location?.let { setGps(ex, it) }
            ex.saveAttributes()
        }
        return try { store.copyFile(tmp, name, "image/jpeg") } finally { tmp.delete() }
    }

    private fun setGps(ex: ExifInterface, l: Location) {
        fun dms(v: Double): String {
            val a = abs(v); val d = a.toInt(); val mf = (a - d) * 60; val m = mf.toInt(); val s = (mf - m) * 60
            return "$d/1,$m/1,${(s * 1000).toInt()}/1000"
        }
        ex.setAttribute(ExifInterface.TAG_GPS_LATITUDE, dms(l.latitude))
        ex.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (l.latitude >= 0) "N" else "S")
        ex.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dms(l.longitude))
        ex.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (l.longitude >= 0) "E" else "W")
        if (l.hasAltitude()) {
            ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${(abs(l.altitude) * 100).toInt()}/100")
            ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (l.altitude >= 0) "0" else "1")
        }
    }

    /** Estimated bytes for a session (for the storage display). */
    fun estimateBytes(opt: CameraOption, plan: ShotPlan, keep: Boolean): Long {
        val px = (opt.rawSize ?: opt.yuvSize).let { it.width.toLong() * it.height }
        val dng = if (opt.rawSize != null) px * 2 + 200_000 else 0
        val frames = if (keep || plan.blend == Blend.LIGHTEN && plan.intervalMs > 0) plan.frames else 1
        return dng * frames + px / 4 * 6 + px / 4
    }

    fun estimateSeconds(cfg: SessionConfig, plan: ShotPlan): Double =
        cfg.countdownSec + 3 + (if (cfg.auto) 2.0 else 0.0) + plan.totalSeconds + plan.frames * 0.4 +
            (if (cfg.darkFrames && !plan.align) 15 + max(1, plan.darkFrames) * plan.exposureSec else 0.0) + 10 + ceil(plan.frames / 20.0)
}
