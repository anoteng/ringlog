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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchAddBirdsScreen(
    flockId: Int,
    onBack: () -> Unit,
    vm: FlockViewModel = hiltViewModel(),
) {
    val batchState by vm.batchAddState.collectAsStateWithLifecycle()

    var ringPrefix   by remember { mutableStateOf("") }
    var ringStart    by remember { mutableStateOf("") }
    var maleCount    by remember { mutableStateOf("") }
    var femaleCount  by remember { mutableStateOf("") }
    var unknownCount by remember { mutableStateOf("") }
    var species      by remember { mutableStateOf("chicken") }
    var breed        by remember { mutableStateOf("") }
    var birthDate    by remember { mutableStateOf("") }

    val isLoading = batchState is FlockViewModel.BatchAddState.Loading
    val result    = batchState as? FlockViewModel.BatchAddState.Success
    val errorMsg  = (batchState as? FlockViewModel.BatchAddState.Error)?.msg

    val totalCount = (maleCount.toIntOrNull() ?: 0) + (femaleCount.toIntOrNull() ?: 0) + (unknownCount.toIntOrNull() ?: 0)

    fun submit() {
        vm.batchAddBirds(
            flockId, ringPrefix.trim().ifBlank { null }, ringStart.trim().toIntOrNull(),
            maleCount.trim().toIntOrNull() ?: 0,
            femaleCount.trim().toIntOrNull() ?: 0,
            unknownCount.trim().toIntOrNull() ?: 0,
            species, breed.trim(), birthDate.ifBlank { null },
        )
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
                        enabled = totalCount in 1..500 && !isLoading,
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
                    label = { Text(stringResource(R.string.ring_start)) },
                    placeholder = { Text(stringResource(R.string.lowest_available)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Text(
                stringResource(R.string.ring_start_optional_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maleCount,
                    onValueChange = { maleCount = it },
                    label = { Text(stringResource(R.string.sex_male)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = femaleCount,
                    onValueChange = { femaleCount = it },
                    label = { Text(stringResource(R.string.sex_female)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = unknownCount,
                    onValueChange = { unknownCount = it },
                    label = { Text(stringResource(R.string.sex_unknown)) },
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
