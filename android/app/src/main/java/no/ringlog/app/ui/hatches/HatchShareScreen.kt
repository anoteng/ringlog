package no.ringlog.app.ui.hatches

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HatchShareScreen(
    hatchId: Int,
    onBack: () -> Unit,
    vm: HatchShareViewModel = hiltViewModel(),
) {
    val state      by vm.state.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.load(hatchId) }

    var username  by remember { mutableStateOf("") }
    var canEdit   by remember { mutableStateOf(false) }

    if (actionError != null) {
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title   = { Text(stringResource(R.string.error)) },
            text    = { Text(actionError!!) },
            confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_hatch)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.share_with_user), style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.can_edit))
                Switch(checked = canEdit, onCheckedChange = { canEdit = it })
            }
            Button(
                onClick = {
                    vm.addShare(hatchId, username.trim(), canEdit)
                    username = ""
                    canEdit = false
                },
                enabled = username.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.add)) }

            HorizontalDivider()

            when (val s = state) {
                is HatchShareViewModel.SharesState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                is HatchShareViewModel.SharesState.Error ->
                    Text(s.msg, color = MaterialTheme.colorScheme.error)
                is HatchShareViewModel.SharesState.Success -> {
                    if (s.shares.isEmpty()) {
                        Text(stringResource(R.string.no_shares),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(s.shares, key = { it.id }) { share ->
                                ListItem(
                                    headlineContent = { Text(share.username) },
                                    supportingContent = {
                                        Text(
                                            if (share.can_edit) stringResource(R.string.perm_view_edit)
                                            else stringResource(R.string.perm_view)
                                        )
                                    },
                                    trailingContent = {
                                        IconButton(onClick = { vm.revokeShare(hatchId, share.id) }) {
                                            Icon(Icons.Default.Delete, stringResource(R.string.revoke))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
