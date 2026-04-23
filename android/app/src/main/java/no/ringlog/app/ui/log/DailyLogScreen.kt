package no.ringlog.app.ui.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import no.ringlog.app.R
import no.ringlog.app.data.api.Flock
import no.ringlog.app.data.api.LogEntry
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val SavedGreen = Color(0xFF4CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLogScreen(vm: LogViewModel = hiltViewModel()) {
    val state        by vm.state.collectAsStateWithLifecycle()
    val selectedDate by vm.selectedDate.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.init() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.daily_log)) }) }) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is LogViewModel.State.Loading -> LoadingScreen()
                is LogViewModel.State.Error   -> ErrorScreen(s.msg) { vm.init() }
                is LogViewModel.State.Ready   -> LogContent(s, selectedDate, vm)
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorScreen(msg: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(msg)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun LogContent(s: LogViewModel.State.Ready, selectedDate: LocalDate, vm: LogViewModel) {
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { vm.loadDate(selectedDate.minusDays(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day))
            }
            Text(selectedDate.format(fmt), style = MaterialTheme.typography.titleSmall)
            IconButton(
                onClick = { vm.loadDate(selectedDate.plusDays(1)) },
                enabled = selectedDate < LocalDate.now(),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_day))
            }
        }
        HorizontalDivider()
        LazyColumn {
            items(s.flocks) { flock ->
                FlockLogRow(flock, s.entries[flock.id], s.prevEntries[flock.id], selectedDate, vm)
            }
        }
    }
}

@Composable
private fun FlockLogRow(
    flock: Flock,
    existing: LogEntry?,
    prev: LogEntry?,
    selectedDate: LocalDate,
    vm: LogViewModel,
) {
    val defaultLight = existing?.light_hours?.toString() ?: prev?.light_hours?.toString() ?: ""

    var eggs      by remember(flock.id, selectedDate) { mutableStateOf(existing?.eggs_collected?.toString() ?: "") }
    var light     by remember(flock.id, selectedDate) { mutableStateOf(defaultLight) }
    var bedding   by remember(flock.id, selectedDate) { mutableStateOf(existing?.bedding_changed ?: false) }
    var notes     by remember(flock.id, selectedDate) { mutableStateOf(existing?.notes ?: "") }
    var isDirty   by remember(flock.id, selectedDate) { mutableStateOf(false) }
    var showSaved by remember(flock.id, selectedDate) { mutableStateOf(false) }

    LaunchedEffect(eggs, light, bedding, notes) {
        if (!isDirty) return@LaunchedEffect
        delay(1200)
        vm.save(flock.id, eggs.toIntOrNull(), light.toFloatOrNull(), bedding, notes.ifBlank { null })
        showSaved = true
        delay(2000)
        showSaved = false
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        border = if (showSaved) BorderStroke(2.dp, SavedGreen) else null,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(flock.name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        val n = (eggs.toIntOrNull() ?: 0) - 1
                        if (n >= 0) { eggs = n.toString(); isDirty = true }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Remove, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                }
                OutlinedTextField(
                    value = eggs,
                    onValueChange = { eggs = it; isDirty = true },
                    label = { Text(stringResource(R.string.eggs)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        val n = (eggs.toIntOrNull() ?: 0) + 1
                        eggs = n.toString(); isDirty = true
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = light,
                    onValueChange = { light = it; isDirty = true },
                    label = { Text(stringResource(R.string.light_hours)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }

            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it; isDirty = true },
                label = { Text(stringResource(R.string.notes)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bedding, onCheckedChange = { bedding = it; isDirty = true })
                Text(stringResource(R.string.bedding_changed), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
