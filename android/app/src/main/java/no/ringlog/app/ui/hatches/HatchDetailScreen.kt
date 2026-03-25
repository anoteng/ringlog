package no.ringlog.app.ui.hatches

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HatchDetailScreen(hatchId: Int, onBack: () -> Unit, vm: HatchViewModel = hiltViewModel()) {
    val state by vm.detailState.collectAsStateWithLifecycle()
    LaunchedEffect(hatchId) { vm.loadHatch(hatchId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? HatchViewModel.DetailState.Success)?.hatch?.name ?: "Hatch") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is HatchViewModel.DetailState.Loading -> LoadingScreen()
                is HatchViewModel.DetailState.Error   -> ErrorScreen(s.msg) { vm.loadHatch(hatchId) }
                is HatchViewModel.DetailState.Success -> {
                    val h = s.hatch
                    val tl = h.timeline
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Progress
                        if (tl?.progress_pct != null) {
                            Text("Progress", style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(
                                progress = { (tl.progress_pct / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${tl.progress_pct}% · ${tl.days_remaining ?: "?"} days remaining",
                                 style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }

                        // Dates
                        DetailRow("Status",      tl?.status?.replaceFirstChar { it.uppercase() } ?: "—")
                        DetailRow("Started",     h.start_datetime.take(10))
                        if (tl?.lockdown_dt != null) DetailRow("Lockdown", tl.lockdown_dt.take(10))
                        if (tl?.hatch_dt != null)    DetailRow("Expected hatch", tl.hatch_dt.take(10))
                        Spacer(Modifier.height(8.dp))

                        // Eggs
                        Text("Eggs", style = MaterialTheme.typography.titleSmall)
                        DetailRow("Set",       h.egg_count?.toString() ?: "—")
                        DetailRow("Discarded", h.eggs_discarded?.toString() ?: "—")
                        DetailRow("To brooder",h.eggs_brooder?.toString() ?: "—")
                        DetailRow("Hatched",   h.eggs_hatched?.toString() ?: "—")
                        Spacer(Modifier.height(8.dp))

                        // Settings
                        Text("Settings", style = MaterialTheme.typography.titleSmall)
                        DetailRow("Species",            h.species.replaceFirstChar { it.uppercase() })
                        DetailRow("Incubation",         "${h.incubation_days} days")
                        DetailRow("Lockdown day",       "day ${h.lockdown_day}")
                        if (h.humidity_incubation != null) DetailRow("Humidity (inc.)", "${h.humidity_incubation}%")
                        if (h.humidity_lockdown != null)   DetailRow("Humidity (lock.)", "${h.humidity_lockdown}%")
                        if (!h.notes.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            DetailRow("Notes", h.notes)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
             modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
