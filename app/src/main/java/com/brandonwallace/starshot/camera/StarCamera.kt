package com.brandonwallace.starshot.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SizeF
import android.view.Surface
import com.brandonwallace.starshot.astro.LensInfo
import com.brandonwallace.starshot.astro.SensorCaps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class CameraOption(
    val cameraId: String,
    val physicalId: String?,
    val label: String,
    val lens: LensInfo,
    val caps: SensorCaps,
    val rawSize: Size?,
    val yuvSize: Size,
    val previewSize: Size,
    val sensorOrientation: Int,
    val minFocusDiopters: Float,
    val focusCalibration: Int,
    val oisCanTurnOff: Boolean,
    val chars: CameraCharacteristics,
) {
    val key: String get() = cameraId + "/" + (physicalId ?: "")
    val exifOrientation: Int get() = when (sensorOrientation) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }
}

object CameraCatalog {
    fun list(ctx: Context): List<CameraOption> {
        val m = ctx.getSystemService(CameraManager::class.java)
        val out = ArrayList<CameraOption>()
        for (id in m.cameraIdList) {
            val ch = runCatching { m.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            build(id, null, ch)?.let(out::add)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                for (pid in ch.physicalCameraIds) {
                    runCatching { build(id, pid, m.getCameraCharacteristics(pid)) }.getOrNull()?.let(out::add)
                }
            }
        }
        return out
    }

    private fun area(s: Size) = s.width.toLong() * s.height

    private fun build(id: String, pid: String?, ch: CameraCharacteristics): CameraOption? {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val capsArr = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val raw = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capsArr)
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull(::area) else null
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull(::area) ?: return null
        val preview = map.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }?.maxByOrNull(::area) ?: Size(1440, 1080)
        val expR = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoR = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capsArr && expR != null && isoR != null
        val caps = SensorCaps(
            expR?.lower ?: 1_000_000L, expR?.upper ?: 100_000_000L,
            isoR?.lower ?: 100, isoR?.upper ?: 800,
            ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: 0, raw != null, manual,
        )
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.4f
        val ap = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 1.8f
        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(6.4f, 4.8f)
        val pix = raw ?: ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: yuv
        val lens = LensInfo(focal.toDouble(), ap.toDouble(), phys.width.toDouble(), phys.height.toDouble(), pix.width, pix.height)
        val eq = lens.equivFocalMm
        val kind = when { eq < 18 -> "Ultrawide"; eq < 40 -> "Main"; else -> "Telephoto" }
        val label = "$kind ${eq.roundToInt()}mm" + (if (pid == null) "" else " · lens $pid") + (if (raw == null) " (no RAW)" else "")
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) ?: false
        return CameraOption(
            id, pid, label, lens, caps, raw, yuv, preview,
            ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) ?: 0,
            ois, ch,
        )
    }
}

class Frame(val image: Image, val result: TotalCaptureResult, val isRaw: Boolean, val physicalId: String?) : AutoCloseable {
    /** Result for the physical sensor when a physical lens was used (needed by DngCreator). */
    val sensorResult: CaptureResult get() = physicalId?.let { result.physicalCameraTotalResults[it] } ?: result
    override fun close() = image.close()
}

