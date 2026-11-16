package dev.camerakmp.model

import kotlinx.coroutines.flow.StateFlow

sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val message: String, val progress: Float) : ScanState
    data class Done(val capabilities: DeviceCapabilities) : ScanState
    data class Failed(val error: String) : ScanState
}

data class CameraUiState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val recording: VideoKind? = null,
    val recordingSeconds: Int = 0,
    val status: String = "",
    val error: String? = null,
)

/** Platform camera engine. The Android implementation is built on Camera2 + MediaCodec. */
interface CameraController {
    val scanState: StateFlow<ScanState>
    val uiState: StateFlow<CameraUiState>
    val settings: StateFlow<AppSettings>
    val captures: StateFlow<List<CaptureRecord>>

    /** Runs (or loads the cached) capability scan, including encoder validation. */
    fun scan(force: Boolean = false)

    fun updateSettings(transform: (AppSettings) -> AppSettings)

    fun takePhoto()
    fun takeRaw()
    fun toggleRecording(kind: VideoKind)

    /** Readable name for a Camera2 integer mode value. */
    fun modeName(kind: ModeKind, value: Int): String
}

enum class ModeKind { NOISE_REDUCTION, EDGE, OIS, VIDEO_STABILIZATION, AWB }
