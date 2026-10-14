package com.brandonwallace.starshot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.PowerManager
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brandonwallace.starshot.MainActivity
import com.brandonwallace.starshot.astro.AstroMath
import com.brandonwallace.starshot.astro.AutoMode
import com.brandonwallace.starshot.astro.Preset
import com.brandonwallace.starshot.astro.Presets
import com.brandonwallace.starshot.astro.ShootMode
import com.brandonwallace.starshot.camera.CameraCatalog
import com.brandonwallace.starshot.camera.CameraOption
import com.brandonwallace.starshot.session.Phase
import com.brandonwallace.starshot.session.SessionConfig
import com.brandonwallace.starshot.session.SessionRunner
import com.brandonwallace.starshot.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

val Red = Color(0xFFFF3B30)
val DimRed = Color(0xFF8E1B14)
val TextRed = Color(0xFFFF6B60)

@Composable
fun StarShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Red, onPrimary = Color.Black, secondary = DimRed, onSecondary = Color.Black,
        background = Color.Black, onBackground = TextRed, surface = Color(0xFF0A0303), onSurface = TextRed,
        surfaceVariant = Color(0xFF1A0605), onSurfaceVariant = TextRed, outline = DimRed,
        secondaryContainer = Color(0xFF3A0B08), onSecondaryContainer = TextRed,
    ), content = content)
}

/** UI settings held at process level so they survive activity recreation. */
object Ui {
    var options by mutableStateOf<List<CameraOption>>(emptyList())
    var selected by mutableStateOf<CameraOption?>(null)
    var preset by mutableStateOf(Presets.MILKY_WAY)
    var auto by mutableStateOf(true)
    var expFrac by mutableFloatStateOf(1f)
    var iso by mutableIntStateOf(1600)
    var frames by mutableIntStateOf(12)
    var interval by mutableFloatStateOf(20f)
    var countdown by mutableIntStateOf(10)
    var voice by mutableStateOf(true)
    var vibrate by mutableStateOf(true)
    var maxVolume by mutableStateOf(true)
    var motionGuard by mutableStateOf(true)
    var darks by mutableStateOf(false)
    var keepFrames by mutableStateOf(true)
    var location by mutableStateOf(false)
    var nightPreview by mutableStateOf(true)
    var focus by mutableFloatStateOf(0f)
    var cameraError by mutableStateOf<String?>(null)

    fun manualExposureNs(o: CameraOption): Long {
        val lo = ln(o.caps.minExposureNs.toDouble()); val hi = ln(o.caps.maxExposureNs.toDouble())
        return exp(lo + (hi - lo) * expFrac).toLong()
    }

    fun config(o: CameraOption, loc: Location?) = SessionConfig(
        o, preset, auto, manualExposureNs(o), iso, frames, interval.toDouble(), countdown, voice, vibrate, maxVolume,
        motionGuard, darks, keepFrames, loc, focus,
    )
}

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun lastLocation(ctx: Context): Location? {
    if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
    val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
    return listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
}

@Composable
fun StarShotApp(act: MainActivity) {
    val ctx = LocalContext.current
    val phase by SessionRunner.phase.collectAsState()
    var screen by remember { mutableStateOf("camera") }
    var hasCam by remember { mutableStateOf(granted(ctx, Manifest.permission.CAMERA)) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCam = granted(ctx, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (!hasCam) perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
    }
    LaunchedEffect(hasCam) {
        if (hasCam && Ui.options.isEmpty()) {
            val list = withContext(Dispatchers.IO) { runCatching { CameraCatalog.list(ctx) }.getOrDefault(emptyList()) }
            Ui.options = list
            Ui.selected = list.firstOrNull { it.physicalId == null && it.rawSize != null } ?: list.firstOrNull()
            Ui.selected?.let { o -> Ui.iso = AutoMode.isoCeiling(o.caps) }
        }
    }
    LaunchedEffect(phase) {
        act.setBrightness(when (phase) {
            is Phase.Capturing, is Phase.Waiting, is Phase.Processing -> 0.005f
            is Phase.Countdown -> 1f
            else -> -1f
        })
    }
    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        when {
            phase !is Phase.Idle -> SessionOverlay(phase)
            !hasCam -> Column(Modifier.padding(24.dp)) {
                Text("StarShot needs camera access.", fontSize = 20.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)) }) { Text("Grant camera") }
            }
            screen == "gallery" -> GalleryScreen { screen = "camera" }
            else -> CameraScreen(act, onGallery = { screen = "gallery" })
        }
    }
}

