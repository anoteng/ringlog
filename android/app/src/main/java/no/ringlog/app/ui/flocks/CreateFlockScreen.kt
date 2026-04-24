package no.ringlog.app.ui.flocks

import androidx.compose.foundation.layout.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFlockScreen(
    onCreated: (flockId: Int) -> Unit,
    onBack: () -> Unit,
    vm: FlockViewModel = hiltViewModel(),
) {
    val actionState by vm.flockActionState.collectAsStateWithLifecycle()

    LaunchedEffect(actionState) {
        if (actionState is FlockViewModel.FlockActionState.Success) {
            vm.resetFlockAction()
            onCreated((actionState as FlockViewModel.FlockActionState.Success).flockId)
        }
    }

    var name        by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    val isLoading = actionState is FlockViewModel.FlockActionState.Loading
    val errorMsg  = (actionState as? FlockViewModel.FlockActionState.Error)?.msg

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_flock)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { vm.createFlock(name.trim(), description.trim().ifBlank { null }) },
                        enabled = name.isNotBlank() && !isLoading,
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
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.flock_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.description)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (errorMsg != null) {
                Text(errorMsg, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
