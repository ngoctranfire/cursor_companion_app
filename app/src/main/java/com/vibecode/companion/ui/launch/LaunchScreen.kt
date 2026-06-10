package com.vibecode.companion.ui.launch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecode.companion.data.api.ModelListItem
import com.vibecode.companion.ui.common.companionViewModel
import com.vibecode.companion.ui.common.relativeTime
import java.time.Instant

/** "owner/name" from a GitHub URL — keeps the picker compact. */
private fun repoDisplayName(url: String): String =
    url.removePrefix("https://github.com/").removeSuffix(".git").trimEnd('/')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchScreen(onLaunched: (agentId: String) -> Unit, onBack: () -> Unit) {
    val vm = companionViewModel { container ->
        LaunchViewModel(container.apiClient, container.repoCache, container.promptStore)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.launchedAgentId) {
        val agentId = state.launchedAgentId
        if (agentId != null) {
            vm.consumeLaunched()
            onLaunched(agentId)
        }
    }

    LaunchedEffect(state.launchError) {
        state.launchError?.let { error ->
            snackbarHostState.showSnackbar(error)
            vm.consumeLaunchError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New agent") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RepoSelector(
                repoUrls = state.repoUrls,
                selectedRepo = state.selectedRepo,
                loading = state.reposLoading,
                error = state.repoError,
                fetchedAtEpochMs = state.repoFetchedAtEpochMs,
                onSelect = vm::selectRepo,
                onRefresh = vm::refreshRepos,
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = vm::setPrompt,
                    label = { Text("What should the agent do?") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                VoiceInputButton(
                    onResult = vm::appendVoiceText,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                )
            }

            ModelSelector(
                models = state.models,
                selectedModelId = state.selectedModelId,
                onSelect = vm::selectModel,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Create PR when done",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.autoCreatePr, onCheckedChange = vm::setAutoCreatePr)
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !state.planMode,
                    onClick = { vm.setPlanMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("Agent")
                }
                SegmentedButton(
                    selected = state.planMode,
                    onClick = { vm.setPlanMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("Plan")
                }
            }

            Button(
                onClick = vm::launch,
                enabled = state.canLaunch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.launching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Launching…")
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Launch agent")
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoSelector(
    repoUrls: List<String>,
    selectedRepo: String?,
    loading: Boolean,
    error: String?,
    fetchedAtEpochMs: Long?,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedRepo?.let(::repoDisplayName) ?: "",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Repository") },
                placeholder = { Text("Select a repository") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                isError = error != null,
                supportingText = {
                    when {
                        error != null -> Text(error)
                        loading -> Text("Fetching repositories — this can take a while…")
                        fetchedAtEpochMs != null ->
                            Text("Updated " + relativeTime(Instant.ofEpochMilli(fetchedAtEpochMs).toString()))
                    }
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (repoUrls.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(if (loading) "Loading repositories…" else "No repositories — tap refresh") },
                        onClick = { expanded = false },
                        enabled = false,
                    )
                }
                repoUrls.forEach { url ->
                    DropdownMenuItem(
                        text = { Text(repoDisplayName(url)) },
                        onClick = {
                            onSelect(url)
                            expanded = false
                        },
                    )
                }
            }
        }

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(12.dp)
                    .size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh repositories")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    models: List<ModelListItem>,
    selectedModelId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = models.firstOrNull { it.id == selectedModelId }?.displayName ?: "Default"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Default") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName) },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
