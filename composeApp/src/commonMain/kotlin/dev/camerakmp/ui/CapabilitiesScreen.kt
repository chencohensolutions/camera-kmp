package dev.camerakmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.camerakmp.model.CameraController
import dev.camerakmp.model.ScanState

/** Raw dump of every characteristic, key, extension and encoder the device reports. */
@Composable
fun CapabilitiesScreen(controller: CameraController, onBack: () -> Unit) {
    val scan by controller.scanState.collectAsState()
    val sections = (scan as? ScanState.Done)?.capabilities?.dump.orEmpty()
    InfoScreen(title = "All capabilities", sections = sections, onBack = onBack)
}