/** Camera2 wrapper with full manual control. All calls are serialised; camera work runs on its own thread. */
class StarCamera(ctx: Context) {
    private val manager = ctx.applicationContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("StarCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val mutex = Mutex()
    private var device: CameraDevice? = null
    var option: CameraOption? = null; private set
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val images = Channel<Image>(Channel.UNLIMITED)

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(opt: CameraOption): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(opt.cameraId, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { if (cont.isActive) cont.resume(d) else d.close() }
            override fun onDisconnected(d: CameraDevice) {
                d.close(); if (device === d) device = null
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected (in use by another app?)"))
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close(); if (device === d) device = null
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
            }
        })
    }

    private suspend fun ensureOpen(opt: CameraOption): CameraDevice {
        device?.let { if (option?.key == opt.key) return it }
        closeInternal()
        val d = openDevice(opt)
        device = d; option = opt
        return d
    }

    private suspend fun createSession(d: CameraDevice, surfaces: List<Surface>, physicalId: String?): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val outs = surfaces.map { s -> OutputConfiguration(s).apply { if (physicalId != null) setPhysicalCameraId(physicalId) } }
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera session configuration failed"))
                    }
                }))
        }

    suspend fun startPreview(opt: CameraOption, surface: Surface, nightBoost: Boolean, focus: Float) = mutex.withLock {
        val d = ensureOpen(opt)
        session?.close(); session = null; reader?.close(); reader = null
        val s = createSession(d, listOf(surface), opt.physicalId)
        session = s
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(surface)
        applyFocus(b, opt, focus)
        if (nightBoost && opt.caps.manualSensor) {
            val exp = minOf(opt.caps.maxExposureNs, 200_000_000L).coerceAtLeast(opt.caps.minExposureNs)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, exp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, minOf(opt.caps.maxIso, 6400).coerceAtLeast(opt.caps.minIso))
        }
        s.setRepeatingRequest(b.build(), null, handler)
    }

    /** Switch to a capture-only session (no preview surface, so it survives the screen/activity going away). Returns true when RAW. */
    suspend fun prepareCapture(opt: CameraOption): Boolean = mutex.withLock {
        val d = ensureOpen(opt)
        session?.close(); session = null; reader?.close(); reader = null
        drain()
        val raw = opt.rawSize
        val r = if (raw != null) ImageReader.newInstance(raw.width, raw.height, ImageFormat.RAW_SENSOR, 3)
        else ImageReader.newInstance(opt.yuvSize.width, opt.yuvSize.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ rr -> runCatching { rr.acquireNextImage() }.getOrNull()?.let { images.trySend(it) } }, handler)
        reader = r
        session = createSession(d, listOf(r.surface), opt.physicalId)
        raw != null
    }

    private fun drain() { while (true) { (images.tryReceive().getOrNull() ?: break).close() } }

    suspend fun capture(exposureNs: Long, iso: Int, focus: Float): Frame = mutex.withLock {
        val d = device ?: error("Camera closed")
        val s = session ?: error("No capture session")
        val r = reader ?: error("No image reader")
        val opt = option!!
        drain()
        val raw = r.imageFormat == ImageFormat.RAW_SENSOR
        val b = d.createCaptureRequest(if (opt.caps.manualSensor) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(r.surface)
        configureManual(b, opt, exposureNs, iso, focus, raw)
        val res = CompletableDeferred<TotalCaptureResult>()
        s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) { res.complete(result) }
            override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                res.completeExceptionally(IllegalStateException("Capture failed (reason ${f.reason})"))
            }
        }, handler)
        val timeout = exposureNs / 1_000_000 + 20_000
        val result = withTimeout(timeout) { res.await() }
        val img = withTimeout(timeout) { images.receive() }
        Frame(img, result, raw, opt.physicalId)
    }

    private fun configureManual(b: CaptureRequest.Builder, opt: CameraOption, exp: Long, iso: Int, focus: Float, raw: Boolean) {
        val ch = opt.chars
        if (opt.caps.manualSensor) {
            b.set(CaptureRequest.CONTROL_MODE, if (raw) CameraMetadata.CONTROL_MODE_OFF else CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AWB_MODE, if (raw) CameraMetadata.CONTROL_AWB_MODE_OFF else CameraMetadata.CONTROL_AWB_MODE_AUTO)
            val e = exp.coerceIn(opt.caps.minExposureNs, opt.caps.maxExposureNs)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, e)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(opt.caps.minIso, opt.caps.maxIso))
        }
        applyFocus(b, opt, focus)
        if (opt.oisCanTurnOff) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        if (ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) == true)
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        if (ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CameraMetadata.EDGE_MODE_OFF) == true)
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        if (ch.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.contains(CameraMetadata.HOT_PIXEL_MODE_OFF) == true)
            b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
        if (raw && ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true)
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
    }

    private fun applyFocus(b: CaptureRequest.Builder, opt: CameraOption, diopters: Float) {
        if (opt.minFocusDiopters > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters.coerceIn(0f, opt.minFocusDiopters))
        }
    }

    private fun closeInternal() {
        runCatching { session?.close() }; session = null
        runCatching { reader?.close() }; reader = null
        runCatching { device?.close() }; device = null
        option = null
        drain()
    }

    suspend fun close() = mutex.withLock { closeInternal() }
}