@Composable
private fun CameraScreen(act: MainActivity, onGallery: () -> Unit) {
    val ctx = LocalContext.current
    val opt = Ui.selected
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        Ui.location = r.values.any { it }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("StarShot", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Red)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onGallery) { Text("Gallery") }
        }
        if (opt == null) { Text("No usable back camera found."); return@Column }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).border(1.dp, DimRed)) {
            Preview(act, opt)
            LevelOverlay(Modifier.align(Alignment.Center))
            Ui.cameraError?.let { Text(it, Modifier.align(Alignment.BottomCenter).padding(8.dp)) }
        }
        StatusRow(opt)
        Section("Camera")
        ChipRow(Ui.options, { it.label }, { it.key == opt.key }) { Ui.selected = it; Ui.iso = Ui.iso.coerceIn(it.caps.minIso, it.caps.maxIso) }
        if (opt.rawSize == null) Text("RAW is not supported on this camera: StarShot will stack processed frames and cannot save DNGs.", color = Color(0xFFFFA000), fontSize = 13.sp)
        Section("Preset")
        ChipRow(Presets.all, { it.name }, { it == Ui.preset }) { Ui.preset = it; if (it.mode == ShootMode.TIMELAPSE) Ui.interval = it.intervalSec.toFloat() }
        PlanCard(opt)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto mode", Modifier.weight(1f), fontSize = 18.sp)
            Switch(checked = Ui.auto, onCheckedChange = { Ui.auto = it })
        }
        if (!Ui.auto) ManualControls(opt)
        Section("Countdown: ${Ui.countdown}s")
        Slider(value = Ui.countdown.toFloat(), onValueChange = { Ui.countdown = it.roundToInt() }, valueRange = 3f..30f, steps = 26)
        Toggle("Voice countdown (alarm volume)", Ui.voice) { Ui.voice = it }
        Toggle("Max alarm volume during session", Ui.maxVolume) { Ui.maxVolume = it }
        Toggle("Vibrate", Ui.vibrate) { Ui.vibrate = it }
        Toggle("Motion guard (wait until still)", Ui.motionGuard) { Ui.motionGuard = it }
        Toggle("Keep every RAW frame (DNG)", Ui.keepFrames) { Ui.keepFrames = it }
        Toggle("Dark frames (Single/Star Trails)", Ui.darks) { Ui.darks = it }
        Toggle("Save location in files", Ui.location) {
            if (it && !granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION))
                locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else Ui.location = it
        }
        Toggle("Bright night preview (framing)", Ui.nightPreview) { Ui.nightPreview = it }
        if (opt.minFocusDiopters > 0f) {
            Section("Focus: ${if (Ui.focus == 0f) "∞ (infinity)" else "%.2f dpt".format(Ui.focus)}")
            Slider(value = Ui.focus, onValueChange = { Ui.focus = it }, valueRange = 0f..minOf(1f, opt.minFocusDiopters))
            if (opt.focusCalibration == 0) Text("Lens focus is uncalibrated: if stars look soft, nudge this slightly.", fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { SessionRunner.start(ctx, Ui.config(opt, if (Ui.location) lastLocation(ctx) else null)) },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Red),
        ) { Text("START  (${Ui.countdown}s countdown)", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(t: String) { Text(t, Modifier.padding(top = 10.dp, bottom = 2.dp), fontWeight = FontWeight.Bold) }

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChipRow(items: List<T>, label: (T) -> String, sel: (T) -> Boolean, onClick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { FilterChip(selected = sel(it), onClick = { onClick(it) }, label = { Text(label(it)) }) }
    }
}

private fun fmtTime(sec: Double): String {
    val s = sec.roundToInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun fmtExp(sec: Double) = if (sec >= 1) "%.1fs".format(sec) else "1/${(1 / sec).roundToInt()}s"

@Composable
private fun PlanCard(opt: CameraOption) {
    val cfg = Ui.config(opt, null)
    val plan = if (Ui.auto) AutoMode.plan(opt.caps, opt.lens, Ui.preset, null) else SessionRunner.manualPlan(cfg)
    val npf = AstroMath.npf(opt.lens); val r500 = AstroMath.rule500(opt.lens)
    val bytes = SessionRunner.estimateBytes(opt, plan, Ui.keepFrames)
    val free = SessionStore.freeBytes()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, DimRed).padding(8.dp)) {
        Text("${Ui.preset.mode.label}: ${plan.frames} × ${fmtExp(plan.exposureSec)} @ ISO ${plan.iso}" +
            (if (plan.intervalMs > 0) " every ${plan.intervalMs / 1000}s" else ""), fontWeight = FontWeight.Bold)
        Text("Integration ${fmtTime(plan.frames * plan.exposureSec)} · session ≈ ${fmtTime(SessionRunner.estimateSeconds(cfg, plan))}")
        Text("Sensor: ${fmtExp(opt.caps.minExposureNs / 1e9)}–${fmtExp(opt.caps.maxExposureNs / 1e9)}, ISO ${opt.caps.minIso}–${opt.caps.maxIso}" +
            (if (opt.caps.maxAnalogIso > 0) " (analog ≤${opt.caps.maxAnalogIso})" else ""), fontSize = 12.sp)
        Text("Star-trail limit: NPF ${"%.1f".format(npf)}s · 500-rule ${"%.1f".format(r500)}s (${opt.lens.focalMm}mm f/${opt.lens.aperture}, ${"%.2f".format(opt.lens.pixelPitchUm)}µm)", fontSize = 12.sp)
        Text("Needs ≈ ${bytes / 1_000_000} MB · free ${free / 1_000_000_000} GB (≈${if (bytes > 0) free / bytes else 0} sessions)", fontSize = 12.sp)
        if (Ui.auto) Text("Auto re-meters the sky after the countdown and may lower ISO/exposure for bright scenes.", fontSize = 12.sp)
    }
}

