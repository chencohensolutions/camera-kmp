package dev.camerakmp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.camerakmp.model.InfoSection

/** Full-screen, searchable list of name/value sections with collapsible headers. */
@Composable
fun InfoScreen(title: String, sections: List<InfoSection>, onBack: () -> Unit, header: @Composable () -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var collapsed by remember(sections) { mutableStateOf(emptySet<Int>()) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            header()
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter keys and values") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    sections.forEachIndexed { index, section ->
                        val entries = if (query.isBlank()) section.entries else section.entries.filter {
                            it.first.contains(query, true) || it.second.contains(query, true)
                        }
                        if (query.isNotBlank() && entries.isEmpty()) return@forEachIndexed
                        val isCollapsed = index in collapsed && query.isBlank()
                        item(key = "h$index") {
                            Text(
                                "${if (isCollapsed) "▸" else "▾"} ${section.title} (${entries.size})",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    collapsed = if (index in collapsed) collapsed - index else collapsed + index
                                }.padding(vertical = 10.dp),
                            )
                        }
                        if (!isCollapsed) {
                            items(entries) { (k, v) ->
                                Column(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                                    Text(k, style = MaterialTheme.typography.labelMedium)
                                    Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        item(key = "d$index") { HorizontalDivider() }
                    }
                }
            }
        }
    }
}
