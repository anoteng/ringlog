package no.ringlog.app.ui.flocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.R
import no.ringlog.app.data.api.Flock
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen
import no.ringlog.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlockListScreen(
    onFlockClick: (Int) -> Unit,
    onNewFlock: () -> Unit,
    vm: FlockViewModel = hiltViewModel(),
) {
    val state       by vm.listState.collectAsStateWithLifecycle()
    val actionState by vm.flockActionState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadFlocks() }

    val errorMsg = (actionState as? FlockViewModel.FlockActionState.Error)?.msg
    if (errorMsg != null) {
        AlertDialog(
            onDismissRequest = { vm.resetFlockAction() },
            title   = { Text(stringResource(R.string.error)) },
            text    = { Text(errorMsg) },
            confirmButton = { TextButton(onClick = { vm.resetFlockAction() }) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.my_flocks)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewFlock) {
                Icon(Icons.Default.Add, stringResource(R.string.new_flock))
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is FlockViewModel.ListState.Loading -> LoadingScreen()
                is FlockViewModel.ListState.Error   -> ErrorScreen(s.msg) { vm.loadFlocks() }
                is FlockViewModel.ListState.Success -> {
                    val all = s.data
                    LazyColumn {
                        if (all.owned.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.my_flocks_header)) }
                            items(all.owned, key = { it.id }) { flock ->
                                FlockRow(
                                    flock = flock,
                                    onClick = { onFlockClick(flock.id) },
                                    canDelete = (flock.bird_count ?: 0) == 0,
                                    onDelete = { vm.deleteFlock(flock.id) {} },
                                )
                            }
                        }
                        if (all.shared.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.shared_with_me)) }
                            items(all.shared, key = { it.id }) { flock ->
                                FlockRow(flock = flock, onClick = { onFlockClick(flock.id) })
                            }
                        }
                        if (all.owned.isEmpty() && all.shared.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.no_flocks),
                                         style = MaterialTheme.typography.bodyMedium,
                                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlockRow(
    flock: Flock,
    onClick: (Int) -> Unit,
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
) {
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title   = { Text(stringResource(R.string.delete_flock)) },
            text    = { Text(stringResource(R.string.delete_flock_confirm, flock.name)) },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onDelete() },
                           colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.back)) }
            },
        )
    }

    val count = flock.bird_count ?: 0
    val owner = if (flock.owner_username != null) " · ${flock.owner_username}" else ""
    ListItem(
        headlineContent   = { Text(flock.name) },
        supportingContent = { Text("${pluralStringResource(R.plurals.bird_count, count, count)}$owner") },
        trailingContent   = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canDelete) {
                    IconButton(onClick = { showConfirm = true }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete),
                             tint = MaterialTheme.colorScheme.error)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        },
        modifier = Modifier.clickable { onClick(flock.id) },
    )
    HorizontalDivider(Modifier.padding(start = 16.dp))
}
