package dev.camerakmp.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.util.Size
import dev.camerakmp.model.VideoCodec
import dev.camerakmp.model.VideoOption

/**
 * Proves which video configurations really work on this phone, so the settings screen never offers
 * a resolution / frame rate / bitrate combination that fails at record time.
 *
 * For each encoder × camera size we check the encoder's declared limits, then actually configure and
 * start the encoder with a camera-style input surface at the lowest and highest bitrate we intend to offer.
 * Results are cached per build fingerprint because the scan takes a while.
 */
internal class VideoValidator(private val context: Context) {

    fun validate(
        characteristics: CameraCharacteristics,
        progress: (String, Float) -> Unit,
    ): List<VideoOption> {
        loadCache()?.let { return it }

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val cameraSizes = map.getOutputSizes(MediaCodec::class.java)
            .filter { it.width >= 176 && it.height >= 144 }
            .sortedByDescending { it.width * it.height }
        val fpsCandidates = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.lower == it.upper }
            ?.map { it.upper }
            ?.distinct()
            ?.sortedDescending()
            ?.ifEmpty { null }
            ?: listOf(30)

        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && !it.isAlias }
            .flatMap { info ->
                VideoCodec.entries.filter { codec -> info.supportedTypes.any { it.equals(codec.mime, true) } }
                    .map { codec -> info to codec }
            }

        val results = mutableListOf<VideoOption>()
        val total = (encoders.size * cameraSizes.size).coerceAtLeast(1)
        var done = 0
        for ((info, codec) in encoders) {
            val caps = info.getCapabilitiesForType(codec.mime)
            val video = caps.videoCapabilities ?: continue
            val hardware = info.isHardwareAccelerated
            for (size in cameraSizes) {
                done++
                progress("Testing ${info.name} at ${size.width}x${size.height}", done.toFloat() / total)
                // Software encoders claim huge sizes but cannot keep up; keep them to 1080p.
                if (!hardware && size.width * size.height > 1920 * 1080) continue
                if (!video.isSizeSupported(size.width, size.height)) continue

                val minFrameNs = map.getOutputMinFrameDuration(MediaCodec::class.java, size)
                val fpsForSize = fpsCandidates.filter { fps ->
                    (minFrameNs == 0L || minFrameNs <= 1_000_000_000L / fps) &&
                        video.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
                }
                if (fpsForSize.isEmpty()) continue

                val declared = video.bitrateRange
                val ladder = BITRATE_LADDER.filter { it in declared.lower..declared.upper }
                if (ladder.isEmpty()) continue

                // Highest fps first; a configuration that works at a high rate works at lower ones too.
                val workingFps = mutableListOf<Int>()
                var bitrates: List<Int>? = null
                for (fps in fpsForSize) {
                    val current = bitrates
                    if (current == null) {
                        val found = findWorkingBitrates(info.name, codec.mime, size, fps, ladder)
                        if (found != null) {
                            bitrates = found
                            workingFps += fps
                        }
                    } else if (tryConfigure(info.name, codec.mime, size, fps, current.first())) {
                        workingFps += fps
                    }
                }
                val confirmed = bitrates ?: continue
                for (fps in workingFps) {
                    results += VideoOption(codec, info.name, hardware, size.width, size.height, fps, confirmed)
                }
            }
        }
        saveCache(results)
        return results
    }

    /** Trims the ladder from both ends until the encoder accepts the lowest and highest bitrate. */
    private fun findWorkingBitrates(name: String, mime: String, size: Size, fps: Int, ladder: List<Int>): List<Int>? {
        var low = 0
        while (low < ladder.size && !tryConfigure(name, mime, size, fps, ladder[low])) low++
        if (low == ladder.size) return null
        var high = ladder.lastIndex
        while (high > low && !tryConfigure(name, mime, size, fps, ladder[high])) high--
        return ladder.subList(low, high + 1)
    }

    private fun tryConfigure(name: String, mime: String, size: Size, fps: Int, bitrate: Int): Boolean {
        val codec = try {
            MediaCodec.createByCodecName(name)
        } catch (e: Exception) {
            return false
        }
        return try {
            codec.configure(
                encoderFormat(mime, size.width, size.height, fps, bitrate, codec.codecInfo),
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            val surface = codec.createInputSurface()
            codec.start()
            codec.stop()
            surface.release()
            true
        } catch (e: Exception) {
            Log.d(TAG, "Rejected $name ${size.width}x${size.height}@$fps $bitrate: ${e.message}")
            false
        } finally {
            codec.release()
        }
    }

    private fun cacheKey() = "video_options_v1_${Build.FINGERPRINT}"

    private fun loadCache(): List<VideoOption>? {
        val prefs = context.getSharedPreferences("scan_cache", Context.MODE_PRIVATE)
        val raw = prefs.getString(cacheKey(), null) ?: return null
        return runCatching {
            raw.lines().filter { it.isNotBlank() }.map { line ->
                val p = line.split(";")
                VideoOption(
                    codec = VideoCodec.valueOf(p[0]),
                    encoderName = p[1],
                    hardware = p[2].toBoolean(),
                    width = p[3].toInt(),
                    height = p[4].toInt(),
                    fps = p[5].toInt(),
                    bitrates = p[6].split(",").map { it.toInt() },
                )
            }
        }.getOrNull()
    }

    private fun saveCache(options: List<VideoOption>) {
        val text = options.joinToString("\n") {
            listOf(it.codec.name, it.encoderName, it.hardware, it.width, it.height, it.fps, it.bitrates.joinToString(","))
                .joinToString(";")
        }
        context.getSharedPreferences("scan_cache", Context.MODE_PRIVATE).edit()
            .putString(cacheKey(), text).apply()
    }

    fun clearCache() {
        context.getSharedPreferences("scan_cache", Context.MODE_PRIVATE).edit().clear().apply()
    }

    companion object {
        private const val TAG = "VideoValidator"

        /** Low ladder for "low bitrate" video, high ladder for normal recording. */
        val BITRATE_LADDER = listOf(
            32_000, 50_000, 75_000, 100_000, 150_000, 200_000, 300_000, 400_000, 500_000, 750_000,
            1_000_000, 1_500_000, 2_000_000, 3_000_000, 4_000_000, 6_000_000, 8_000_000, 10_000_000,
            12_000_000, 16_000_000, 20_000_000, 25_000_000, 30_000_000, 40_000_000, 50_000_000,
            60_000_000, 80_000_000, 100_000_000, 120_000_000,
        )
        const val LOW_BITRATE_MAX = 1_500_000

        fun encoderFormat(
            mime: String, width: Int, height: Int, fps: Int, bitrate: Int, info: MediaCodecInfo,
        ): MediaFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Long GOP saves a lot of bits at low bitrates; 2 s keeps seeking reasonable at high ones.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, if (bitrate <= LOW_BITRATE_MAX) 5 else 2)
            val encoderCaps = info.getCapabilitiesForType(mime).encoderCapabilities
            if (encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }
    }
}
