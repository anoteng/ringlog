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
fun FlockListScreen(onFlockClick: (Int) -> Unit, vm: FlockViewModel = hiltViewModel()) {
    val state by vm.listState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadFlocks() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.my_flocks)) }) }) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is FlockViewModel.ListState.Loading -> LoadingScreen()
                is FlockViewModel.ListState.Error   -> ErrorScreen(s.msg) { vm.loadFlocks() }
                is FlockViewModel.ListState.Success -> {
                    val all = s.data
                    LazyColumn {
                        if (all.owned.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.my_flocks_header)) }
                            items(all.owned) { FlockRow(it, onFlockClick) }
                        }
                        if (all.shared.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.shared_with_me)) }
                            items(all.shared) { FlockRow(it, onFlockClick) }
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
private fun FlockRow(flock: Flock, onClick: (Int) -> Unit) {
    val count = flock.bird_count ?: 0
    val owner = if (flock.owner_username != null) " · ${flock.owner_username}" else ""
    ListItem(
        headlineContent  = { Text(flock.name) },
        supportingContent = {
            Text("${pluralStringResource(R.plurals.bird_count, count, count)}$owner")
        },
        trailingContent  = { Icon(Icons.Default.KeyboardArrowRight, null) },
        modifier = Modifier.clickable { onClick(flock.id) },
    )
    HorizontalDivider(Modifier.padding(start = 16.dp))
}
