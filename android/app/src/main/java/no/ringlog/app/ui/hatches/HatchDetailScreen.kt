package no.ringlog.app.ui.hatches

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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import no.ringlog.app.R
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen

private fun formatIsoDateTime(dt: String): String {
    return try {
        val ldt = LocalDateTime.parse(dt.replace(" ", "T").take(19))
        ldt.format(DateTimeFormatter.ofPattern("d MMM yyyy  HH:mm", Locale.getDefault()))
    } catch (_: Exception) { dt.take(16).replace("T", "  ") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HatchDetailScreen(hatchId: Int, onBack: () -> Unit, vm: HatchViewModel = hiltViewModel()) {
    val state by vm.detailState.collectAsStateWithLifecycle()
    LaunchedEffect(hatchId) { vm.loadHatch(hatchId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? HatchViewModel.DetailState.Success)?.hatch?.name
                    ?: stringResource(R.string.hatch)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
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
                        if (tl?.progress_pct != null) {
                            Text(stringResource(R.string.progress), style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(
                                progress = { (tl.progress_pct / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(stringResource(R.string.progress_detail, tl.progress_pct, tl.days_remaining ?: 0),
                                 style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }

                        val statusLabel = when (tl?.status) {
                            "incubating" -> stringResource(R.string.status_incubating_label)
                            "lockdown"   -> stringResource(R.string.status_lockdown_label)
                            "hatching"   -> stringResource(R.string.status_hatching_label)
                            "done"       -> stringResource(R.string.status_done_label)
                            else -> tl?.status?.replaceFirstChar { it.uppercase() } ?: "—"
                        }
                        DetailRow(stringResource(R.string.status),         statusLabel)
                        DetailRow(stringResource(R.string.started),        h.start_datetime.take(10))
                        if (tl?.lockdown_dt != null)
                            DetailRow(stringResource(R.string.lockdown),   formatIsoDateTime(tl.lockdown_dt))
                        if (tl?.hatch_dt != null)
                            DetailRow(stringResource(R.string.expected_hatch), formatIsoDateTime(tl.hatch_dt))
                        Spacer(Modifier.height(8.dp))

                        Text(stringResource(R.string.eggs), style = MaterialTheme.typography.titleSmall)
                        DetailRow(stringResource(R.string.set),         h.egg_count?.toString() ?: "—")
                        DetailRow(stringResource(R.string.discarded),   h.eggs_discarded?.toString() ?: "—")
                        DetailRow(stringResource(R.string.to_brooder),  h.eggs_brooder?.toString() ?: "—")
                        DetailRow(stringResource(R.string.hatched),     h.eggs_hatched?.toString() ?: "—")
                        Spacer(Modifier.height(8.dp))

                        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleSmall)
                        DetailRow(stringResource(R.string.species),
                            h.species.replaceFirstChar { it.uppercase() })
                        DetailRow(stringResource(R.string.incubation),
                            stringResource(R.string.incubation_days, h.incubation_days))
                        if (h.humidity_incubation != null)
                            DetailRow(stringResource(R.string.humidity_incubation), "${h.humidity_incubation}%")
                        if (h.humidity_lockdown != null)
                            DetailRow(stringResource(R.string.humidity_lockdown), "${h.humidity_lockdown}%")
                        if (!h.notes.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            DetailRow(stringResource(R.string.notes), h.notes)
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
