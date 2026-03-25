package no.ringlog.app.ui.flocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.data.api.Flock
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen
import no.ringlog.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlockListScreen(onFlockClick: (Int) -> Unit, vm: FlockViewModel = hiltViewModel()) {
    val state by vm.listState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadFlocks() }

    Scaffold(topBar = { TopAppBar(title = { Text("My Flocks") }) }) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is FlockViewModel.ListState.Loading -> LoadingScreen()
                is FlockViewModel.ListState.Error   -> ErrorScreen(s.msg) { vm.loadFlocks() }
                is FlockViewModel.ListState.Success -> {
                    val all = s.data
                    LazyColumn {
                        if (all.owned.isNotEmpty()) {
                            item { SectionHeader("MY FLOCKS") }
                            items(all.owned) { FlockRow(it, onFlockClick) }
                        }
                        if (all.shared.isNotEmpty()) {
                            item { SectionHeader("SHARED WITH ME") }
                            items(all.shared) { FlockRow(it, onFlockClick) }
                        }
                        if (all.owned.isEmpty() && all.shared.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No flocks yet. Create one on ringlog.no.", style = MaterialTheme.typography.bodyMedium,
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
private fun FlockRow(flock: Flock, onClick: (Int) -> Unit) {
    ListItem(
        headlineContent  = { Text(flock.name) },
        supportingContent = {
            val count = flock.bird_count ?: 0
            val owner = if (flock.owner_username != null) " · ${flock.owner_username}" else ""
            Text("$count birds$owner")
        },
        trailingContent  = { Icon(Icons.Default.KeyboardArrowRight, null) },
        modifier = Modifier.clickable { onClick(flock.id) },
    )
    HorizontalDivider(Modifier.padding(start = 16.dp))
}
