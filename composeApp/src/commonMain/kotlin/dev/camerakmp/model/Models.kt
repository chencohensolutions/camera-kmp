package dev.camerakmp.model

/** Compressed photo formats the camera pipeline can write directly (each encoded exactly once). */
enum class PhotoFormat(val label: String, val extension: String, val mime: String) {
    HEIC("HEIC", "heic", "image/heic"),
    HEIC_ULTRAHDR("HEIC Ultra HDR", "heic", "image/heic"),
    JPEG_R("JPEG Ultra HDR (JPEG_R)", "jpg", "image/jpeg"),
    JPEG("JPEG", "jpg", "image/jpeg"),
}

enum class VideoCodec(val label: String, val mime: String) {
    AV1("AV1", "video/av01"),
    HEVC("HEVC (H.265)", "video/hevc"),
    H264("H.264 (AVC)", "video/avc"),
}

/** Which bitrate family a video preset belongs to. */
enum class VideoKind { HIGH, LOW }

/** A video configuration that was verified on this device's encoder and camera. */
data class VideoOption(
    val codec: VideoCodec,
    val encoderName: String,
    val hardware: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Bitrates (bits per second) inside the encoder's declared range that configured successfully. */
    val bitrates: List<Int>,
) {
    val key: String get() = "$encoderName|${width}x$height@$fps"
    val sizeLabel: String get() = "${width}x$height"
}

data class VideoPreset(
    val codec: VideoCodec,
    val encoderName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
) {
    val label: String
        get() = "${codec.label} · ${width}x$height · $fps fps · ${formatBitrate(bitrate)}"
}

data class LensInfo(
    /** Camera id to open (logical camera). */
    val cameraId: String,
    /** Physical sub-camera, when the logical camera exposes one; null = let the device switch lenses. */
    val physicalId: String?,
    val label: String,
)

/** A titled list of name/value pairs, used for capability dumps and per-photo details. */
data class InfoSection(val title: String, val entries: List<Pair<String, String>>)

/** Options that the capability scan proved the device supports, per logical camera. */
data class CameraOptions(
    val cameraId: String,
    val photoFormats: List<PhotoFormat>,
    val rawSupported: Boolean,
    val hdrExtensionSupported: Boolean,
    /** Extension output formats usable for HDR stills (subset of JPEG / HEIC-from-YUV / JPEG_R). */
    val hdrPhotoFormats: List<PhotoFormat>,
    val manualSensor: Boolean,
    val isoRange: IntRange?,
    val exposureRangeNs: LongRange?,
    val minFocusDistance: Float,
    val noiseReductionModes: List<Int>,
    val edgeModes: List<Int>,
    val oisModes: List<Int>,
    val videoStabilizationModes: List<Int>,
    val awbModes: List<Int>,
)

data class DeviceCapabilities(
    val deviceName: String,
    val lenses: List<LensInfo>,
    val cameras: Map<String, CameraOptions>,
    val videoOptions: List<VideoOption>,
    val dump: List<InfoSection>,
)

enum class HdrMode(val label: String) { OFF("Off (single capture)"), ON("On (Samsung HDR extension)") }

data class ManualControls(
    val manualExposure: Boolean = false,
    val iso: Int = 100,
    val exposureTimeNs: Long = 10_000_000L,
    val manualFocus: Boolean = false,
    /** Diopters (1/m); 0 = infinity. */
    val focusDistance: Float = 0f,
    val noiseReductionMode: Int? = null,
    val edgeMode: Int? = null,
    val oisMode: Int? = null,
    val awbMode: Int? = null,
)

data class AppSettings(
    val lensKey: String? = null,
    val photoFormat: PhotoFormat = PhotoFormat.HEIC,
    val hdrMode: HdrMode = HdrMode.OFF,
    val highVideo: VideoPreset? = null,
    val lowVideo: VideoPreset? = null,
    val videoStabilizationMode: Int? = null,
    val recordAudio: Boolean = true,
    val manual: ManualControls = ManualControls(),
)

fun LensInfo.key(): String = "$cameraId/${physicalId ?: "-"}"

fun DeviceCapabilities.selectedLens(lensKey: String?): LensInfo? =
    lenses.firstOrNull { it.key() == lensKey } ?: lenses.firstOrNull()

fun DeviceCapabilities.optionsFor(lensKey: String?): CameraOptions? =
    selectedLens(lensKey)?.let { cameras[it.cameraId] }

data class CaptureRecord(
    val title: String,
    val uri: String,
    val sections: List<InfoSection>,
)

fun formatBitrate(bps: Int): String = when {
    bps >= 1_000_000 -> {
        val m = bps / 1_000_000.0
        if (m % 1.0 == 0.0) "${m.toInt()} Mbps" else "${(m * 10).toInt() / 10.0} Mbps"
    }
    else -> "${bps / 1000} kbps"
}

fun formatExposure(ns: Long): String = when {
    ns >= 1_000_000_000L -> "${ns / 1_000_000_000.0} s"
    ns >= 1_000_000L -> "1/${(1_000_000_000.0 / ns).toInt()} s (${ns / 1_000_000.0} ms)"
    else -> "${ns / 1000} µs"
}
