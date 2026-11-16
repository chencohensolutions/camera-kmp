package dev.camerakmp.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Rational

/** Turns any Camera2 metadata value (arrays, ranges, nested params) into readable text. */
internal fun formatValue(value: Any?): String {
    if (value == null) return "null"
    return when (value) {
        is StreamConfigurationMap -> value.toString().replace("), ", "),\n")
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
        is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> if (value.size > 64) "${value.size} bytes" else value.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> if (value.size > 200) "${value.size} items" else value.joinToString(prefix = "[", postfix = "]") { formatValue(it) }
        is Range<*> -> "[${value.lower}, ${value.upper}]"
        is Rational -> value.toString()
        is Collection<*> -> value.joinToString(prefix = "[", postfix = "]") { formatValue(it) }
        else -> value.toString()
    }
}

/** Human names for the enum-like integer values that matter most when reading a dump. */
internal fun describeKnown(keyName: String, value: Any?): String? = when (keyName) {
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL.name -> when (value as? Int) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> null
    }
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES.name ->
        (value as? IntArray)?.joinToString { capabilityName(it) }
    CameraCharacteristics.LENS_FACING.name -> facingName(value as? Int)
    CaptureResult.CONTROL_AE_STATE.name -> when (value as? Int) {
        CameraMetadata.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CameraMetadata.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CameraMetadata.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CameraMetadata.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CameraMetadata.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        else -> null
    }
    else -> null
}

internal fun facingName(facing: Int?): String = when (facing) {
    CameraMetadata.LENS_FACING_BACK -> "Back"
    CameraMetadata.LENS_FACING_FRONT -> "Front"
    CameraMetadata.LENS_FACING_EXTERNAL -> "External"
    else -> "Unknown"
}

internal fun capabilityName(cap: Int): String = when (cap) {
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
    else -> "CAPABILITY_$cap"
}

/** Names for the small integer enums shown as choices in settings. */
internal fun noiseReductionName(mode: Int) = when (mode) {
    CameraMetadata.NOISE_REDUCTION_MODE_OFF -> "Off"
    CameraMetadata.NOISE_REDUCTION_MODE_FAST -> "Fast"
    CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "High quality"
    CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> "Minimal"
    CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "Zero shutter lag"
    else -> "Mode $mode"
}

internal fun edgeName(mode: Int) = when (mode) {
    CameraMetadata.EDGE_MODE_OFF -> "Off"
    CameraMetadata.EDGE_MODE_FAST -> "Fast"
    CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "High quality"
    CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG -> "Zero shutter lag"
    else -> "Mode $mode"
}

internal fun oisName(mode: Int) = when (mode) {
    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "Off"
    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "On"
    else -> "Mode $mode"
}

internal fun videoStabName(mode: Int) = when (mode) {
    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "Off"
    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "On"
    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "Preview stabilization"
    else -> "Mode $mode"
}

internal fun awbName(mode: Int) = when (mode) {
    CameraMetadata.CONTROL_AWB_MODE_OFF -> "Off"
    CameraMetadata.CONTROL_AWB_MODE_AUTO -> "Auto"
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "Incandescent"
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescent"
    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Warm fluorescent"
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "Daylight"
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloudy"
    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "Twilight"
    CameraMetadata.CONTROL_AWB_MODE_SHADE -> "Shade"
    else -> "Mode $mode"
}
