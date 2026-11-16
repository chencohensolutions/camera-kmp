package dev.camerakmp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.camerakmp.camera.AndroidCameraController
import dev.camerakmp.ui.CameraPreview

class MainActivity : ComponentActivity() {

    private lateinit var controller: AndroidCameraController
    private val cameraGranted = mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted.value = result[Manifest.permission.CAMERA] == true || hasCamera()
        if (cameraGranted.value) controller.scan()
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = AndroidCameraController(applicationContext)
        cameraGranted.value = hasCamera()
        if (cameraGranted.value) controller.scan() else requestPermissions()

        setContent {
            if (cameraGranted.value) {
                App(controller) { modifier -> CameraPreview(controller, modifier) }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = ::requestPermissions, modifier = Modifier.padding(24.dp)) {
                        Text("Allow camera and microphone")
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }

    override fun onPause() {
        controller.onPause()
        super.onPause()
    }
}
