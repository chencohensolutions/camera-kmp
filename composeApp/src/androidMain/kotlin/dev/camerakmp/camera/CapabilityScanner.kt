package dev.camerakmp.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecList
import android.os.Build
import dev.camerakmp.model.CameraOptions
import dev.camerakmp.model.DeviceCapabilities
import dev.camerakmp.model.InfoSection
import dev.camerakmp.model.LensInfo
import dev.camerakmp.model.PhotoFormat

/** Reads everything Camera2, the extension framework and the codec list publish for this device. */
internal class CapabilityScanner(
    private val context: Context,
    private val manager: CameraManager,
) {
    val videoValidator = VideoValidator(context)

    fun scan(progress: (String, Float) -> Unit): DeviceCapabilities {
        progress("Reading cameras", 0f)
        val dump = mutableListOf<InfoSection>()
        dump += deviceSection()

        val lenses = mutableListOf<LensInfo>()
        val cameras = linkedMapOf<String, CameraOptions>()
        val ids = manager.cameraIdList

        for (id in ids) {
            val ch = manager.getCameraCharacteristics(id)
            val facing = facingName(ch.get(CameraCharacteristics.LENS_FACING))
            val physical = ch.physicalCameraIds
            lenses += LensInfo(id, null, "$facing camera $id${if (physical.isNotEmpty()) " (auto lens switching)" else ""} · ${focalText(ch)}")
            for (pid in physical) {
                val pch = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                lenses += LensInfo(id, pid, "$facing lens $pid (inside camera $id) · ${focalText(pch)}")
                dump += characteristicsSection("Physical lens $pid (of camera $id)", pch)
            }
            cameras[id] = options(id, ch)
            dump += characteristicsSection("Camera $id · $facing", ch)
            dump += keysSection(id, ch)
            dump += extensionSection(id)
        }

        val mainBack = ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        } ?: ids.first()
        val video = videoValidator.validate(manager.getCameraCharacteristics(mainBack)) { msg, p ->
            progress(msg, 0.1f + p * 0.9f)
        }
        dump += encoderSection()
        dump += InfoSection(
            "Validated video configurations (camera $mainBack)",
            video.map { "${it.codec.label} ${it.sizeLabel}@${it.fps}" to "${it.encoderName}${if (it.hardware) " (hw)" else " (sw)"} · ${it.bitrates.first()}–${it.bitrates.last()} bps" },
        )

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        return DeviceCapabilities(deviceName, lenses, cameras, video, dump)
    }

    private fun focalText(ch: CameraCharacteristics): String {
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") { "%.2f".format(it) }
        val aperture = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString("/") { "f/%.1f".format(it) }
        return listOfNotNull(focal?.let { "${it}mm" }, aperture).joinToString(" ")
    }

    private fun options(id: String, ch: CameraCharacteristics): CameraOptions {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val formats = map?.outputFormats?.toSet().orEmpty()
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()

        val photoFormats = buildList {
            if (Build.VERSION.SDK_INT >= 36 && ImageFormat.HEIC_ULTRAHDR in formats) add(PhotoFormat.HEIC_ULTRAHDR)
            if (ImageFormat.HEIC in formats) add(PhotoFormat.HEIC)
            if (ImageFormat.JPEG_R in formats) add(PhotoFormat.JPEG_R)
            if (ImageFormat.JPEG in formats) add(PhotoFormat.JPEG)
        }

        var hdr = false
        val hdrFormats = mutableListOf<PhotoFormat>()
        runCatching {
            val ext = manager.getCameraExtensionCharacteristics(id)
            if (CameraExtensionCharacteristics.EXTENSION_HDR in ext.supportedExtensions) {
                hdr = true
                fun has(format: Int) = runCatching {
                    ext.getExtensionSupportedSizes(CameraExtensionCharacteristics.EXTENSION_HDR, format).isNotEmpty()
                }.getOrDefault(false)
                // HEIC in HDR mode = YUV from the extension encoded once by HeifWriter.
                if (has(ImageFormat.YUV_420_888)) hdrFormats += PhotoFormat.HEIC
                if (has(ImageFormat.JPEG_R)) hdrFormats += PhotoFormat.JPEG_R
                if (has(ImageFormat.JPEG)) hdrFormats += PhotoFormat.JPEG
            }
        }

        return CameraOptions(
            cameraId = id,
            photoFormats = photoFormats,
            rawSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps && ImageFormat.RAW_SENSOR in formats,
            hdrExtensionSupported = hdr && hdrFormats.isNotEmpty(),
            hdrPhotoFormats = hdrFormats,
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper },
            exposureRangeNs = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper },
            minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            noiseReductionModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty(),
            edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty(),
            oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty(),
            videoStabilizationModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty(),
            awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty(),
        )
    }

    private fun deviceSection() = InfoSection(
        "Device",
        listOf(
            "Manufacturer" to Build.MANUFACTURER,
            "Model" to Build.MODEL,
            "Device" to Build.DEVICE,
            "SoC" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Security patch" to Build.VERSION.SECURITY_PATCH,
            "Build" to Build.DISPLAY,
            "Fingerprint" to Build.FINGERPRINT,
        ),
    )

    /** Every static key, including OEM vendor tags (they appear in getKeys()). */
    private fun characteristicsSection(title: String, ch: CameraCharacteristics): InfoSection {
        val entries = ch.keys.sortedBy { it.name }.map { key ->
            val value = runCatching { ch.get(key) }.getOrNull()
            key.name to (describeKnown(key.name, value) ?: formatValue(value))
        }
        return InfoSection(title, entries)
    }

    private fun keysSection(id: String, ch: CameraCharacteristics): InfoSection {
        val entries = mutableListOf<Pair<String, String>>()
        ch.availableCaptureRequestKeys.sortedBy { it.name }.forEach { entries += "request" to it.name }
        ch.availableCaptureResultKeys.sortedBy { it.name }.forEach { entries += "result" to it.name }
        ch.availableSessionKeys.sortedBy { it.name }.forEach { entries += "session" to it.name }
        ch.availablePhysicalCameraRequestKeys.sortedBy { it.name }.forEach { entries += "physical request" to it.name }
        return InfoSection("Camera $id controllable and reported keys", entries)
    }

    private fun extensionSection(id: String): InfoSection {
        val entries = mutableListOf<Pair<String, String>>()
        runCatching {
            val ext = manager.getCameraExtensionCharacteristics(id)
            val names = mapOf(
                CameraExtensionCharacteristics.EXTENSION_AUTOMATIC to "Auto",
                CameraExtensionCharacteristics.EXTENSION_BOKEH to "Bokeh",
                CameraExtensionCharacteristics.EXTENSION_HDR to "HDR",
                CameraExtensionCharacteristics.EXTENSION_NIGHT to "Night",
                CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH to "Face retouch",
            )
            val supported = ext.supportedExtensions
            if (supported.isEmpty()) entries += "Extensions" to "none"
            for (e in supported) {
                val name = names[e] ?: "Extension $e"
                val formats = listOf(ImageFormat.JPEG to "JPEG", ImageFormat.YUV_420_888 to "YUV", ImageFormat.JPEG_R to "JPEG_R")
                    .mapNotNull { (f, n) ->
                        runCatching { ext.getExtensionSupportedSizes(e, f) }.getOrNull()
                            ?.takeIf { it.isNotEmpty() }?.let { "$n ${it.maxByOrNull { s -> s.width * s.height }}" }
                    }
                entries += name to formats.joinToString(", ").ifEmpty { "supported" }
                runCatching {
                    entries += "$name result keys" to ext.getAvailableCaptureResultKeys(e).joinToString { it.name }
                }
            }
        }.onFailure { entries += "Extensions" to "error: ${it.message}" }
        return InfoSection("Camera $id extensions", entries)
    }

    private fun encoderSection(): InfoSection {
        val entries = mutableListOf<Pair<String, String>>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                val caps = info.getCapabilitiesForType(type)
                val video = caps.videoCapabilities
                val audio = caps.audioCapabilities
                val detail = when {
                    video != null -> "bitrate ${video.bitrateRange}, width ${video.supportedWidths}, height ${video.supportedHeights}, fps ${video.supportedFrameRates}"
                    audio != null -> "bitrate ${audio.bitrateRange}, max channels ${audio.maxInputChannelCount}"
                    else -> ""
                }
                val modes = caps.encoderCapabilities?.let { ec ->
                    listOf(0 to "CQ", 1 to "VBR", 2 to "CBR").filter { ec.isBitrateModeSupported(it.first) }.joinToString("/") { it.second }
                }
                entries += "${info.name} ($type)" to listOfNotNull(
                    if (info.isHardwareAccelerated) "hardware" else "software",
                    detail, modes?.let { "modes $it" },
                ).joinToString(" · ")
            }
        }
        return InfoSection("Encoders", entries)
    }
}
