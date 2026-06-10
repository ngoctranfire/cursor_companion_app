package com.vibecode.companion.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.api.RunGitBranch
import com.vibecode.companion.data.api.RunStatus
import com.vibecode.companion.ui.common.formatDuration
import com.vibecode.companion.ui.common.relativeTime

private val COMPLETED_TOOL_STATUSES = setOf("completed", "success", "succeeded", "finished", "done")
private val FAILED_TOOL_STATUSES = setOf("failed", "error", "errored", "cancelled")

/** Small colored status chip for a run. */
@Composable
fun RunStatusChip(status: String?, modifier: Modifier = Modifier) {
    val color = when (status) {
        RunStatus.RUNNING, RunStatus.CREATING -> MaterialTheme.colorScheme.primary
        RunStatus.FINISHED -> MaterialTheme.colorScheme.secondary
        RunStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline // CANCELLED / EXPIRED / unknown
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f),
        contentColor = color,
    ) {
        Text(
            text = status ?: "UNKNOWN",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Dispatches one timeline entry to its renderer. */
@Composable
fun TimelineItemView(item: TimelineItem, agentUrl: String?) {
    when (item) {
        is TimelineItem.UserPrompt -> UserPromptBubble(item)
        is TimelineItem.AssistantText -> AssistantTextItem(item)
        is TimelineItem.Thinking -> ThinkingBlock(item)
        is TimelineItem.Tool -> ToolCallRow(item)
        is TimelineItem.ResultCard -> ResultCardView(item, agentUrl)
    }
}

@Composable
private fun UserPromptBubble(item: TimelineItem.UserPrompt) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = "You",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = item.text.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssistantTextItem(item: TimelineItem.AssistantText) {
    Text(
        text = item.text.trim(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ThinkingBlock(item: TimelineItem.Thinking) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse thinking" else "Expand thinking",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = item.text.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToolCallRow(item: TimelineItem.Tool) {
    val normalized = item.status?.lowercase().orEmpty()
    val expandable = item.argsPretty != null || item.resultPretty != null
    var expanded by remember(item.callId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (normalized) {
                in COMPLETED_TOOL_STATUSES -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completed",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp),
                )
                in FAILED_TOOL_STATUSES -> Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                else -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.name ?: "tool",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (item.detail != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (expandable) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse tool details" else "Expand tool details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (expanded) {
            ToolJsonBlock(label = "Args", json = item.argsPretty)
            ToolJsonBlock(label = "Result", json = item.resultPretty)
        }
    }
}

@Composable
private fun ToolJsonBlock(label: String, json: String?) {
    if (json == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        ) {
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun ResultCardView(item: TimelineItem.ResultCard, agentUrl: String?) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RunStatusChip(item.status)
                if (item.durationMs != null) {
                    Text(
                        text = formatDuration(item.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!item.text.isNullOrBlank()) {
                Text(
                    text = item.text.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val branches = item.git?.branches.orEmpty()
            if (branches.isNotEmpty()) {
                GitSection(branches = branches, agentUrl = agentUrl)
            }
        }
    }
}

/**
 * Branch list with deep links: branch name in monospace, "Open PR" when a PR
 * exists, and a single "Open in Cursor" link to the agent page.
 */
@Composable
fun GitSection(branches: List<RunGitBranch>, agentUrl: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        branches.forEach { branch ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = branch.branch ?: branch.repoUrl.removeSuffix("/").substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val prUrl = branch.prUrl
                if (prUrl != null) {
                    TextButton(onClick = { openUrl(context, prUrl) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Open PR", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (agentUrl != null) {
            TextButton(onClick = { openUrl(context, agentUrl) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Open in Cursor", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Compact collapsed card for a past (non-newest) run; tap to expand the result
 * text, [onViewSteps] replays the run's full step-by-step history.
 */
@Composable
fun PastRunCard(run: Run, modifier: Modifier = Modifier, onViewSteps: (() -> Unit)? = null) {
    var expanded by remember(run.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RunStatusChip(run.status)
                Text(
                    text = relativeTime(run.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (run.durationMs != null) {
                    Text(
                        text = formatDuration(run.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (onViewSteps != null) {
                    TextButton(onClick = onViewSteps) {
                        Text("View steps", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val result = run.result
            if (!result.isNullOrBlank()) {
                Text(
                    text = result.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser installed — nothing sensible to do in a sample app.
    }
}
