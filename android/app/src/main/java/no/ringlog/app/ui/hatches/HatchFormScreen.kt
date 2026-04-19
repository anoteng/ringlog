package no.ringlog.app.ui.hatches

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.R
import no.ringlog.app.data.api.Hatch
import no.ringlog.app.data.api.HatchRequest
import no.ringlog.app.ui.components.DatePickerField
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val SPECIES = listOf("chicken", "duck", "muscovy", "custom")
private val PRESETS = mapOf(
    "chicken" to Pair(21, 18),
    "duck"    to Pair(28, 25),
    "muscovy" to Pair(35, 31),
    "custom"  to Pair(21, 18),
)

private fun normalizeDateTime(s: String): String {
    val t = s.trim()
    return if (t.length == 16) "$t:00" else t
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HatchFormScreen(
    hatchId: Int?,
    initial: Hatch?,
    onSaved: (Int) -> Unit,
    onBack: () -> Unit,
    vm: HatchViewModel = hiltViewModel(),
) {
    val saveState by vm.saveState.collectAsStateWithLifecycle()

    LaunchedEffect(saveState) {
        if (saveState is HatchViewModel.SaveState.Success) {
            vm.resetSave()
            onSaved((saveState as HatchViewModel.SaveState.Success).id)
        }
    }

    val now = remember { LocalDateTime.now() }

    var name           by remember { mutableStateOf(initial?.name ?: "") }
    var species        by remember { mutableStateOf(initial?.species ?: "chicken") }
    var startDate      by remember { mutableStateOf(initial?.start_datetime?.take(10) ?: now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))) }
    var startTime      by remember { mutableStateOf(initial?.start_datetime?.substring(11, 16) ?: now.format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var incubationDays     by remember { mutableStateOf(initial?.incubation_days?.toString() ?: "21") }
    var lockdownDay        by remember { mutableStateOf(initial?.lockdown_day?.toString() ?: "18") }
    var humidityIncubation by remember { mutableStateOf(initial?.humidity_incubation?.toString() ?: "") }
    var humidityLockdown   by remember { mutableStateOf(initial?.humidity_lockdown?.toString() ?: "") }
    var eggCount           by remember { mutableStateOf(initial?.egg_count?.toString() ?: "") }
    var eggsBrooder        by remember { mutableStateOf(initial?.eggs_brooder?.toString() ?: "") }
    var eggsDiscarded      by remember { mutableStateOf(initial?.eggs_discarded?.toString() ?: "") }
    var eggsHatched        by remember { mutableStateOf(initial?.eggs_hatched?.toString() ?: "") }
    var notes              by remember { mutableStateOf(initial?.notes ?: "") }
    var speciesExpanded    by remember { mutableStateOf(false) }

    fun applyPreset(s: String) {
        PRESETS[s]?.let { (inc, lock) ->
            incubationDays = inc.toString()
            lockdownDay    = lock.toString()
        }
    }

    fun buildRequest(): HatchRequest? {
        val dt = normalizeDateTime("$startDate $startTime")
        if (dt.length < 19) return null
        return HatchRequest(
            name                = name.trim().ifBlank { null },
            species             = species,
            start_datetime      = dt,
            incubation_days     = incubationDays.toIntOrNull() ?: return null,
            lockdown_day        = lockdownDay.toIntOrNull() ?: return null,
            humidity_incubation = humidityIncubation.trim().toFloatOrNull(),
            humidity_lockdown   = humidityLockdown.trim().toFloatOrNull(),
            egg_count           = eggCount.trim().toIntOrNull(),
            eggs_brooder        = eggsBrooder.trim().toIntOrNull(),
            eggs_discarded      = eggsDiscarded.trim().toIntOrNull(),
            eggs_hatched        = eggsHatched.trim().toIntOrNull(),
            notes               = notes.trim().ifBlank { null },
        )
    }

    val isSaving = saveState is HatchViewModel.SaveState.Loading
    val errorMsg = (saveState as? HatchViewModel.SaveState.Error)?.msg

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (hatchId == null) stringResource(R.string.new_hatch) else stringResource(R.string.edit_hatch)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { buildRequest()?.let { vm.saveHatch(hatchId, it) } },
                        enabled = !isSaving,
                    ) {
                        if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )

            ExposedDropdownMenuBox(expanded = speciesExpanded, onExpandedChange = { speciesExpanded = it }) {
                OutlinedTextField(
                    value = species.replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.species)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(speciesExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = speciesExpanded, onDismissRequest = { speciesExpanded = false }) {
                    SPECIES.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                if (species != s) { species = s; applyPreset(s) }
                                speciesExpanded = false
                            }
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DatePickerField(
                    value = startDate, onValueChange = { startDate = it },
                    label = { Text(stringResource(R.string.date)) },
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = startTime, onValueChange = { startTime = it },
                    label = { Text("HH:mm") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = incubationDays, onValueChange = { incubationDays = it },
                    label = { Text(stringResource(R.string.incubation_days_label)) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = lockdownDay, onValueChange = { lockdownDay = it },
                    label = { Text(stringResource(R.string.lockdown_day_label)) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = humidityIncubation, onValueChange = { humidityIncubation = it },
                    label = { Text(stringResource(R.string.humidity_incubation)) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = humidityLockdown, onValueChange = { humidityLockdown = it },
                    label = { Text(stringResource(R.string.humidity_lockdown)) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }

            Text(stringResource(R.string.eggs), style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = eggCount, onValueChange = { eggCount = it },
                    label = { Text(stringResource(R.string.set)) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = eggsDiscarded, onValueChange = { eggsDiscarded = it },
                    label = { Text(stringResource(R.string.discarded)) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = eggsBrooder, onValueChange = { eggsBrooder = it },
                    label = { Text(stringResource(R.string.to_brooder)) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = eggsHatched, onValueChange = { eggsHatched = it },
                    label = { Text(stringResource(R.string.hatched)) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text(stringResource(R.string.notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            if (errorMsg != null) {
                Text(errorMsg, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
