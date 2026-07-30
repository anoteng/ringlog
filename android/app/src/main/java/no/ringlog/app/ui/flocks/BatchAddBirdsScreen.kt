package no.ringlog.app.ui.flocks

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
import no.ringlog.app.ui.components.DatePickerField
import no.ringlog.app.ui.components.SpeciesDropdown
import no.ringlog.app.ui.components.SexDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchAddBirdsScreen(
    flockId: Int,
    onBack: () -> Unit,
    vm: FlockViewModel = hiltViewModel(),
) {
    val batchState by vm.batchAddState.collectAsStateWithLifecycle()

    var ringPrefix by remember { mutableStateOf("") }
    var ringStart  by remember { mutableStateOf("") }
    var count      by remember { mutableStateOf("") }
    var species    by remember { mutableStateOf("chicken") }
    var breed      by remember { mutableStateOf("") }
    var sex        by remember { mutableStateOf("unknown") }
    var birthDate  by remember { mutableStateOf("") }

    val isLoading = batchState is FlockViewModel.BatchAddState.Loading
    val result    = batchState as? FlockViewModel.BatchAddState.Success
    val errorMsg  = (batchState as? FlockViewModel.BatchAddState.Error)?.msg

    fun submit() {
        val start = ringStart.trim().toIntOrNull() ?: return
        val n     = count.trim().toIntOrNull() ?: return
        vm.batchAddBirds(flockId, ringPrefix.trim().ifBlank { null }, start, n,
                         species, breed.trim(), sex, birthDate.ifBlank { null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_add)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = ::submit,
                        enabled = ringStart.toIntOrNull() != null && count.toIntOrNull()?.let { it > 0 } == true && !isLoading,
                    ) {
                        if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.add))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (result != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.batch_add_result, result.added, result.skipped),
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ringPrefix,
                    onValueChange = { ringPrefix = it },
                    label = { Text(stringResource(R.string.ring_prefix)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = ringStart,
                    onValueChange = { ringStart = it },
                    label = { Text(stringResource(R.string.ring_start) + " *") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = count,
                    onValueChange = { count = it },
                    label = { Text(stringResource(R.string.count) + " *") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            SpeciesDropdown(selected = species, onSelected = { species = it },
                            modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = breed,
                onValueChange = { breed = it },
                label = { Text(stringResource(R.string.breed)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            SexDropdown(selected = sex, onSelected = { sex = it },
                        modifier = Modifier.fillMaxWidth())
            DatePickerField(
                label = { Text(stringResource(R.string.born)) },
                value = birthDate,
                onValueChange = { birthDate = it },
                modifier = Modifier.fillMaxWidth(),
            )

            if (errorMsg != null) {
                Text(errorMsg, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
