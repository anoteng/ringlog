package no.ringlog.app.ui.flocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.ringlog.app.ui.util.birdAge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.R
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
    onAddBird: (Int) -> Unit = {},
    onBatchAdd: (Int) -> Unit = {},
    vm: FlockViewModel = hiltViewModel(),
) {
    val state by vm.detailState.collectAsStateWithLifecycle()
    LaunchedEffect(flockId) { vm.loadFlock(flockId) }

    val flock     = (state as? FlockViewModel.DetailState.Success)?.flock
    val flockName = flock?.name ?: stringResource(R.string.my_flocks)
    val canEdit   = flock?.can_edit == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(flockName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (canEdit) {
                        IconButton(onClick = { onBatchAdd(flockId) }) {
                            Icon(Icons.Default.PlaylistAdd, stringResource(R.string.batch_add))
                        }
                    }
                    TextButton(onClick = { onReportClick(flockId, flockName) }) {
                        Text(stringResource(R.string.report))
                    }
                },
            )
        },
        floatingActionButton = {
            if (canEdit) {
                FloatingActionButton(onClick = { onAddBird(flockId) }) {
                    Icon(Icons.Default.Add, stringResource(R.string.add_bird))
                }
            }
        },
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
                            item {
                                Text(stringResource(R.string.no_birds_in_flock),
                                     Modifier.padding(16.dp),
                                     color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                        items(alive) { BirdRow(it, onBirdClick) }
                        if (dead.isNotEmpty()) {
                            item {
                                Text(stringResource(R.string.deceased_sold),
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                     modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                            }
                            items(dead) { BirdRow(it, onBirdClick) }
                        }
                        if (s.flock.is_owner) {
                            item { HorizontalDivider(Modifier.padding(top = 16.dp)) }
                            item {
                                Text(stringResource(R.string.settings),
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                     modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                            }
                            item {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.allow_ring_reuse))
                                        Text(stringResource(R.string.allow_ring_reuse_desc),
                                             style = MaterialTheme.typography.bodySmall,
                                             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    }
                                    Switch(
                                        checked = s.flock.allow_ring_reuse,
                                        onCheckedChange = { vm.setAllowRingReuse(flockId, it) },
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(16.dp)) }
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
        birdAge(bird.birth_date)?.let { append(" · $it") }
    }
    ListItem(
        headlineContent   = { Text(label) },
        supportingContent = { Text(sub) },
        trailingContent   = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier = Modifier.clickable { onClick(bird.id) },
    )
    HorizontalDivider(Modifier.padding(start = 16.dp))
}
