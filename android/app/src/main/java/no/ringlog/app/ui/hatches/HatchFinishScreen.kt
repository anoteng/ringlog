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
import no.ringlog.app.data.api.Flock
import no.ringlog.app.data.api.HatchFinishRequest
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HatchFinishScreen(
    hatchId: Int,
    currentEggsHatched: Int?,
    currentEggsBrooder: Int?,
    currentEggsDiscarded: Int?,
    onFinished: (flockId: Int?) -> Unit,
    onBack: () -> Unit,
    vm: HatchFinishViewModel = hiltViewModel(),
) {
    val flocksState  by vm.flocksState.collectAsStateWithLifecycle()
    val finishState  by vm.finishState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadFlocks() }
    LaunchedEffect(finishState) {
        if (finishState is HatchFinishViewModel.FinishState.Success) {
            vm.resetFinish()
            onFinished((finishState as HatchFinishViewModel.FinishState.Success).flockId)
        }
    }

    var eggsHatched   by remember { mutableStateOf(currentEggsHatched?.toString() ?: "") }
    var eggsBrooder   by remember { mutableStateOf(currentEggsBrooder?.toString() ?: "") }
    var eggsDiscarded by remember { mutableStateOf(currentEggsDiscarded?.toString() ?: "") }
    var addToFlock    by remember { mutableStateOf(true) }
    var selectedFlock by remember { mutableStateOf<Flock?>(null) }
    var newFlockName  by remember { mutableStateOf("") }
    var useNewFlock   by remember { mutableStateOf(false) }
    var ringPrefix    by remember { mutableStateOf("") }
    var ringStart     by remember { mutableStateOf("1") }
    var birthDate     by remember { mutableStateOf(LocalDate.now().toString()) }
    var breed         by remember { mutableStateOf("") }
    var flockExpanded by remember { mutableStateOf(false) }

    val isLoading = finishState is HatchFinishViewModel.FinishState.Loading
    val errorMsg  = (finishState as? HatchFinishViewModel.FinishState.Error)?.msg
    val count     = eggsHatched.toIntOrNull() ?: 0

    fun buildRequest() = HatchFinishRequest(
        eggs_hatched   = eggsHatched.toIntOrNull(),
        eggs_brooder   = eggsBrooder.toIntOrNull(),
        eggs_discarded = eggsDiscarded.toIntOrNull(),
        flock_id       = if (addToFlock && !useNewFlock) selectedFlock?.id else null,
        new_flock_name = if (addToFlock && useNewFlock) newFlockName.trim().ifBlank { null } else null,
        ring_prefix    = if (addToFlock) ringPrefix else null,
        ring_start     = if (addToFlock) ringStart.toIntOrNull() else null,
        birth_date     = if (addToFlock) birthDate.ifBlank { null } else null,
        breed          = if (addToFlock) breed.trim().ifBlank { null } else null,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.finish_hatch)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { vm.finish(hatchId, buildRequest()) },
                        enabled = !isLoading,
                    ) {
                        if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
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
            Text(stringResource(R.string.eggs), style = MaterialTheme.typography.titleSmall)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = eggsHatched, onValueChange = { eggsHatched = it },
                    label = { Text(stringResource(R.string.hatched)) },
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
            OutlinedTextField(
                value = eggsBrooder, onValueChange = { eggsBrooder = it },
                label = { Text(stringResource(R.string.to_brooder)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.transfer_to_flock), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = addToFlock, onCheckedChange = { addToFlock = it })
            }

            if (addToFlock) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !useNewFlock,
                        onClick  = { useNewFlock = false },
                        label    = { Text(stringResource(R.string.existing_flock)) },
                    )
                    FilterChip(
                        selected = useNewFlock,
                        onClick  = { useNewFlock = true },
                        label    = { Text(stringResource(R.string.new_flock)) },
                    )
                }

                if (useNewFlock) {
                    OutlinedTextField(
                        value = newFlockName, onValueChange = { newFlockName = it },
                        label = { Text(stringResource(R.string.flock_name)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                } else {
                    when (val fs = flocksState) {
                        is HatchFinishViewModel.FlocksState.Loading ->
                            CircularProgressIndicator(Modifier.size(24.dp))
                        is HatchFinishViewModel.FlocksState.Error ->
                            Text(fs.msg, color = MaterialTheme.colorScheme.error,
                                 style = MaterialTheme.typography.bodySmall)
                        is HatchFinishViewModel.FlocksState.Success -> {
                            ExposedDropdownMenuBox(
                                expanded = flockExpanded,
                                onExpandedChange = { flockExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = selectedFlock?.name ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.flock)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(flockExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                )
                                ExposedDropdownMenu(
                                    expanded = flockExpanded,
                                    onDismissRequest = { flockExpanded = false },
                                ) {
                                    fs.flocks.forEach { flock ->
                                        DropdownMenuItem(
                                            text = { Text(flock.name) },
                                            onClick = { selectedFlock = flock; flockExpanded = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = birthDate, onValueChange = { birthDate = it },
                    label = { Text(stringResource(R.string.born)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("YYYY-MM-DD") },
                )
                OutlinedTextField(
                    value = breed, onValueChange = { breed = it },
                    label = { Text(stringResource(R.string.breed)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )

                if (count > 0) {
                    Text(stringResource(R.string.ring_numbers), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = ringPrefix, onValueChange = { ringPrefix = it },
                            label = { Text(stringResource(R.string.ring_prefix)) },
                            modifier = Modifier.weight(2f), singleLine = true,
                        )
                        OutlinedTextField(
                            value = ringStart, onValueChange = { ringStart = it },
                            label = { Text(stringResource(R.string.ring_start)) },
                            modifier = Modifier.weight(1f), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    val start = ringStart.toIntOrNull() ?: 1
                    val preview = (0 until minOf(count, 5)).joinToString(", ") { "$ringPrefix${start + it}" }
                    val suffix  = if (count > 5) " …" else ""
                    Text("$preview$suffix",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            if (errorMsg != null) {
                Text(errorMsg, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
