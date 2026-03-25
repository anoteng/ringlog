package no.ringlog.app.ui.flocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.data.api.Bird
import no.ringlog.app.ui.components.ErrorScreen
import no.ringlog.app.ui.components.LoadingScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlockDetailScreen(
    flockId: Int,
    onBack: () -> Unit,
    onBirdClick: (Int) -> Unit,
    onReportClick: (Int, String) -> Unit,
    vm: FlockViewModel = hiltViewModel(),
) {
    val state by vm.detailState.collectAsStateWithLifecycle()
    LaunchedEffect(flockId) { vm.loadFlock(flockId) }

    val flockName = (state as? FlockViewModel.DetailState.Success)?.flock?.name ?: "Flock"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(flockName) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { onReportClick(flockId, flockName) }) { Text("Report") }
                },
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                is FlockViewModel.DetailState.Loading -> LoadingScreen()
                is FlockViewModel.DetailState.Error   -> ErrorScreen(s.msg) { vm.loadFlock(flockId) }
                is FlockViewModel.DetailState.Success -> {
                    val birds = s.flock.birds.orEmpty()
                    val alive  = birds.filter { !it.is_dead && !it.is_sold }
                    val dead   = birds.filter { it.is_dead || it.is_sold }
                    LazyColumn {
                        if (alive.isEmpty() && dead.isEmpty()) {
                            item { Spacer(Modifier.height(32.dp)) }
                            item { Text("No birds in this flock.", Modifier.padding(16.dp),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                        }
                        items(alive) { BirdRow(it, onBirdClick) }
                        if (dead.isNotEmpty()) {
                            item { Text("DECEASED / SOLD",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
                            items(dead) { BirdRow(it, onBirdClick) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BirdRow(bird: Bird, onClick: (Int) -> Unit) {
    val label = bird.name ?: bird.ring_number
    val sub = buildString {
        append(bird.ring_number)
        if (bird.name != null) append(" · ${bird.name}")
        append(" · ${bird.species.replaceFirstChar { it.uppercase() }}")
        if (!bird.breed.isNullOrBlank()) append(" ${bird.breed}")
        append(" · ${bird.sex.replaceFirstChar { it.uppercase() }}")
    }
    ListItem(
        headlineContent   = { Text(label) },
        supportingContent = { Text(sub) },
        trailingContent   = { Icon(Icons.Default.KeyboardArrowRight, null) },
        modifier = Modifier.clickable { onClick(bird.id) },
    )
    HorizontalDivider(Modifier.padding(start = 16.dp))
}