@Composable
private fun ManualControls(opt: CameraOption) {
    val expS = Ui.manualExposureNs(opt) / 1e9
    Text("Exposure per frame: ${fmtExp(expS)}" + if (Ui.preset.mode == ShootMode.STACK && expS > AstroMath.npf(opt.lens)) "  (stars will trail)" else "")
    Slider(value = Ui.expFrac, onValueChange = { Ui.expFrac = it })
    Text("ISO ${Ui.iso}")
    Slider(value = Ui.iso.toFloat(), onValueChange = { Ui.iso = it.roundToInt() }, valueRange = opt.caps.minIso.toFloat()..opt.caps.maxIso.toFloat())
    if (Ui.preset.mode != ShootMode.SINGLE) {
        Text("Frames: ${Ui.frames}")
        Slider(value = Ui.frames.toFloat(), onValueChange = { Ui.frames = it.roundToInt() }, valueRange = 2f..400f)
    }
    if (Ui.preset.mode == ShootMode.TIMELAPSE) {
        Text("Interval: ${Ui.interval.roundToInt()}s")
        Slider(value = Ui.interval, onValueChange = { Ui.interval = it }, valueRange = 2f..120f)
    }
}

@Composable
private fun StatusRow(opt: CameraOption) {
    val ctx = LocalContext.current
    var batt by remember { mutableIntStateOf(-1) }
    var thermal by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { batt = SessionRunner.batteryPct(ctx); thermal = SessionRunner.thermalStatus(ctx); delay(10_000) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("🔋 ${if (batt >= 0) "$batt%" else "?"}", color = if (batt in 0..19) Color(0xFFFFA000) else TextRed)
        Text(when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "🌡 HOT – let it cool"
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> "🌡 warm"
            else -> "🌡 ok"
        }, color = if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) Color(0xFFFFA000) else TextRed)
        Text(if (opt.rawSize != null) "RAW ${opt.rawSize.width}×${opt.rawSize.height}" else "no RAW")
    }
    if (batt in 0..19) Text("Battery low: long sessions may stop early.", color = Color(0xFFFFA000), fontSize = 12.sp)
}

