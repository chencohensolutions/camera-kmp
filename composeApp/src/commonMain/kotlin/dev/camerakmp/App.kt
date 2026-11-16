package dev.camerakmp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.camerakmp.model.CameraController
import dev.camerakmp.ui.CameraScreen
import dev.camerakmp.ui.CapabilitiesScreen
import dev.camerakmp.ui.DetailsScreen
import dev.camerakmp.ui.SettingsScreen

enum class Screen { CAMERA, SETTINGS, DETAILS, CAPABILITIES }

/**
 * @param preview platform camera preview (a Camera2 SurfaceView on Android).
 */
@Composable
fun App(controller: CameraController, preview: @Composable (Modifier) -> Unit) {
    var screen by remember { mutableStateOf(Screen.CAMERA) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        when (screen) {
            Screen.CAMERA -> CameraScreen(controller, preview, onNavigate = { screen = it })
            Screen.SETTINGS -> SettingsScreen(controller, onBack = { screen = Screen.CAMERA }, onCapabilities = { screen = Screen.CAPABILITIES })
            Screen.DETAILS -> DetailsScreen(controller, onBack = { screen = Screen.CAMERA })
            Screen.CAPABILITIES -> CapabilitiesScreen(controller, onBack = { screen = Screen.SETTINGS })
        }
    }
}
