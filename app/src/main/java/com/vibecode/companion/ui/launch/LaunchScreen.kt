package com.vibecode.companion.ui.launch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import com.vibecode.companion.data.api.ModelListItem
import com.vibecode.companion.ui.common.relativeTime
import com.vibecode.companion.ui.theme.GradientButton
import java.time.Instant

/** "owner/name" from a GitHub URL — keeps the picker compact. */
private fun repoDisplayName(url: String): String =
    url.removePrefix("https://github.com/").removeSuffix(".git").trimEnd('/')

/**
 * Stateful launch screen: binds [LaunchViewModel], navigates away on a
 * successful launch, relays launch errors to a snackbar, and renders
 * [LaunchContent].
 */
@Composable
fun LaunchScreen(onLaunched: (agentId: String) -> Unit, onBack: () -> Unit) {
    val vm: LaunchViewModel = metroViewModel()
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

    LaunchContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onPromptChange = vm::setPrompt,
        onVoiceResult = vm::appendVoiceText,
        onSelectRepo = vm::selectRepo,
        onRefreshRepos = vm::refreshRepos,
        onSelectModel = vm::selectModel,
        onAutoCreatePrChange = vm::setAutoCreatePr,
        onPlanModeChange = vm::setPlanMode,
        onLaunch = vm::launch,
    )
}

/**
 * Stateless launch UI: the prompt hero (with voice dictation), the repo + model
 * configuration card, the PR/mode option toggles, and the gradient launch
 * button. Rendered purely from [state] plus callbacks so it can be
 * screenshot-tested from fixtures.
 *
 * @param state the launch UI state to render.
 * @param snackbarHostState host for transient launch-error messages.
 * @param onBack invoked by the top-bar back button.
 * @param onPromptChange invoked as the prompt text changes.
 * @param onVoiceResult invoked with dictated text to append to the prompt.
 * @param onSelectRepo invoked with the chosen repository URL.
 * @param onRefreshRepos invoked to re-fetch the repository list.
 * @param onSelectModel invoked with the chosen model id (null = "Default").
 * @param onAutoCreatePrChange invoked when the "create PR" toggle changes.
 * @param onPlanModeChange invoked when the Agent/Plan mode selection changes.
 * @param onLaunch invoked to create the agent from the current selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchContent(
    state: LaunchUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onPromptChange: (String) -> Unit,
    onVoiceResult: (String) -> Unit,
    onSelectRepo: (String) -> Unit,
    onRefreshRepos: () -> Unit,
    onSelectModel: (String?) -> Unit,
    onAutoCreatePrChange: (Boolean) -> Unit,
    onPlanModeChange: (Boolean) -> Unit,
    onLaunch: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New agent") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PromptHeroCard(
                prompt = state.prompt,
                onPromptChange = onPromptChange,
                onVoiceResult = onVoiceResult,
            )

            ConfigurationSection(
                repoUrls = state.repoUrls,
                selectedRepo = state.selectedRepo,
                reposLoading = state.reposLoading,
                repoError = state.repoError,
                repoFetchedAtEpochMs = state.repoFetchedAtEpochMs,
                onSelectRepo = onSelectRepo,
                onRefreshRepos = onRefreshRepos,
                models = state.models,
                selectedModelId = state.selectedModelId,
                onSelectModel = onSelectModel,
            )

            OptionsSection(
                autoCreatePr = state.autoCreatePr,
                onAutoCreatePrChange = onAutoCreatePrChange,
                planMode = state.planMode,
                onPlanModeChange = onPlanModeChange,
            )

            GradientButton(
                text = if (state.launching) "Launching…" else "Launch agent",
                onClick = onLaunch,
                enabled = state.canLaunch,
                loading = state.launching,
                leading = {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )

            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Section label: small, uppercase, muted — anchors each block of controls. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The hero: a large, prominent prompt area inside an elevated card with the mic
 * tucked into the bottom-right. This is the "tell the agent what to do" moment.
 */
@Composable
private fun PromptHeroCard(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onVoiceResult: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "New task",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    placeholder = {
                        Text(
                            "What should the agent do?",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    textStyle = MaterialTheme.typography.titleMedium,
                    minLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        errorBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Tap the mic to dictate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    VoiceInputButton(onResult = onVoiceResult)
                }
            }
        }
    }
}

/**
 * Compact configuration block: the repo and model selectors live together in a
 * single tonal card under a section header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationSection(
    repoUrls: List<String>,
    selectedRepo: String?,
    reposLoading: Boolean,
    repoError: String?,
    repoFetchedAtEpochMs: Long?,
    onSelectRepo: (String) -> Unit,
    onRefreshRepos: () -> Unit,
    models: List<ModelListItem>,
    selectedModelId: String?,
    onSelectModel: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            SectionLabel("Configuration")
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RepoSelector(
                    repoUrls = repoUrls,
                    selectedRepo = selectedRepo,
                    loading = reposLoading,
                    error = repoError,
                    fetchedAtEpochMs = repoFetchedAtEpochMs,
                    onSelect = onSelectRepo,
                    onRefresh = onRefreshRepos,
                )
                ModelSelector(
                    models = models,
                    selectedModelId = selectedModelId,
                    onSelect = onSelectModel,
                )
            }
        }
    }
}

/** Toggles: PR-on-done switch and the Agent/Plan mode picker, cleanly labeled. */
@Composable
private fun OptionsSection(
    autoCreatePr: Boolean,
    onAutoCreatePrChange: (Boolean) -> Unit,
    planMode: Boolean,
    onPlanModeChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CallSplit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Create PR when done",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Open a pull request with the agent's changes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = autoCreatePr,
                    onCheckedChange = onAutoCreatePrChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Mode")
            ModePicker(planMode = planMode, onPlanModeChange = onPlanModeChange)
        }
    }
}

/** Two-segment Agent/Plan selector choosing how the agent runs. */
@Composable
private fun ModePicker(planMode: Boolean, onPlanModeChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !planMode,
            onClick = { onPlanModeChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {},
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Agent")
            }
        }
        SegmentedButton(
            selected = planMode,
            onClick = { onPlanModeChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {},
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Plan")
            }
        }
    }
}

/**
 * Repository picker: a read-only dropdown of repo URLs with a refresh action and
 * supporting text reflecting the loading / error / last-updated state.
 */
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Repository")
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
                    placeholder = { Text("Select a repository") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    isError = error != null,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
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

            Spacer(Modifier.width(4.dp))
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(12.dp)
                        .size(22.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh repositories",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** Model picker dropdown; the "Default" entry (null id) lets the server choose. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    models: List<ModelListItem>,
    selectedModelId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = models.firstOrNull { it.id == selectedModelId }?.displayName ?: "Default"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Model")
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
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
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
}
