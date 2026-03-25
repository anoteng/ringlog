package no.ringlog.app.ui.flocks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.ringlog.app.data.api.FlockReport
import no.ringlog.app.data.repository.FlockRepository
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import javax.inject.Inject

@HiltViewModel
class FlockReportViewModel @Inject constructor(private val repo: FlockRepository) : ViewModel() {
    sealed class State { object Loading : State()
        data class Success(val report: FlockReport, val flockName: String) : State()
        data class Error(val msg: String) : State() }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()
    var selectedDays by mutableIntStateOf(30)
        private set

    fun load(flockId: Int, flockName: String, days: Int = selectedDays) {
        selectedDays = days
        viewModelScope.launch {
            _state.value = State.Loading
            repo.getReport(flockId, days).fold(
                onSuccess = { _state.value = State.Success(it, flockName) },
                onFailure = { _state.value = State.Error(it.message ?: "Error") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlockReportScreen(
    flockId: Int,
    flockName: String,
    onBack: () -> Unit,
    vm: FlockReportViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(flockId) { vm.load(flockId, flockName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$flockName — Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is FlockReportViewModel.State.Loading -> LoadingScreen()
                is FlockReportViewModel.State.Error   -> ErrorScreen(s.msg) { vm.load(flockId, flockName) }
                is FlockReportViewModel.State.Success -> ReportContent(s.report, flockId, flockName, vm)
            }
        }
    }
}

@Composable
private fun ReportContent(report: FlockReport, flockId: Int, flockName: String, vm: FlockReportViewModel) {
    val dayOptions = listOf(30, 60, 90, 180, 365)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Period selector
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dayOptions.forEach { d ->
                    FilterChip(
                        selected = vm.selectedDays == d,
                        onClick  = { vm.load(flockId, flockName, d) },
                        label    = { Text("${d}d") },
                    )
                }
            }
        }

        // Summary cards
        item {
            val st = report.stats
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Total eggs",    st.total_eggs.toString(),     Modifier.weight(1f))
                StatCard("Daily avg",     st.avg_eggs_per_day.toString(), Modifier.weight(1f))
                StatCard("Best day",      st.best_day.toString(),       Modifier.weight(1f))
                StatCard("Days logged",   st.days_logged.toString(),    Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Laying hens", report.laying_count.toString(), Modifier.weight(1f))
            }
        }

        // Table header
        item {
            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("Date",  Modifier.weight(2f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Eggs", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    Text("Avg/hen", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    Text("Light", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                }
            }
        }

        // Log rows (newest first)
        val reversed = report.logs.reversed()
        items(reversed) { entry ->
            val eggsPerHen = if (entry.eggs_collected != null && report.laying_count > 0)
                "%.2f".format(entry.eggs_collected.toFloat() / report.laying_count) else "—"
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(entry.log_date, Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                Text(entry.eggs_collected?.toString() ?: "—", Modifier.weight(1f),
                     style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                Text(eggsPerHen, Modifier.weight(1.2f),
                     style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                Text(entry.light_hours?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f),
                     style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge,
                 color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                 textAlign = TextAlign.Center)
        }
    }
}
