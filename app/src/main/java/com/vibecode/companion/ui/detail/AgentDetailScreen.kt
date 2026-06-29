package com.vibecode.companion.ui.detail

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.api.RunGitBranch
import com.vibecode.companion.data.api.RunStatus
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import com.vibecode.companion.ui.common.StatusDot
import com.vibecode.companion.ui.common.StatusPill
import com.vibecode.companion.ui.common.formatDuration
import com.vibecode.companion.ui.common.relativeTime
import com.vibecode.companion.ui.theme.BrandGradient
import com.vibecode.companion.ui.theme.GradientButton
import com.vibecode.companion.ui.theme.runStatusColor

/**
 * Agent detail: live run timeline over SSE, past runs, git/PR links, and a
 * follow-up composer. The heart of the app.
 */
@Composable
fun AgentDetailScreen(agentId: String, onBack: () -> Unit) {
    // Assisted: the runtime agentId is supplied here (not the graph). Keyed by agentId so each
    // agent gets its own VM instance scoped to this nav entry's ViewModelStoreOwner.
    val vm = assistedMetroViewModel<AgentDetailViewModel, AgentDetailViewModel.Factory>(key = agentId) {
        create(agentId)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // SSE only while visible — the stream pauses with the screen and resumes
    // from the last seen event id.
    LifecycleResumeEffect(Unit) {
        vm.onScreenResumed()
        onPauseOrDispose { vm.onScreenPaused() }
    }

    LaunchedEffect(state.transientMessage) {
        val message = state.transientMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeMessage()
    }

    AgentDetailContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onShareAgentLink = {
            val agentUrl = state.agent?.url
            if (agentUrl != null) {
                // Hand off to desktop: this link opens Cursor Web, whose
                // "Open in Cursor" button deep-links into the desktop app.
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, agentUrl)
                }
                context.startActivity(Intent.createChooser(send, "Share agent link"))
            }
        },
        onRefresh = vm::refresh,
        onCancelRun = vm::cancelRun,
        onRetryLoad = vm::retryLoad,
        onReconnect = vm::reconnect,
        onViewPastRun = vm::viewPastRun,
        onViewLatest = vm::viewLatest,
        onFollowUpTextChange = vm::onFollowUpTextChange,
        onSendFollowUp = vm::sendFollowUp,
        onBuildPlan = vm::buildPlan,
    )
}

/**
 * Stateless agent-detail UI: the status top bar (cancel / share / refresh), the
 * follow-up composer bottom bar, and the body that switches between loading, a
 * full-screen load error, and the run timeline ([DetailBody]). Rendered purely
 * from [state] plus callbacks so each state can be screenshot-tested from
 * fixtures.
 *
 * @param state the agent-detail UI state to render.
 * @param snackbarHostState host for transient messages.
 * @param onBack invoked by the top-bar back button.
 * @param onShareAgentLink invoked to share the agent's Cursor link.
 * @param onRefresh invoked to re-fetch the agent and its runs.
 * @param onCancelRun invoked to cancel the in-flight run.
 * @param onRetryLoad invoked from the full-screen error to reload.
 * @param onReconnect invoked to re-attach the SSE stream after it drops.
 * @param onViewPastRun invoked with an earlier run to replay its history.
 * @param onViewLatest invoked to return from a past run to the latest one.
 * @param onFollowUpTextChange invoked as the composer text changes.
 * @param onSendFollowUp invoked to send the composed follow-up.
 * @param onBuildPlan invoked by the contextual Build button to implement a finished plan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailContent(
    state: AgentDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onShareAgentLink: () -> Unit,
    onRefresh: () -> Unit,
    onCancelRun: () -> Unit,
    onRetryLoad: () -> Unit,
    onReconnect: () -> Unit,
    onViewPastRun: (Run) -> Unit,
    onViewLatest: () -> Unit,
    onFollowUpTextChange: (String) -> Unit,
    onSendFollowUp: () -> Unit,
    onBuildPlan: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = state.agent?.name ?: "Agent",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val newest = state.newestRun
                        if (newest != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatusPill(state.liveRunStatus)
                                Text(
                                    text = relativeTime(newest.updatedAt),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (RunStatus.isTerminal(state.liveRunStatus) && newest.durationMs != null) {
                                    Text(
                                        text = "·",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = formatDuration(newest.durationMs),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isRunActive) {
                        TextButton(onClick = onCancelRun, enabled = !state.isCancelling) {
                            Text("Cancel run", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.agent?.url != null) {
                        IconButton(onClick = onShareAgentLink) {
                            Icon(Icons.Default.Share, contentDescription = "Share agent link")
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // Contextual bridge from a finished plan to building it. Visible only when the newest
                // run is a terminal PLAN run (state.canBuild); stays up but disabled while building.
                if (state.canBuild) {
                    BuildActionBar(isBuilding = state.isBuilding, onBuild = onBuildPlan)
                }
                FollowUpComposer(
                    text = state.followUpText,
                    onTextChange = onFollowUpTextChange,
                    onSend = onSendFollowUp,
                    isSending = state.isSending,
                )
            }
        },
    ) { padding ->
        val loadError = state.loadError
        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Couldn't load agent", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = loadError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                GradientButton(
                    text = "Retry",
                    onClick = onRetryLoad,
                    modifier = Modifier.width(200.dp),
                )
            }

            else -> DetailBody(
                state = state,
                onReconnect = onReconnect,
                onViewPastRun = onViewPastRun,
                onViewLatest = onViewLatest,
                contentPadding = padding,
            )
        }
    }
}

/**
 * Scrollable run view: earlier runs, the active or replayed run's timeline, the
 * live / reconnect indicators, and the branches & PRs card. Auto-scrolls to new
 * timeline items while the list is already near the bottom.
 */
