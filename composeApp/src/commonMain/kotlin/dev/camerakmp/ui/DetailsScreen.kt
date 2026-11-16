package dev.camerakmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.camerakmp.model.CameraController

/** Everything known about each capture from this session: capture result metadata, EXIF, video tracks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(controller: CameraController, onBack: () -> Unit) {
    val captures by controller.captures.collectAsState()
    var selected by remember { mutableStateOf(0) }
    val record = captures.getOrNull(selected)
    InfoScreen(
        title = "Capture details",
        sections = record?.sections.orEmpty(),
        onBack = onBack,
        header = {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                captures.take(12).forEachIndexed { i, c ->
                    FilterChip(selected = i == selected, onClick = { selected = i }, label = { Text("${c.title} ${captures.size - i}") })
                }
            }
        },
    )
}
