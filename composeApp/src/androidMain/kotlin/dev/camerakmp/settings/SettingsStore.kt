package dev.camerakmp.settings

import android.content.Context
import android.content.SharedPreferences
import dev.camerakmp.model.AppSettings
import dev.camerakmp.model.HdrMode
import dev.camerakmp.model.ManualControls
import dev.camerakmp.model.PhotoFormat
import dev.camerakmp.model.VideoCodec
import dev.camerakmp.model.VideoPreset

internal class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        lensKey = prefs.getString("lens", null),
        photoFormat = enumOr(prefs.getString("photoFormat", null), PhotoFormat.HEIC),
        hdrMode = enumOr(prefs.getString("hdr", null), HdrMode.OFF),
        highVideo = prefs.getString("highVideo", null)?.let(::decodePreset),
        lowVideo = prefs.getString("lowVideo", null)?.let(::decodePreset),
        videoStabilizationMode = prefs.intOrNull("videoStab"),
        recordAudio = prefs.getBoolean("audio", true),
        manual = ManualControls(
            manualExposure = prefs.getBoolean("manualExposure", false),
            iso = prefs.getInt("iso", 100),
            exposureTimeNs = prefs.getLong("exposureNs", 10_000_000L),
            manualFocus = prefs.getBoolean("manualFocus", false),
            focusDistance = prefs.getFloat("focusDistance", 0f),
            noiseReductionMode = prefs.intOrNull("nr"),
            edgeMode = prefs.intOrNull("edge"),
            oisMode = prefs.intOrNull("ois"),
            awbMode = prefs.intOrNull("awb"),
        ),
    )

    fun save(s: AppSettings) {
        prefs.edit().apply {
            putString("lens", s.lensKey)
            putString("photoFormat", s.photoFormat.name)
            putString("hdr", s.hdrMode.name)
            putString("highVideo", s.highVideo?.let(::encodePreset))
            putString("lowVideo", s.lowVideo?.let(::encodePreset))
            putIntOrRemove("videoStab", s.videoStabilizationMode)
            putBoolean("audio", s.recordAudio)
            putBoolean("manualExposure", s.manual.manualExposure)
            putInt("iso", s.manual.iso)
            putLong("exposureNs", s.manual.exposureTimeNs)
            putBoolean("manualFocus", s.manual.manualFocus)
            putFloat("focusDistance", s.manual.focusDistance)
            putIntOrRemove("nr", s.manual.noiseReductionMode)
            putIntOrRemove("edge", s.manual.edgeMode)
            putIntOrRemove("ois", s.manual.oisMode)
            putIntOrRemove("awb", s.manual.awbMode)
        }.apply()
    }

    private fun SharedPreferences.intOrNull(key: String): Int? = if (contains(key)) getInt(key, 0) else null

    private fun SharedPreferences.Editor.putIntOrRemove(key: String, value: Int?) {
        if (value == null) remove(key) else putInt(key, value)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    private fun encodePreset(p: VideoPreset) =
        listOf(p.codec.name, p.encoderName, p.width, p.height, p.fps, p.bitrate).joinToString(";")

    private fun decodePreset(s: String): VideoPreset? = runCatching {
        val p = s.split(";")
        VideoPreset(VideoCodec.valueOf(p[0]), p[1], p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt())
    }.getOrNull()
}
