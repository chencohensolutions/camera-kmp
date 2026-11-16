package dev.camerakmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.camerakmp.model.AppSettings
import dev.camerakmp.model.CameraController
import dev.camerakmp.model.CameraOptions
import dev.camerakmp.model.DeviceCapabilities
import dev.camerakmp.model.HdrMode
import dev.camerakmp.model.ModeKind
import dev.camerakmp.model.ScanState
import dev.camerakmp.model.VideoKind
import dev.camerakmp.model.VideoOption
import dev.camerakmp.model.VideoPreset
import dev.camerakmp.model.formatBitrate
import dev.camerakmp.model.formatExposure
import dev.camerakmp.model.key
import dev.camerakmp.model.optionsFor
import dev.camerakmp.model.selectedLens
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Bitrates at or below this are offered for "low bitrate" video; above it for normal video. */
private const val LOW_MAX = 1_500_000

@Composable
fun SettingsScreen(controller: CameraController, onBack: () -> Unit, onCapabilities: () -> Unit) {
    val scan by controller.scanState.collectAsState()
    val settings by controller.settings.collectAsState()
    val caps = (scan as? ScanState.Done)?.capabilities

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
            if (caps == null) {
                Text("Still checking the device. Settings appear when the scan finishes.", Modifier.padding(16.dp))
                return@Column
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(caps.deviceName, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCapabilities) { Text("All capabilities") }
                    OutlinedButton(onClick = { controller.scan(force = true) }) { Text("Re-test device") }
                }
                Text(
                    "Only options this phone reported and passed testing are listed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SettingsBody(controller, caps, settings)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsBody(controller: CameraController, caps: DeviceCapabilities, settings: AppSettings) {
    val update = controller::updateSettings
    val lens = caps.selectedLens(settings.lensKey)
    val opts = caps.optionsFor(settings.lensKey) ?: return

    Section("Lens")
    for (l in caps.lenses) {
        Choice(l.label, selected = l.key() == lens?.key()) { update { it.copy(lensKey = l.key()) } }
    }

    Section("HDR")
    if (opts.hdrExtensionSupported) {
        val physical = lens?.physicalId != null
        Chips(HdrMode.entries, settings.hdrMode, { it.label }) { mode -> update { it.copy(hdrMode = mode) } }
        if (physical) Note("HDR uses the camera's automatic lens; it is ignored while a single physical lens is selected.")
        else Note("On: Samsung's HDR processing via the Camera2 HDR extension. Off: a single normal capture.")
    } else {
        Note("This camera does not offer the HDR extension.")
    }

    Section("Photo format (when not RAW)")
    val hdrOn = settings.hdrMode == HdrMode.ON && opts.hdrExtensionSupported && lens?.physicalId == null
    val formats = if (hdrOn) opts.hdrPhotoFormats else opts.photoFormats
    Chips(formats, settings.photoFormat, { it.label }) { f -> update { it.copy(photoFormat = f) } }
    Note("Every format is written once by the camera pipeline; nothing is re-encoded. RAW is saved as lossless DNG.")
    if (!opts.rawSupported) Note("RAW is not available on this camera.")

    Section("Video")
    VideoPicker("Normal video", caps.videoOptions.filter { it.bitrates.any { b -> b > LOW_MAX } }, settings.highVideo, VideoKind.HIGH) { p ->
        update { it.copy(highVideo = p) }
    }
    Spacer(Modifier.height(8.dp))
    VideoPicker("Low-bitrate video", caps.videoOptions.filter { it.bitrates.any { b -> b <= LOW_MAX } }, settings.lowVideo, VideoKind.LOW) { p ->
        update { it.copy(lowVideo = p) }
    }
    if (opts.videoStabilizationModes.isNotEmpty()) {
        Label("Video stabilization")
        ModeChips(controller, ModeKind.VIDEO_STABILIZATION, opts.videoStabilizationModes, settings.videoStabilizationMode) { v ->
            update { it.copy(videoStabilizationMode = v) }
        }
    }
    Toggle("Record audio", settings.recordAudio) { v -> update { it.copy(recordAudio = v) } }

    Section("Pro controls")
    ProControls(controller, opts, settings)
}

@Composable
private fun ProControls(controller: CameraController, opts: CameraOptions, settings: AppSettings) {
    val update = controller::updateSettings
    val m = settings.manual
    val iso = opts.isoRange
    val expRange = opts.exposureRangeNs
    if (opts.manualSensor && iso != null && expRange != null) {
        Toggle("Manual exposure", m.manualExposure) { v -> update { it.copy(manual = it.manual.copy(manualExposure = v)) } }
        if (m.manualExposure) {
            Label("ISO ${m.iso} (${iso.first}–${iso.last})")
            Slider(
                value = m.iso.toFloat(),
                onValueChange = { v -> update { it.copy(manual = it.manual.copy(iso = v.roundToInt())) } },
                valueRange = iso.first.toFloat()..iso.last.toFloat(),
            )
            Label("Shutter ${formatExposure(m.exposureTimeNs)}")
            val lo = ln(expRange.first.toDouble())
            val hi = ln(expRange.last.toDouble())
            Slider(
                value = ((ln(m.exposureTimeNs.toDouble()) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f),
                onValueChange = { t ->
                    val ns = exp(lo + t * (hi - lo)).toLong().coerceIn(expRange.first, expRange.last)
                    update { it.copy(manual = it.manual.copy(exposureTimeNs = ns)) }
                },
            )
        }
    } else {
        Note("Manual exposure is not offered by this camera.")
    }
    if (opts.minFocusDistance > 0f) {
        Toggle("Manual focus", m.manualFocus) { v -> update { it.copy(manual = it.manual.copy(manualFocus = v)) } }
        if (m.manualFocus) {
            val distance = if (m.focusDistance <= 0f) "infinity" else "${((100f / m.focusDistance).roundToInt())} cm"
            Label("Focus $distance")
            Slider(
                value = m.focusDistance,
                onValueChange = { v -> update { it.copy(manual = it.manual.copy(focusDistance = v)) } },
                valueRange = 0f..opts.minFocusDistance,
            )
        }
    }
    if (opts.noiseReductionModes.isNotEmpty()) {
        Label("Noise reduction")
        ModeChips(controller, ModeKind.NOISE_REDUCTION, opts.noiseReductionModes, m.noiseReductionMode) { v ->
            update { it.copy(manual = it.manual.copy(noiseReductionMode = v)) }
        }
    }
    if (opts.edgeModes.isNotEmpty()) {
        Label("Sharpening (edge mode)")
        ModeChips(controller, ModeKind.EDGE, opts.edgeModes, m.edgeMode) { v ->
            update { it.copy(manual = it.manual.copy(edgeMode = v)) }
        }
    }
    if (opts.oisModes.size > 1) {
        Label("Optical stabilization")
        ModeChips(controller, ModeKind.OIS, opts.oisModes, m.oisMode) { v ->
            update { it.copy(manual = it.manual.copy(oisMode = v)) }
        }
    }
    if (opts.awbModes.isNotEmpty()) {
        Label("White balance")
        ModeChips(controller, ModeKind.AWB, opts.awbModes.filter { it != 0 }, m.awbMode) { v ->
            update { it.copy(manual = it.manual.copy(awbMode = v)) }
        }
    }
    Note("Pro controls apply to normal photos, RAW and video. The HDR extension uses Samsung's own processing instead.")
}

/** Cascading pickers: encoder → resolution → frame rate → bitrate, each limited to tested combinations. */
@Composable
private fun VideoPicker(
    title: String,
    options: List<VideoOption>,
    current: VideoPreset?,
    kind: VideoKind,
    onChange: (VideoPreset) -> Unit,
) {
    Label(title)
    if (options.isEmpty()) {
        Note("No tested configuration fits this category.")
        return
    }
    fun bitratesOf(o: VideoOption) = o.bitrates.filter { if (kind == VideoKind.LOW) it <= LOW_MAX else it > LOW_MAX }

    fun pick(encoder: String, width: Int?, height: Int?, fps: Int?, bitrate: Int?) {
        val forEncoder = options.filter { it.encoderName == encoder }
        val sized = forEncoder.filter { it.width == width && it.height == height }.ifEmpty { forEncoder }
        val o = sized.firstOrNull { it.fps == fps } ?: sized.maxBy { it.fps }
        val rates = bitratesOf(o)
        val b = rates.minByOrNull { kotlin.math.abs(it.toLong() - (bitrate ?: rates.first()).toLong()) } ?: return
        onChange(VideoPreset(o.codec, o.encoderName, o.width, o.height, o.fps, b))
    }

    val encoders = options.map { it.encoderName to "${it.codec.label} · ${it.encoderName}${if (it.hardware) "" else " (software)"}" }.distinct()
    val encoder = current?.encoderName?.takeIf { e -> options.any { it.encoderName == e } } ?: encoders.first().first
    Chips(encoders.map { it.first }, encoder, { e -> encoders.first { it.first == e }.second }) { e ->
        pick(e, current?.width, current?.height, current?.fps, current?.bitrate)
    }

    val forEncoder = options.filter { it.encoderName == encoder }
    val sizes = forEncoder.map { it.width to it.height }.distinct()
    val size = current?.let { it.width to it.height }?.takeIf { it in sizes } ?: sizes.first()
    Chips(sizes, size, { "${it.first}x${it.second}" }) { s -> pick(encoder, s.first, s.second, current?.fps, current?.bitrate) }

    val fpsList = forEncoder.filter { it.width == size.first && it.height == size.second }.map { it.fps }.distinct().sorted()
    val fps = current?.fps?.takeIf { it in fpsList } ?: fpsList.last()
    Chips(fpsList, fps, { "$it fps" }) { f -> pick(encoder, size.first, size.second, f, current?.bitrate) }

    val option = forEncoder.first { it.width == size.first && it.height == size.second && it.fps == fps }
    val rates = bitratesOf(option)
    Chips(rates, current?.bitrate ?: rates.firstOrNull(), { formatBitrate(it) }) { b ->
        pick(encoder, size.first, size.second, fps, b)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(items: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) {
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}

/** Chips for a Camera2 mode list plus a "Device default" choice (null). */
@Composable
private fun ModeChips(controller: CameraController, kind: ModeKind, modes: List<Int>, selected: Int?, onSelect: (Int?) -> Unit) {
    val items: List<Int?> = listOf<Int?>(null) + modes
    Chips(items, selected, { it?.let { v -> controller.modeName(kind, v) } ?: "Device default" }, onSelect)
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Toggle(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
