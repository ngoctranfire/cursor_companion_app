package com.vibecode.companion.ui.detail

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
import android.content.Intent
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecode.companion.data.api.RunGitBranch
import com.vibecode.companion.data.api.RunStatus
import com.vibecode.companion.ui.common.companionViewModel
import com.vibecode.companion.ui.common.formatDuration
import com.vibecode.companion.ui.common.relativeTime

/**
 * Agent detail: live run timeline over SSE, past runs, git/PR links, and a
 * follow-up composer. The heart of the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(agentId: String, onBack: () -> Unit) {
    val vm = companionViewModel { container ->
        AgentDetailViewModel(container.apiClient, container.runStreamClient, agentId)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.transientMessage) {
        val message = state.transientMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeMessage()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.agent?.name ?: "Agent",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val newest = state.newestRun
                        if (newest != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RunStatusChip(state.liveRunStatus)
                                Text(
                                    text = relativeTime(newest.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (RunStatus.isTerminal(state.liveRunStatus) && newest.durationMs != null) {
                                    Text(
                                        text = formatDuration(newest.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
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
                        TextButton(onClick = vm::cancelRun, enabled = !state.isCancelling) {
                            Text("Cancel run", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    val agentUrl = state.agent?.url
                    if (agentUrl != null) {
                        IconButton(
                            onClick = {
                                // Hand off to desktop: this link opens Cursor Web, whose
                                // "Open in Cursor" button deep-links into the desktop app.
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, agentUrl)
                                }
                                context.startActivity(Intent.createChooser(send, "Share agent link"))
                            },
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share agent link")
                        }
                    }
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        bottomBar = {
            FollowUpComposer(
                text = state.followUpText,
                onTextChange = vm::onFollowUpTextChange,
                onSend = vm::sendFollowUp,
                isSending = state.isSending,
            )
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
                Text("Couldn't load agent", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = loadError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = vm::retryLoad) { Text("Retry") }
            }

            else -> DetailBody(
                state = state,
                onReconnect = vm::reconnect,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun DetailBody(
    state: AgentDetailUiState,
    onReconnect: () -> Unit,
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
                PastRunCard(run)
            }
            item(key = "current_header") {
                Spacer(Modifier.height(4.dp))
                SectionHeader("Latest run")
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
            TimelineItemView(timelineItem, state.agent?.url)
        }

        if (state.isStreaming) {
            item(key = "live") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.timeline.isEmpty()) "Waiting for events…" else "Live",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.showReconnect) {
            item(key = "reconnect") {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "Connection lost",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onReconnect) { Text("Reconnect") }
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
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SectionHeader("Branches & PRs")
                        GitSection(branches = branches, agentUrl = agentUrl)
                    }
                }
            }
        }
    }
}

private fun LazyListState.isNearBottom(threshold: Int = 3): Boolean {
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= info.totalItemsCount - 1 - threshold
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FollowUpComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Send a follow-up…") },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !isSending,
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send follow-up",
                        tint = if (text.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