@Composable
private fun Preview(act: MainActivity, opt: CameraOption) {
    val ctx = LocalContext.current
    var surface by remember { mutableStateOf<Surface?>(null) }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var resumed by remember { mutableStateOf(act.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumed = true
            if (e == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        act.lifecycle.addObserver(obs)
        onDispose { act.lifecycle.removeObserver(obs) }
    }
    AndroidView(factory = { c ->
        TextureView(c).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { texture = st; surface = Surface(st) }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { surface = null; texture = null; return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }, modifier = Modifier.fillMaxSize())
    val focusKey = (Ui.focus * 50).roundToInt()
    LaunchedEffect(opt.key, surface, resumed, Ui.nightPreview, focusKey) {
        val s = surface
        val cam = SessionRunner.camera(ctx)
        if (s == null || !resumed) { if (SessionRunner.isIdle) cam.close(); return@LaunchedEffect }
        texture?.setDefaultBufferSize(opt.previewSize.width, opt.previewSize.height)
        Ui.cameraError = try { cam.startPreview(opt, s, Ui.nightPreview, Ui.focus); null } catch (e: Exception) { "Camera: ${e.message}" }
    }
    DisposableEffect(Unit) { onDispose { if (SessionRunner.isIdle) kotlinx.coroutines.runBlocking { SessionRunner.camera(ctx).close() } } }
}

/** Aim helper: elevation of the rear camera above the horizon (90° = straight up), with a bubble level. */
@Composable
private fun LevelOverlay(modifier: Modifier) {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(floatArrayOf(0f, 0f, 9.81f)) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { g = e.values.copyOf(3) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensor?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm?.unregisterListener(l) }
    }
    val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(0.1f)
    val elev = Math.toDegrees(asin((-g[2] / n).toDouble().coerceIn(-1.0, 1.0))).roundToInt()
    val level = elev >= 87
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(90.dp)) {
            val r = size.minDimension / 2
            drawCircle(DimRed, r, style = Stroke(2f))
            drawCircle(DimRed, r / 4, style = Stroke(1f))
            val dx = (-g[0] / n) * r; val dy = (g[1] / n) * r
            drawCircle(if (level) Color(0xFF66FF66) else Red, 10f, Offset(center.x + dx, center.y + dy))
        }
        Text("Camera aim ${elev}° ${if (level) "· zenith" else ""}", fontSize = 13.sp, color = if (level) Color(0xFF66FF66) else TextRed)
    }
}

@Composable
private fun SessionOverlay(phase: Phase) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(phase) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(1000) } }
    val dim = Color(0xFF3A0B08)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (phase) {
                is Phase.Countdown -> {
                    Text("${phase.sec}", fontSize = 220.sp, fontWeight = FontWeight.Black, color = Red)
                    Text(phase.label, fontSize = 20.sp, color = Red)
                }
                is Phase.Waiting -> Text(phase.text, color = dim, fontSize = 16.sp)
                is Phase.Capturing -> {
                    val el = (now - phase.startedAt) / 1000.0
                    Text("Frame ${phase.frame}/${phase.total}", color = dim, fontSize = 18.sp)
                    Text("${fmtExp(phase.exposureSec)} · ISO ${phase.iso}", color = dim, fontSize = 14.sp)
                    Text("${fmtTime(el)} / ${fmtTime(phase.totalSec)}", color = dim, fontSize = 14.sp)
                    if (phase.info.isNotEmpty()) Text(phase.info, color = dim, fontSize = 12.sp)
                }
                is Phase.Processing -> {
                    Text(phase.text, color = dim, fontSize = 16.sp)
                    LinearProgressIndicator(Modifier.padding(top = 8.dp), color = dim)
                }
                is Phase.Done -> {
                    Text("✓ Done", fontSize = 40.sp, color = Red, fontWeight = FontWeight.Bold)
                    Text(phase.summary, Modifier.padding(vertical = 12.dp))
                    phase.preview?.let { uri ->
                        Button(onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        }) { Text("Open image") }
                    }
                    OutlinedButton(onClick = { SessionRunner.reset() }) { Text("Back to camera") }
                }
                is Phase.Failed -> {
                    Text("Capture failed", fontSize = 28.sp, color = Red)
                    Text(phase.message, Modifier.padding(vertical = 12.dp))
                    OutlinedButton(onClick = { SessionRunner.reset() }) { Text("Back") }
                }
                Phase.Idle -> {}
            }
        }
        if (phase !is Phase.Done && phase !is Phase.Failed) {
            TextButton(onClick = { SessionRunner.cancel() }, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                Text("Cancel", color = dim)
            }
        }
    }
}

@Composable
private fun GalleryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<com.brandonwallace.starshot.session.SessionEntry>?>(null) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { runCatching { SessionStore.sessions(ctx) }.getOrDefault(emptyList()) } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Camera") }
            Text("Sessions", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        val l = list
        when {
            l == null -> Text("Loading…")
            l.isEmpty() -> Text("No sessions yet. Files are saved to Pictures/StarShot/.")
            else -> LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(l) { e ->
                    var bmp by remember(e.cover) { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(e.cover) {
                        bmp = e.cover?.let { u -> withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(u, Size(512, 512), null).asImageBitmap() }.getOrNull() } }
                    }
                    Column(Modifier.clickable(enabled = e.cover != null) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(e.cover, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    }) {
                        val b = bmp
                        if (b != null) Image(b, null, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                        else Box(Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, DimRed))
                        Text(e.folder, fontSize = 12.sp)
                        Text("${e.files} files", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
