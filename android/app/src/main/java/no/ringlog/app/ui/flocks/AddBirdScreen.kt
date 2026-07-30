package no.ringlog.app.ui.flocks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.R
import no.ringlog.app.ui.components.DatePickerField
import no.ringlog.app.ui.components.SpeciesDropdown
import no.ringlog.app.ui.components.SexDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBirdScreen(
    flockId: Int,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    vm: FlockViewModel = hiltViewModel(),
) {
    val addState by vm.addBirdState.collectAsStateWithLifecycle()

    LaunchedEffect(addState) {
        if (addState is FlockViewModel.AddBirdState.Success) {
            vm.resetAddBird()
            onSaved()
        }
    }

    var ring      by remember { mutableStateOf("") }
    var name      by remember { mutableStateOf("") }
    var species   by remember { mutableStateOf("chicken") }
    var breed     by remember { mutableStateOf("") }
    var sex       by remember { mutableStateOf("unknown") }
    var birthDate by remember { mutableStateOf("") }

    val isLoading = addState is FlockViewModel.AddBirdState.Loading
    val errorMsg  = (addState as? FlockViewModel.AddBirdState.Error)?.msg

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_bird)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val fields = buildMap<String, String> {
                                put("ring_number", ring.trim())
                                if (name.isNotBlank()) put("name", name.trim())
                                put("species", species)
                                if (breed.isNotBlank()) put("breed", breed.trim())
                                put("sex", sex)
                                if (birthDate.isNotBlank()) put("birth_date", birthDate)
                            }
                            vm.addBird(flockId, fields)
                        },
                        enabled = ring.isNotBlank() && !isLoading,
                    ) {
                        if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.save))
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
            OutlinedTextField(
                value = ring,
                onValueChange = { ring = it },
                label = { Text(stringResource(R.string.ring) + " *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = ring.isBlank() && errorMsg != null,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
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