@Composable
private fun DetailBody(
    state: AgentDetailUiState,
    onReconnect: () -> Unit,
    onViewPastRun: (com.vibecode.companion.data.api.Run) -> Unit,
    onViewLatest: () -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom when new items arrive while already near it.
    val lastTimelineItem = state.timeline.lastOrNull()
    LaunchedEffect(state.timeline.size, lastTimelineItem) {
        if (listState.isNearBottom()) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.animateScrollToItem(total - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Past runs, oldest first so the screen reads chronologically downward.
        val pastRunsOldestFirst = state.pastRuns.asReversed()
        if (pastRunsOldestFirst.isNotEmpty()) {
            item(key = "past_header") { SectionHeader("Earlier runs") }
            items(pastRunsOldestFirst, key = { "past_${it.id}" }) { run ->
                PastRunCard(run, onViewSteps = { onViewPastRun(run) })
            }
            item(key = "current_header") {
                Spacer(Modifier.height(4.dp))
                SectionHeader(if (state.viewedPastRun != null) "Run history" else "Latest run")
            }
        }

        val viewedPast = state.viewedPastRun
        if (viewedPast != null) {
            item(key = "past_banner") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Text(
                            text = "Viewing earlier run · ${relativeTime(viewedPast.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onViewLatest) { Text("Back to latest") }
                    }
                }
            }
        }

        if (state.newestRun == null && state.timeline.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "No runs yet — send a follow-up below to start one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.timeline) { timelineItem ->
            Box(modifier = Modifier.animateItem()) {
                TimelineItemView(timelineItem, state.agent?.url)
            }
        }

        if (state.isStreaming || state.isReplaying) {
            item(key = "live") {
                val liveColor = if (state.isReplaying) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    runStatusColor(state.liveRunStatus)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .animateItem()
                        .padding(vertical = 4.dp),
                ) {
                    StatusDot(color = liveColor, active = true, size = 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when {
                            state.isReplaying -> "Replaying history…"
                            state.timeline.isEmpty() -> "Waiting for events…"
                            else -> "Live"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = liveColor,
                    )
                }
            }
        }

        if (state.showReconnect) {
            item(key = "reconnect") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Text(
                            text = "Connection lost",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onReconnect) {
                            Text("Reconnect", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // Git/PR card: latest known branches for the newest run, with the
        // agent's repo config as fallback (RepoConfig carries url + prUrl).
        val branches = state.newestRun?.git?.branches.orEmpty().ifEmpty {
            state.agent?.repos.orEmpty().map { repo ->
                RunGitBranch(repoUrl = repo.url, branch = null, prUrl = repo.prUrl)
            }
        }
        val agentUrl = state.agent?.url
        if (branches.isNotEmpty() || agentUrl != null) {
            item(key = "git") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionHeader("Branches & PRs")
                        GitSection(branches = branches, agentUrl = agentUrl)
                    }
                }
            }
        }
    }
}

/** True when the last visible item is within [threshold] of the end — the cue to keep auto-scrolling as items stream in. */
private fun LazyListState.isNearBottom(threshold: Int = 3): Boolean {
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= info.totalItemsCount - 1 - threshold
}

/** Small uppercase, muted label that titles a section of the timeline. */
@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp),
    )
}

/**
 * Contextual "Build" action shown above the composer once a plan-mode run has finished: a primary
 * CTA that launches an agent run to implement the approved plan. Mirrors the Cancel button's
 * in-flight contract — while [isBuilding] the button shows a spinner and ignores taps.
 */
@Composable
private fun BuildActionBar(isBuilding: Boolean, onBuild: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Plan ready — build it into changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GradientButton(
                    text = if (isBuilding) "Building…" else "Build this plan",
                    onClick = onBuild,
                    enabled = !isBuilding,
                    loading = isBuilding,
                    leading = {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}

/**
 * Bottom composer for follow-up messages: a multiline text field and a gradient
 * send button that disables while empty or [isSending].
 */
@Composable
private fun FollowUpComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Send a follow-up…") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 4,
                )
                Spacer(Modifier.width(10.dp))
                val sendEnabled = text.isNotBlank() && !isSending
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (sendEnabled) BrandGradient
                            else androidx.compose.ui.graphics.SolidColor(
                                MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                        .clickable(enabled = sendEnabled, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send follow-up",
                            tint = if (sendEnabled) {
                                androidx.compose.ui.graphics.Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
