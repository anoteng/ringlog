package no.ringlog.app.ui.log

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.data.api.Flock
import no.ringlog.app.data.api.LogEntry
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLogScreen(vm: LogViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.init() }

    Scaffold(topBar = { TopAppBar(title = { Text("Daily Log") }) }) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is LogViewModel.State.Loading -> LoadingScreen()
                is LogViewModel.State.Error   -> ErrorScreen(s.msg) { vm.init() }
                is LogViewModel.State.Ready   -> LogContent(s, vm)
            }
        }
    }
}

@Composable
private fun LogContent(s: LogViewModel.State.Ready, vm: LogViewModel) {
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    Column {
        // Date navigator
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { vm.loadDate(vm.selectedDate.minusDays(1)) }) {
                Icon(Icons.Default.KeyboardArrowLeft, "Previous day")
            }
            Text(vm.selectedDate.format(fmt), style = MaterialTheme.typography.titleSmall)
            IconButton(
                onClick = { vm.loadDate(vm.selectedDate.plusDays(1)) },
                enabled = vm.selectedDate < LocalDate.now(),
            ) {
                Icon(Icons.Default.KeyboardArrowRight, "Next day")
            }
        }
        HorizontalDivider()
        LazyColumn {
            items(s.flocks) { flock ->
                FlockLogRow(flock, s.entries[flock.id], vm)
            }
        }
    }
}

@Composable
private fun FlockLogRow(flock: Flock, existing: LogEntry?, vm: LogViewModel) {
    var eggs    by remember(flock.id, vm.selectedDate) { mutableStateOf(existing?.eggs_collected?.toString() ?: "") }
    var light   by remember(flock.id, vm.selectedDate) { mutableStateOf(existing?.light_hours?.toString() ?: "") }
    var bedding by remember(flock.id, vm.selectedDate) { mutableStateOf(existing?.bedding_changed ?: false) }
    var notes   by remember(flock.id, vm.selectedDate) { mutableStateOf(existing?.notes ?: "") }
    var saved   by remember(flock.id, vm.selectedDate) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(flock.name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = eggs, onValueChange = { eggs = it; saved = false },
                    label = { Text("Eggs") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                )
                OutlinedTextField(
                    value = light, onValueChange = { light = it; saved = false },
                    label = { Text("Light h") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = notes, onValueChange = { notes = it; saved = false },
                label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bedding, onCheckedChange = { bedding = it; saved = false })
                    Text("Bedding changed", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    vm.save(flock.id, eggs.toIntOrNull(), light.toFloatOrNull(), bedding, notes.ifBlank { null })
                    saved = true
                }) { Text(if (saved) "Saved ✓" else "Save") }
            }
        }
    }
}
