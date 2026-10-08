package com.ayman.aicam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal enum class Profile(val title: String) { S("S"), G("G"), IPHONE("iPhone") }
internal enum class CameraMode(val title: String) { PHOTO("PHOTO"), HDR("HDR"), VIDEO("VIDEO"), PORTRAIT("PORTRAIT"), NIGHT("NIGHT"), PRO("PRO") }
internal enum class LensChoice(val title: String, val targetMm: Float) { ULTRA("0.5x", 13f), WIDE("1x", 26f), TELE2("2x", 52f), TELE5("5x", 130f) }
internal enum class WhiteBalance(val title: String, val value: Int) {
    AUTO("AWB", CaptureRequest.CONTROL_AWB_MODE_AUTO), DAYLIGHT("DAY", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT), CLOUDY("CLOUD", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT), TUNGSTEN("TUNG", CaptureRequest.CONTROL_AWB_MODE_TUNGSTEN)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AICamApp() } }
}

@Composable private fun AICamApp() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r[Manifest.permission.CAMERA] == true }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    if (granted) CameraScreen() else Box(Modifier.fillMaxSize().background(ComposeColor.Black), Alignment.Center) { Text("Camera permission required", color = ComposeColor.White) }
}

@androidx.camera.camera2.interop.ExperimentalCamera2Interop
@Composable private fun CameraScreen() {
    val context = LocalContext.current
    var profile by remember { mutableStateOf(Profile.S) }
    var mode by remember { mutableStateOf(CameraMode.PHOTO) }
    var flash by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    var lens by remember { mutableStateOf(LensChoice.WIDE) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableIntStateOf(0) }
    var iso by remember { mutableIntStateOf(800) }
    var shutterNs by remember { mutableLongStateOf(8_000_000L) }
    var wb by remember { mutableStateOf(WhiteBalance.AUTO) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var video by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var status by remember { mutableStateOf("V3") }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var availableLensMm by remember { mutableStateOf<List<Float>>(emptyList()) }
    var extensionStatus by remember { mutableStateOf("") }

    fun chooseCameraInfo(p: ProcessCameraProvider, selector: CameraSelector): CameraInfo? = p.availableCameraInfos.firstOrNull { info ->
        try { Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK } catch (_: Exception) { false }
    }

    fun selectorForLens(p: ProcessCameraProvider): CameraSelector {
        if (front) return CameraSelector.DEFAULT_FRONT_CAMERA
        val candidates = p.availableCameraInfos.filter { info ->
            try { Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } catch (_: Exception) { false }
        }
        val best = candidates.minByOrNull { info ->
            val focal = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 26f
            kotlin.math.abs(focal - lens.targetMm)
        }
        return best?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
    }

    fun bindCamera() {
        val p = provider ?: return
        val view = previewView ?: return
        val base = selectorForLens(p)
        val extensionMode = when (mode) { CameraMode.NIGHT -> ExtensionMode.NIGHT; CameraMode.PORTRAIT -> ExtensionMode.BOKEH; CameraMode.HDR -> ExtensionMode.HDR; else -> null }
        val selector = if (!front && extensionMode != null) {
            try {
                val ext = ExtensionsManager.getInstanceAsync(context, p).get()
                if (ext.isExtensionAvailable(base, extensionMode)) {
                    extensionStatus = "Vendor ${mode.title}"
                    ext.getExtensionEnabledCameraSelector(base, extensionMode)
                } else { extensionStatus = "Own ${mode.title}"; base }
            } catch (_: Exception) { extensionStatus = "Own ${mode.title}"; base }
        } else { extensionStatus = if (mode == CameraMode.VIDEO) "Video" else "Standard"; base }

        val builder = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(97)
        val interop = Camera2Interop.Extender(builder)
        if (mode == CameraMode.PRO && !front) {
            interop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            interop.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(50, 6400))
            interop.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs.coerceIn(100_000L, 100_000_000L))
            interop.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb.value)
        }
        val cap = builder.build()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
        val vid = VideoCapture.withOutput(recorder)
        try {
            p.unbindAll()
            val vendorActive = extensionStatus.startsWith("Vendor")
            val bound = if (vendorActive) {
                p.bindToLifecycle(context as MainActivity, selector, preview, cap)
            } else {
                p.bindToLifecycle(context as MainActivity, selector, preview, cap, vid)
            }
            camera = bound
            capture = cap
            video = if (vendorActive) null else vid
            availableLensMm = try { Camera2CameraInfo.from(bound.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
            status = "${if (front) "Front" else "Rear"} • $extensionStatus"
        } catch (e: Exception) { status = "Camera bind error: ${e.message ?: "unknown"}" }
    }

    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ provider = future.get(); bindCamera() }, ContextCompat.getMainExecutor(context))
    }
    LaunchedEffect(front, mode, lens, iso, shutterNs, wb) { if (provider != null && previewView != null) bindCamera() }
    LaunchedEffect(zoom, exposure) {
        camera?.let { cam ->
            cam.cameraInfo.zoomState.value?.let { state -> cam.cameraControl.setZoomRatio(min(state.maxZoomRatio, max(state.minZoomRatio, zoom))) }
            cam.cameraControl.setExposureCompensationIndex(exposure)
        }
    }

    fun captureOne(file: File, done: (File?) -> Unit) {
        val c = capture ?: return done(null)
        c.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        c.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) { done(null) }
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { done(file) }
        })
    }

    fun takePhoto() {
        val vendorExtension = extensionStatus.startsWith("Vendor") && (mode == CameraMode.HDR || mode == CameraMode.NIGHT || mode == CameraMode.PORTRAIT)
        val count = if (vendorExtension) 1 else when (mode) { CameraMode.HDR -> 3; CameraMode.NIGHT -> 6; else -> 1 }
        val files = mutableListOf<File>()
        fun next(index: Int) {
            if (index >= count) {
                val bitmaps = files.mapNotNull { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
                if (bitmaps.isEmpty()) { status = "Capture failed"; return }
                val result = ProcessingEngine.process(bitmaps, profile, mode, mode == CameraMode.PORTRAIT)
                saveImage(context, result, profile, mode)
                bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                files.forEach { it.delete() }
                status = "Saved ${profile.title} ${mode.title} • ${if (vendorExtension) "vendor pipeline" else "$count frames"}"
                return
            }
            val f = File(context.cacheDir, "v3_${System.nanoTime()}.jpg")
            captureOne(f) { saved -> if (saved == null) status = "Capture failed" else { files += saved; next(index + 1) } }
        }
        next(0)
    }

    fun toggleVideo() {
        val vc = video ?: return
        if (recording != null) { recording?.stop(); return }
        val out = File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4")
        recording = vc.output.prepareRecording(context, FileOutputOptions.Builder(out).build()).start(ContextCompat.getMainExecutor(context)) { e ->
            when (e) { is VideoRecordEvent.Start -> status = "Recording…"; is VideoRecordEvent.Finalize -> { if (!e.hasError()) saveVideo(context, out) else status = "Video error"; recording = null } }
        }
    }

    Surface(color = ComposeColor.Black) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { PreviewView(it).also { v -> previewView = v } }, modifier = Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().padding(top = 22.dp, bottom = 16.dp), Arrangement.SpaceBetween) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), Arrangement.Center) {
                        Profile.entries.forEach { p ->
                            val selected = p == profile
                            Surface(color = if (selected) ComposeColor.White else ComposeColor.Black.copy(.55f), shape = RoundedCornerShape(22.dp), modifier = Modifier.clickable { profile = p }) {
                                Text(p.title, color = if (selected) ComposeColor.Black else ComposeColor.White, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp), fontSize = 14.sp)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(18.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Icon(if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flash", ComposeColor.White, Modifier.clickable { flash = !flash })
                        Text(extensionStatus, color = ComposeColor.White, fontSize = 11.sp)
                        Icon(Icons.Default.Settings, "Settings", ComposeColor.White)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        LensChoice.entries.forEach { l -> Text(l.title, color = if (lens == l) ComposeColor.White else ComposeColor.LightGray, modifier = Modifier.clickable { lens = l; zoom = l.targetMm / 26f }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("ISO $iso", ComposeColor.White, fontSize = 11.sp)
                        if (mode == CameraMode.PRO && !front) {
                            Text("1/${(1_000_000_000L / shutterNs.coerceAtLeast(1)).coerceAtMost(100000)}s", ComposeColor.White, fontSize = 11.sp)
                            Text(wb.title, ComposeColor.White, fontSize = 11.sp, modifier = Modifier.clickable { wb = WhiteBalance.entries[(wb.ordinal + 1) % WhiteBalance.entries.size] })
                        }
                    }
                    if (mode == CameraMode.PRO && !front) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("ISO", ComposeColor.White, fontSize = 11.sp)
                            Slider(value = iso.toFloat(), onValueChange = { iso = it.toInt() }, valueRange = 50f..6400f, modifier = Modifier.weight(1f))
                            Text("EV", ComposeColor.White, fontSize = 11.sp)
                            Slider(value = exposure.toFloat(), onValueChange = { exposure = it.toInt() }, valueRange = -6f..6f, steps = 11, modifier = Modifier.weight(1f))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                        Icon(Icons.Default.PhotoLibrary, "Gallery", ComposeColor.White, Modifier.size(30.dp).clickable { context.startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) })
                        Surface(shape = CircleShape, color = if (recording != null) ComposeColor.Red else ComposeColor.White, modifier = Modifier.size(78.dp).clickable { if (mode == CameraMode.VIDEO) toggleVideo() else takePhoto() }) { Box(Modifier.fillMaxSize(), Alignment.Center) { if (recording != null) Text("■", color = ComposeColor.White, fontSize = 24.sp) } }
                        Icon(Icons.Default.Sync, "Switch camera", ComposeColor.White, Modifier.size(32.dp).clickable { front = !front })
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp), Arrangement.SpaceEvenly) { CameraMode.entries.forEach { m -> Text(m.title, color = if (m == mode) ComposeColor.White else ComposeColor.LightGray, fontSize = 10.sp, modifier = Modifier.clickable { mode = m }) } }
                    if (status.isNotEmpty()) Text(status, ComposeColor.White, 10.sp, modifier = Modifier.padding(top = 7.dp))
                    if (availableLensMm.isNotEmpty()) Text("Lens: ${availableLensMm.joinToString { "%.1fmm".format(Locale.US, it) }}", ComposeColor.LightGray, 9.sp)
                }
            }
        }
    }
}

private fun saveImage(context: Context, bitmap: android.graphics.Bitmap, profile: Profile, mode: CameraMode) {
    val name = "AICam_V3_${profile.title}_${mode.title}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICam") }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 97, it) }
    if (!bitmap.isRecycled) bitmap.recycle()
}

private fun saveVideo(context: Context, file: File) {
    val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, file.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AICam") }
    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
    file.delete()
}
