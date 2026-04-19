package no.ringlog.app.ui.log

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.R
import no.ringlog.app.data.api.Flock
import no.ringlog.app.data.api.LogEntry
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

    var eggs    by remember(flock.id, selectedDate) { mutableStateOf(existing?.eggs_collected?.toString() ?: "") }
    var light   by remember(flock.id, selectedDate) { mutableStateOf(defaultLight) }
    var bedding by remember(flock.id, selectedDate) { mutableStateOf(existing?.bedding_changed ?: false) }
    var notes   by remember(flock.id, selectedDate) { mutableStateOf(existing?.notes ?: "") }
    var saved   by remember(flock.id, selectedDate) { mutableStateOf(false) }

    val saveLabel  = stringResource(R.string.save)
    val savedLabel = stringResource(R.string.saved)

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(flock.name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = eggs, onValueChange = { eggs = it; saved = false },
                    label = { Text(stringResource(R.string.eggs)) }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                )
                OutlinedTextField(
                    value = light, onValueChange = { light = it; saved = false },
                    label = { Text(stringResource(R.string.light_hours)) }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = notes, onValueChange = { notes = it; saved = false },
                label = { Text(stringResource(R.string.notes)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bedding, onCheckedChange = { bedding = it; saved = false })
                    Text(stringResource(R.string.bedding_changed), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    vm.save(flock.id, eggs.toIntOrNull(), light.toFloatOrNull(), bedding, notes.ifBlank { null })
                    saved = true
                }) { Text(if (saved) savedLabel else saveLabel) }
            }
        }
    }
}
