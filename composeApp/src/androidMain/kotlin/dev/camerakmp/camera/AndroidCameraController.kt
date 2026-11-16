package dev.camerakmp.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import androidx.exifinterface.media.ExifInterface
import dev.camerakmp.model.AppSettings
import dev.camerakmp.model.CameraController
import dev.camerakmp.model.CameraOptions
import dev.camerakmp.model.CameraUiState
import dev.camerakmp.model.CaptureRecord
import dev.camerakmp.model.DeviceCapabilities
import dev.camerakmp.model.HdrMode
import dev.camerakmp.model.InfoSection
import dev.camerakmp.model.LensInfo
import dev.camerakmp.model.ModeKind
import dev.camerakmp.model.PhotoFormat
import dev.camerakmp.model.ScanState
import dev.camerakmp.model.VideoCodec
import dev.camerakmp.model.VideoKind
import dev.camerakmp.model.VideoOption
import dev.camerakmp.model.VideoPreset
import dev.camerakmp.model.formatBitrate
import dev.camerakmp.model.key
import dev.camerakmp.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2-based engine. One "still" session is kept open for preview + photo (+ RAW when the device allows
 * all three together); RAW, HDR and video each switch sessions when needed.
 */
class AndroidCameraController(private val context: Context) : CameraController {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val store = SettingsStore(context)
    private val saver = MediaStoreSaver(context)
    private val scanner = CapabilityScanner(context, manager)

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val handler = Handler(cameraThread.looper)
    private val executor = Executor { handler.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
    private val mutex = Mutex()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()
    private val _uiState = MutableStateFlow(CameraUiState())
    override val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    private val _settings = MutableStateFlow(store.load())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private val _captures = MutableStateFlow<List<CaptureRecord>>(emptyList())
    override val captures: StateFlow<List<CaptureRecord>> = _captures.asStateFlow()

    private var caps: DeviceCapabilities? = null

    // Camera state, touched only on the camera thread under [mutex].
    private var previewHolder: SurfaceHolder? = null
    private var previewSurface: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var extSession: CameraExtensionSession? = null
    private var photoReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var activePhotoFormat: PhotoFormat? = null
    private var recorder: VideoRecorder? = null
    private var recordingUri: Uri? = null
    private var recordingPfd: ParcelFileDescriptor? = null
    private var recordingPreset: VideoPreset? = null
    private var recordingTimer: Job? = null

    @Volatile private var deviceOrientation = 0
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation != ORIENTATION_UNKNOWN) deviceOrientation = ((orientation + 45) / 90 * 90) % 360
        }
    }

    // ---------------------------------------------------------------- public API

    override fun scan(force: Boolean) {
        if (_scanState.value is ScanState.Running) return
        scope.launch(Dispatchers.Default) {
            try {
                if (force) scanner.videoValidator.clearCache()
                _scanState.value = ScanState.Running("Starting", 0f)
                val result = scanner.scan { msg, p -> _scanState.value = ScanState.Running(msg, p) }
                caps = result
                _settings.update { sanitize(it, result) }
                store.save(_settings.value)
                _scanState.value = ScanState.Done(result)
                withContext(handler.asCoroutineDispatcher()) { mutex.withLock { preparePreview() } }
            } catch (e: Exception) {
                Log.e(TAG, "scan failed", e)
                _scanState.value = ScanState.Failed(e.message ?: e.toString())
            }
        }
    }

    override fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = _settings.value
        val after = caps?.let { sanitize(transform(before), it) } ?: transform(before)
        _settings.value = after
        store.save(after)
        val needsRestart = before.lensKey != after.lensKey || before.photoFormat != after.photoFormat ||
            before.hdrMode != after.hdrMode
        if (needsRestart) {
            launchCamera("Applying settings") { if (recorder == null) preparePreview() }
        } else if (before.manual != after.manual) {
            // Pro controls only change the repeating request; no need to rebuild the session.
            scope.launch {
                mutex.withLock {
                    val dev = device
                    val surface = previewSurface
                    if (recorder == null && session != null && dev != null && surface != null) {
                        runCatching { startRegularPreview(dev, surface, video = false) }
                    }
                }
            }
        }
    }

    override fun takePhoto() = launchCamera("Taking photo") { capturePhoto() }

    override fun takeRaw() = launchCamera("Taking RAW") { captureRaw() }

    override fun toggleRecording(kind: VideoKind) = launchCamera(if (recorder == null) "Starting video" else "Saving video") {
        if (recorder != null) stopRecording() else startRecording(kind)
    }

    override fun modeName(kind: ModeKind, value: Int): String = when (kind) {
        ModeKind.NOISE_REDUCTION -> noiseReductionName(value)
        ModeKind.EDGE -> edgeName(value)
        ModeKind.OIS -> oisName(value)
        ModeKind.VIDEO_STABILIZATION -> videoStabName(value)
        ModeKind.AWB -> awbName(value)
    }

    /** Called by the preview SurfaceView. */
    fun bindPreview(holder: SurfaceHolder) {
        scope.launch {
            mutex.withLock {
                previewHolder = holder
                preparePreview()
            }
        }
    }

    fun onPreviewChanged(holder: SurfaceHolder, width: Int, height: Int) {
        scope.launch {
            mutex.withLock {
                val wanted = previewSize() ?: return@withLock
                if (width == wanted.width && height == wanted.height) {
                    previewSurface = holder.surface
                    restartStill()
                } else {
                    mainHandler.post { holder.setFixedSize(wanted.width, wanted.height) }
                }
            }
        }
    }

    fun unbindPreview() {
        scope.launch {
            mutex.withLock {
                if (recorder != null) stopRecording(restart = false)
                closeSessions()
                device?.close()
                device = null
                previewSurface = null
                previewHolder = null
            }
        }
    }

    fun onResume() = orientationListener.enable()
    fun onPause() = orientationListener.disable()

    // ---------------------------------------------------------------- helpers

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Makes sure the preview surface has the size the current lens needs, then (re)starts the still session.
     * Resizing triggers surfaceChanged, which calls [onPreviewChanged] and restarts the session from there.
     */
    private suspend fun preparePreview() {
        val holder = previewHolder ?: return
        val wanted = previewSize() ?: return
        val frame = holder.surfaceFrame
        if (holder.surface.isValid && frame.width() == wanted.width && frame.height() == wanted.height) {
            previewSurface = holder.surface
            restartStill()
        } else {
            closeSessions()
            previewSurface = null
            mainHandler.post { holder.setFixedSize(wanted.width, wanted.height) }
        }
    }

    private fun launchCamera(status: String, block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                _uiState.update { it.copy(busy = true, status = status, error = null) }
                try {
                    block()
                    _uiState.update { it.copy(busy = false, status = "") }
                } catch (e: Exception) {
                    Log.e(TAG, status, e)
                    _uiState.update { it.copy(busy = false, status = "", error = "$status failed: ${e.message ?: e}") }
                    runCatching { if (recorder == null) restartStill() }
                }
            }
        }
    }

    private fun lens(): LensInfo? {
        val c = caps ?: return null
        return c.lenses.firstOrNull { it.key() == _settings.value.lensKey } ?: c.lenses.firstOrNull()
    }

    private fun options(): CameraOptions? = lens()?.let { caps?.cameras?.get(it.cameraId) }

    private fun logicalChars(): CameraCharacteristics? = lens()?.let { manager.getCameraCharacteristics(it.cameraId) }

    /** Characteristics of the lens actually producing frames (physical lens if one was picked). */
    private fun streamChars(): CameraCharacteristics? = lens()?.let {
        manager.getCameraCharacteristics(it.physicalId ?: it.cameraId)
    }

    private fun hdrActive(): Boolean {
        val o = options() ?: return false
        return _settings.value.hdrMode == HdrMode.ON && o.hdrExtensionSupported && lens()?.physicalId == null
    }

    /** 4:3 preview size usable by both regular and HDR-extension sessions. */
    private fun previewSize(): Size? {
        val l = lens() ?: return null
        val chars = manager.getCameraCharacteristics(l.cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        var sizes = map.getOutputSizes(SurfaceHolder::class.java).toList()
        if (options()?.hdrExtensionSupported == true) {
            val ext = runCatching {
                manager.getCameraExtensionCharacteristics(l.cameraId)
                    .getExtensionSupportedSizes(CameraExtensionCharacteristics.EXTENSION_HDR, SurfaceTexture::class.java)
            }.getOrNull().orEmpty()
            val both = sizes.filter { it in ext }
            if (both.isNotEmpty()) sizes = both
        }
        val fourThree = sizes.filter { it.width * 3 == it.height * 4 && it.height <= 1440 }
        return (fourThree.ifEmpty { sizes.filter { it.height <= 1440 } }.ifEmpty { sizes })
            .maxByOrNull { it.width * it.height }
    }

    private fun sanitize(s: AppSettings, c: DeviceCapabilities): AppSettings {
        val lens = c.lenses.firstOrNull { it.key() == s.lensKey } ?: c.lenses.first()
        val opts = c.cameras[lens.cameraId]
        val format = when {
            opts == null -> s.photoFormat
            s.photoFormat in opts.photoFormats || s.photoFormat in opts.hdrPhotoFormats -> s.photoFormat
            else -> opts.photoFormats.firstOrNull() ?: PhotoFormat.JPEG
        }
        fun valid(p: VideoPreset?): VideoPreset? = p?.takeIf { preset ->
            c.videoOptions.any {
                it.encoderName == preset.encoderName && it.width == preset.width && it.height == preset.height &&
                    it.fps == preset.fps && preset.bitrate in it.bitrates
            }
        }
        return s.copy(
            lensKey = lens.key(),
            photoFormat = format,
            highVideo = valid(s.highVideo) ?: defaultPreset(c.videoOptions, VideoKind.HIGH),
            lowVideo = valid(s.lowVideo) ?: defaultPreset(c.videoOptions, VideoKind.LOW),
            videoStabilizationMode = s.videoStabilizationMode?.takeIf { opts?.videoStabilizationModes?.contains(it) == true },
        )
    }

    private fun defaultPreset(options: List<VideoOption>, kind: VideoKind): VideoPreset? {
        fun score(o: VideoOption): Int = (if (o.hardware) 0 else 1000) + when (o.codec) {
            VideoCodec.HEVC -> 0; VideoCodec.AV1 -> 1; VideoCodec.H264 -> 2
        }
        val candidates = options.sortedBy { score(it) }
        return when (kind) {
            VideoKind.HIGH -> {
                val o = candidates.firstOrNull { it.width == 3840 && it.height == 2160 && it.fps == 30 }
                    ?: candidates.firstOrNull { it.width == 1920 && it.height == 1080 && it.fps == 30 }
                    ?: candidates.firstOrNull() ?: return null
                val bitrate = o.bitrates.filter { it <= 50_000_000 }.maxOrNull() ?: o.bitrates.last()
                VideoPreset(o.codec, o.encoderName, o.width, o.height, o.fps, bitrate)
            }
            VideoKind.LOW -> {
                val o = candidates.firstOrNull { it.width == 640 && it.height == 480 && it.fps == 15 }
                    ?: candidates.firstOrNull { it.height in 360..480 && it.fps <= 30 }
                    ?: candidates.minByOrNull { it.width * it.height } ?: return null
                val bitrate = o.bitrates.firstOrNull { it >= 200_000 } ?: o.bitrates.first()
                VideoPreset(o.codec, o.encoderName, o.width, o.height, o.fps, bitrate)
            }
        }
    }

    private fun jpegOrientation(): Int {
        val chars = streamChars() ?: return 0
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val device = if (front) -deviceOrientation else deviceOrientation
        return (sensor + device + 360) % 360
    }

    private fun exifOrientation(degrees: Int) = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun PhotoFormat.imageFormat(): Int = when (this) {
        PhotoFormat.JPEG -> ImageFormat.JPEG
        PhotoFormat.HEIC -> ImageFormat.HEIC
        PhotoFormat.JPEG_R -> ImageFormat.JPEG_R
        PhotoFormat.HEIC_ULTRAHDR -> if (Build.VERSION.SDK_INT >= 36) ImageFormat.HEIC_ULTRAHDR else ImageFormat.HEIC
    }

    // ---------------------------------------------------------------- sessions

    @SuppressLint("MissingPermission")
    private suspend fun ensureDevice(id: String): CameraDevice {
        device?.let { if (it.id == id) return it; it.close() }
        device = null
        val opened = suspendCancellableCoroutine<CameraDevice> { cont ->
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    _uiState.update { it.copy(ready = false, status = "Camera disconnected") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    if (cont.isActive) cont.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_ERROR, "Camera error $error"))
                    else _uiState.update { it.copy(ready = false, error = "Camera error $error") }
                }
            })
        }
        device = opened
        return opened
    }

    private fun closeSessions() {
        runCatching { session?.close() }
        runCatching { extSession?.close() }
        session = null
        extSession = null
        photoReader?.close()
        rawReader?.close()
        photoReader = null
        rawReader = null
        activePhotoFormat = null
        _uiState.update { it.copy(ready = false) }
    }

    private fun output(surface: Surface): OutputConfiguration = OutputConfiguration(surface).apply {
        lens()?.physicalId?.let { setPhysicalCameraId(it) }
    }

    private fun isSupported(dev: CameraDevice, outputs: List<OutputConfiguration>): Boolean? = try {
        val probe = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, NoopStateCallback)
        dev.isSessionConfigurationSupported(probe)
    } catch (e: Exception) {
        null // Query not available: unknown.
    }

    private suspend fun createRegularSession(dev: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s) else s.close()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Session configuration failed"))
                    }
                })
            dev.createCaptureSession(cfg)
        }

    private suspend fun createExtensionSession(dev: CameraDevice, outputs: List<OutputConfiguration>): CameraExtensionSession =
        suspendCancellableCoroutine { cont ->
            val cfg = ExtensionSessionConfiguration(CameraExtensionCharacteristics.EXTENSION_HDR, outputs, executor,
                object : CameraExtensionSession.StateCallback() {
                    override fun onConfigured(s: CameraExtensionSession) {
                        if (cont.isActive) cont.resume(s) else s.close()
                    }

                    override fun onConfigureFailed(s: CameraExtensionSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("HDR extension session failed"))
                    }
                })
            dev.createExtensionSession(cfg)
        }

    /** (Re)creates the preview + photo session for the current settings. */
    private suspend fun restartStill() {
        val surface = previewSurface ?: return
        val l = lens() ?: return
        val opts = options() ?: return
        closeSessions()
        val dev = ensureDevice(l.cameraId)
        val s = _settings.value

        if (hdrActive()) {
            val format = if (s.photoFormat in opts.hdrPhotoFormats) s.photoFormat else opts.hdrPhotoFormats.first()
            val imageFormat = if (format == PhotoFormat.HEIC) ImageFormat.YUV_420_888 else format.imageFormat()
            val size = manager.getCameraExtensionCharacteristics(l.cameraId)
                .getExtensionSupportedSizes(CameraExtensionCharacteristics.EXTENSION_HDR, imageFormat)
                .maxBy { it.width * it.height }
            val reader = ImageReader.newInstance(size.width, size.height, imageFormat, 2)
            photoReader = reader
            activePhotoFormat = format
            val ext = createExtensionSession(dev, listOf(OutputConfiguration(surface), OutputConfiguration(reader.surface)))
            extSession = ext
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
            ext.setRepeatingRequest(request, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
            _uiState.update { it.copy(ready = true, status = "") }
            return
        }

        val format = if (s.photoFormat in opts.photoFormats) s.photoFormat else opts.photoFormats.first()
        val chars = streamChars() ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        // HEIC without a native stream: full-size YUV, encoded once to HEIC after capture.
        val streamFormat = if (format == PhotoFormat.HEIC && opts.heicFromYuv) ImageFormat.YUV_420_888 else format.imageFormat()
        val photoSize = map.getOutputSizes(streamFormat)?.maxByOrNull { it.width * it.height }
            ?: error("${format.label} not available on this lens")
        val reader = ImageReader.newInstance(photoSize.width, photoSize.height, streamFormat, 2)
        photoReader = reader
        activePhotoFormat = format

        val base = listOf(output(surface), output(reader.surface))
        var outputs = base
        if (opts.rawSupported) {
            val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
            if (rawSize != null) {
                val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                val withRaw = base + output(raw.surface)
                if (isSupported(dev, withRaw) == true) {
                    rawReader = raw
                    outputs = withRaw
                } else {
                    raw.close()
                }
            }
        }
        session = createRegularSession(dev, outputs)
        startRegularPreview(dev, surface, video = false)
        _uiState.update { it.copy(ready = true, status = "") }
    }

    private fun startRegularPreview(dev: CameraDevice, surface: Surface, video: Boolean, extraTarget: Surface? = null) {
        val template = if (video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val request = dev.createCaptureRequest(template).apply {
            addTarget(surface)
            extraTarget?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_AF_MODE,
                if (video) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyCommon(this, video)
        }.build()
        session!!.setRepeatingRequest(request, null, handler)
    }

    /** Applies user-chosen controls, but only those this camera reported as supported. */
    private fun applyCommon(b: CaptureRequest.Builder, video: Boolean, fps: Int? = null) {
        val o = options() ?: return
        val m = _settings.value.manual
        if (o.manualSensor && m.manualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            o.isoRange?.let { b.set(CaptureRequest.SENSOR_SENSITIVITY, m.iso.coerceIn(it)) }
            var exposure = o.exposureRangeNs?.let { m.exposureTimeNs.coerceIn(it) } ?: m.exposureTimeNs
            if (video && fps != null) exposure = exposure.coerceAtMost(1_000_000_000L / fps)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (video && fps != null) 1_000_000_000L / fps else exposure)
        }
        if (m.manualFocus && o.minFocusDistance > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, m.focusDistance.coerceIn(0f, o.minFocusDistance))
        }
        m.noiseReductionMode?.takeIf { it in o.noiseReductionModes }?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
        m.edgeMode?.takeIf { it in o.edgeModes }?.let { b.set(CaptureRequest.EDGE_MODE, it) }
        m.oisMode?.takeIf { it in o.oisModes }?.let { b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it) }
        m.awbMode?.takeIf { it in o.awbModes }?.let { b.set(CaptureRequest.CONTROL_AWB_MODE, it) }
        if (video) {
            _settings.value.videoStabilizationMode?.takeIf { it in o.videoStabilizationModes }
                ?.let { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
        }
    }

    // ---------------------------------------------------------------- photo

    private suspend fun capturePhoto() {
        if (recorder != null) error("Stop recording first")
        if (session == null && extSession == null) restartStill()
        val reader = photoReader ?: error("Camera not ready")
        val format = activePhotoFormat ?: error("Camera not ready")
        val dev = device ?: error("Camera not ready")
        val orientation = jpegOrientation()
        val imageDeferred = awaitImage(reader)
        val resultDeferred = CompletableDeferred<TotalCaptureResult?>()

        val ext = extSession
        if (ext != null) {
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }.build()
            ext.capture(request, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureResultAvailable(session: CameraExtensionSession, request: CaptureRequest, result: TotalCaptureResult) {
                    resultDeferred.complete(result)
                }

                override fun onCaptureProcessProgressed(session: CameraExtensionSession, request: CaptureRequest, progress: Int) {
                    _uiState.update { it.copy(status = "HDR processing $progress%") }
                }

                override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                    imageDeferred.completeExceptionally(IllegalStateException("HDR capture failed"))
                    resultDeferred.complete(null)
                }
            })
        } else {
            val s = session ?: error("Camera not ready")
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(previewSurface!!)
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                applyCommon(this, video = false)
            }.build()
            s.capture(request, resultCallback(resultDeferred), handler)
        }

        val image = withTimeout(30_000) { imageDeferred.await() }
        // Some extensions never report metadata; don't wait forever for it.
        val result = withTimeoutOrNull(3_000) { resultDeferred.await() }
        try {
            val uri = withContext(Dispatchers.IO) {
                if (image.format == ImageFormat.YUV_420_888) saver.saveYuvAsHeic(image, orientation)
                else saver.saveEncoded(image, format.extension, format.mime)
            }
            val summary = InfoSection("Capture", listOfNotNull(
                "Format" to (if (image.format == ImageFormat.YUV_420_888) "HEIC (YUV encoded once by the HEIC encoder)" else format.label),
                "Size" to "${image.width}x${image.height}",
                "HDR" to if (ext != null) "On (Samsung HDR extension)" else "Off",
                "Lens" to (lens()?.label ?: "?"),
                "Orientation" to "$orientation°",
                "File" to uri.toString(),
            ))
            addRecord("Photo", uri, listOf(summary) + resultSections(result) + withContext(Dispatchers.IO) { saver.readExif(uri) })
        } finally {
            image.close()
        }
    }

    private suspend fun captureRaw() {
        if (recorder != null) error("Stop recording first")
        val opts = options() ?: error("Camera not ready")
        if (!opts.rawSupported) error("RAW is not supported on this camera")
        val surface = previewSurface ?: error("Camera not ready")

        // Reuse the still session when it already has a RAW stream; otherwise open a preview + RAW session.
        var temporary = false
        if (rawReader == null || session == null) {
            closeSessions()
            val dev = ensureDevice(lens()!!.cameraId)
            val map = streamChars()!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
            val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
            rawReader = raw
            session = createRegularSession(dev, listOf(output(surface), output(raw.surface)))
            startRegularPreview(dev, surface, video = false)
            temporary = true
        }
        val reader = rawReader!!
        val dev = device!!
        val imageDeferred = awaitImage(reader)
        val resultDeferred = CompletableDeferred<TotalCaptureResult?>()
        val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(surface)
            addTarget(reader.surface)
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            applyCommon(this, video = false)
        }.build()
        session!!.capture(request, resultCallback(resultDeferred), handler)
        val image = withTimeout(15_000) { imageDeferred.await() }
        val result = withTimeout(5_000) { resultDeferred.await() } ?: error("RAW capture failed")
        try {
            val orientation = jpegOrientation()
            val chars = streamChars()!!
            val uri = withContext(Dispatchers.IO) { saver.saveDng(image, chars, result, exifOrientation(orientation)) }
            val summary = InfoSection("Capture", listOf(
                "Format" to "DNG (RAW_SENSOR, lossless)",
                "Size" to "${image.width}x${image.height}",
                "Lens" to (lens()?.label ?: "?"),
                "File" to uri.toString(),
            ))
            addRecord("RAW", uri, listOf(summary) + resultSections(result) + withContext(Dispatchers.IO) { saver.readExif(uri) })
        } finally {
            image.close()
        }
        if (temporary) restartStill()
    }

    private fun awaitImage(reader: ImageReader): CompletableDeferred<Image> {
        // Drop stale frames from an earlier capture.
        while (true) reader.acquireLatestImage()?.close() ?: break
        val deferred = CompletableDeferred<Image>()
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            if (!deferred.complete(img)) img.close()
        }, handler)
        return deferred
    }

    private fun resultCallback(deferred: CompletableDeferred<TotalCaptureResult?>) =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                deferred.complete(result)
            }

            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                deferred.complete(null)
            }
        }

    /** Every key in the capture result (including vendor tags), plus per-physical-lens results. */
    private fun resultSections(result: TotalCaptureResult?): List<InfoSection> {
        if (result == null) return listOf(InfoSection("Capture result", listOf("metadata" to "not reported for this capture")))
        val sections = mutableListOf(resultSection("Capture result (sensor metadata)", result))
        runCatching {
            result.physicalCameraTotalResults.forEach { (id, r) -> sections += resultSection("Physical lens $id result", r) }
        }
        return sections
    }

    private fun resultSection(title: String, r: CaptureResult): InfoSection =
        InfoSection(title, r.keys.sortedBy { it.name }.map { key ->
            val v = runCatching { r.get(key) }.getOrNull()
            key.name to (describeKnown(key.name, v) ?: formatValue(v))
        })

    private fun addRecord(title: String, uri: Uri, sections: List<InfoSection>) {
        _captures.update { listOf(CaptureRecord(title, uri.toString(), sections)) + it }
    }

    // ---------------------------------------------------------------- video

    private suspend fun startRecording(kind: VideoKind) {
        val s = _settings.value
        val preset = (if (kind == VideoKind.LOW) s.lowVideo else s.highVideo) ?: error("No validated video setting yet")
        val surface = previewSurface ?: error("Camera not ready")
        closeSessions()
        val dev = ensureDevice(lens()!!.cameraId)
        val (uri, pfd) = withContext(Dispatchers.IO) { saver.createVideo() }
        val chars = streamChars()!!
        val realtime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val audio = s.recordAudio &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rec = try {
            VideoRecorder(preset, pfd.fileDescriptor, jpegOrientation(), audio) {
                if (realtime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
            }
        } catch (e: Exception) {
            pfd.close()
            saver.deleteQuietly(uri)
            throw e
        }
        try {
            session = createRegularSession(dev, listOf(output(surface), output(rec.inputSurface)))
            rec.start()
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                addTarget(rec.inputSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(preset.fps, preset.fps))
                applyCommon(this, video = true, fps = preset.fps)
            }.build()
            session!!.setRepeatingRequest(request, null, handler)
        } catch (e: Exception) {
            runCatching { rec.stop() }
            pfd.close()
            saver.deleteQuietly(uri)
            throw e
        }
        recorder = rec
        recordingUri = uri
        recordingPfd = pfd
        recordingPreset = preset
        _uiState.update { it.copy(ready = true, recording = kind, recordingSeconds = 0) }
        recordingTimer = scope.launch {
            var seconds = 0
            while (true) {
                delay(1000)
                seconds++
                _uiState.update { it.copy(recordingSeconds = seconds) }
            }
        }
    }

    private suspend fun stopRecording(restart: Boolean = true) {
        val rec = recorder ?: return
        recordingTimer?.cancel()
        runCatching { session?.stopRepeating() }
        closeSessions()
        withContext(Dispatchers.IO) { rec.stop() }
        recorder = null
        recordingPfd?.close()
        recordingPfd = null
        val uri = recordingUri!!
        val preset = recordingPreset!!
        withContext(Dispatchers.IO) { saver.finishVideo(uri) }
        _uiState.update { it.copy(recording = null, recordingSeconds = 0) }
        val summary = InfoSection("Recording settings", listOf(
            "Encoder" to preset.encoderName,
            "Codec" to preset.codec.label,
            "Resolution" to "${preset.width}x${preset.height}",
            "Frame rate" to "${preset.fps} fps",
            "Target video bitrate" to formatBitrate(preset.bitrate),
            "Lens" to (lens()?.label ?: "?"),
            "File" to uri.toString(),
        ))
        addRecord("Video", uri, listOf(summary) + withContext(Dispatchers.IO) { saver.readVideo(uri) })
        if (restart) restartStill()
    }

    private object NoopStateCallback : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {}
        override fun onConfigureFailed(session: CameraCaptureSession) {}
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
