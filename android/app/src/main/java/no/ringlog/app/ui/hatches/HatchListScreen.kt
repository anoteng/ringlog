package no.ringlog.app.ui.hatches

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.R
import no.ringlog.app.data.api.Hatch
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen
import no.ringlog.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HatchListScreen(onHatchClick: (Int) -> Unit, vm: HatchViewModel = hiltViewModel()) {
    val state by vm.listState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadHatches() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.hatches)) }) }) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is HatchViewModel.ListState.Loading -> LoadingScreen()
                is HatchViewModel.ListState.Error   -> ErrorScreen(s.msg) { vm.loadHatches() }
                is HatchViewModel.ListState.Success -> {
                    val all = s.data
                    LazyColumn {
                        if (all.owned.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.my_hatches)) }
                            items(all.owned) { HatchRow(it, onHatchClick) }
                        }
                        if (all.shared.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.shared_with_me)) }
                            items(all.shared) { HatchRow(it, onHatchClick) }
                        }
                        if (all.owned.isEmpty() && all.shared.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.no_hatches),
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
private fun HatchRow(hatch: Hatch, onClick: (Int) -> Unit) {
    val tl = hatch.timeline
    val statusText = when (tl?.status) {
        "incubating" -> stringResource(R.string.status_incubating, tl.days_remaining ?: 0)
        "lockdown"   -> stringResource(R.string.lockdown)
        "hatching"   -> stringResource(R.string.status_hatching)
        "done"       -> stringResource(R.string.status_done_hatched, hatch.eggs_hatched?.toString() ?: "?")
        else         -> tl?.status?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.unknown)
    }
    Column(Modifier.clickable { onClick(hatch.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(hatch.name ?: stringResource(R.string.hatch_number, hatch.id),
                 style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        Text("${hatch.species.replaceFirstChar { it.uppercase() }} · $statusText",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        if (tl?.progress_pct != null && tl.status in listOf("incubating", "lockdown")) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (tl.progress_pct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}
