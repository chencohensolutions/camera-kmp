package dev.camerakmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.camerakmp.Screen
import dev.camerakmp.model.CameraController
import dev.camerakmp.model.HdrMode
import dev.camerakmp.model.ScanState
import dev.camerakmp.model.VideoKind
import dev.camerakmp.model.optionsFor

@Composable
fun CameraScreen(
    controller: CameraController,
    preview: @Composable (Modifier) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val scan by controller.scanState.collectAsState()
    val ui by controller.uiState.collectAsState()
    val settings by controller.settings.collectAsState()
    val captures by controller.captures.collectAsState()
    val options = (scan as? ScanState.Done)?.capabilities?.optionsFor(settings.lensKey)
    val recording = ui.recording

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onNavigate(Screen.SETTINGS) }, enabled = recording == null) { Text("Settings") }
            Spacer(Modifier.weight(1f))
            val hdr = if (settings.hdrMode == HdrMode.ON) "HDR on" else "HDR off"
            Text("${settings.photoFormat.label} · $hdr", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onNavigate(Screen.DETAILS) }, enabled = captures.isNotEmpty() && recording == null) {
                Text("Details")
            }
        }

        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f), contentAlignment = Alignment.Center) {
            preview(Modifier.fillMaxSize())
            when (val s = scan) {
                is ScanState.Running -> ScanOverlay(s.message, s.progress)
                is ScanState.Failed -> Text("Scan failed: ${s.error}", color = Color.Red, modifier = Modifier.padding(16.dp))
                else -> {}
            }
            if (recording != null) {
                val label = if (recording == VideoKind.LOW) "LOW BITRATE" else "REC"
                val m = ui.recordingSeconds / 60
                val sec = (ui.recordingSeconds % 60).toString().padStart(2, '0')
                Text(
                    "● $label $m:$sec",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(Color(0xCCD32F2F)).padding(6.dp),
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val status = ui.error ?: ui.status
            Text(
                status,
                color = if (ui.error != null) Color(0xFFFF8A80) else Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().height(36.dp),
            )
            val canShoot = ui.ready && !ui.busy && recording == null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = controller::takePhoto, enabled = canShoot, modifier = Modifier.weight(1f)) {
                    Text("Photo\n${settings.photoFormat.label}", textAlign = TextAlign.Center)
                }
                OutlinedButton(
                    onClick = controller::takeRaw,
                    enabled = canShoot && options?.rawSupported == true,
                    modifier = Modifier.weight(1f),
                ) { Text("RAW\nDNG", textAlign = TextAlign.Center) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordButton(
                    title = "Video",
                    detail = settings.highVideo?.label ?: "no validated setting",
                    active = recording == VideoKind.HIGH,
                    enabled = ui.ready && !ui.busy && settings.highVideo != null && (recording == null || recording == VideoKind.HIGH),
                    onClick = { controller.toggleRecording(VideoKind.HIGH) },
                    modifier = Modifier.weight(1f),
                )
                RecordButton(
                    title = "Low bitrate",
                    detail = settings.lowVideo?.label ?: "no validated setting",
                    active = recording == VideoKind.LOW,
                    enabled = ui.ready && !ui.busy && settings.lowVideo != null && (recording == null || recording == VideoKind.LOW),
                    onClick = { controller.toggleRecording(VideoKind.LOW) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RecordButton(
    title: String,
    detail: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = if (active) ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)) else ButtonDefaults.buttonColors(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (active) "Stop $title" else title)
            Text(detail, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ScanOverlay(message: String, progress: Float) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Checking what this phone supports (first launch only)", color = Color.White)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
}
