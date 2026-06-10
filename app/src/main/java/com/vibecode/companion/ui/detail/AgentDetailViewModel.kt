package com.vibecode.companion.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibecode.companion.data.api.CloudAgent
import com.vibecode.companion.data.api.CreateRunRequest
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.CursorApiException
import com.vibecode.companion.data.api.PromptBody
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.api.RunGit
import com.vibecode.companion.data.api.RunStatus
import com.vibecode.companion.data.api.RunStreamClient
import com.vibecode.companion.data.api.RunStreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/** One rendered entry in the live run timeline. */
sealed interface TimelineItem {
    data class AssistantText(val text: String) : TimelineItem
    data class Thinking(val text: String) : TimelineItem
    data class Tool(val callId: String?, val name: String?, val status: String?) : TimelineItem
    data class ResultCard(
        val status: String?,
        val text: String?,
        val durationMs: Long?,
        val git: RunGit?,
    ) : TimelineItem
}

data class AgentDetailUiState(
    val isLoading: Boolean = true,
    /** Fatal load failure (nothing to show) — renders a full-screen retry. */
    val loadError: String? = null,
    val agent: CloudAgent? = null,
    val newestRun: Run? = null,
    /** Runs older than the newest, newest-first (API order). */
    val pastRuns: List<Run> = emptyList(),
    /** Timeline for the newest run. */
    val timeline: List<TimelineItem> = emptyList(),
    /** Status chip value for the newest run (updated live by SSE status events). */
    val liveRunStatus: String? = null,
    val isStreaming: Boolean = false,
    /** True after reconnect attempts are exhausted — UI shows a Reconnect button. */
    val showReconnect: Boolean = false,
    val followUpText: String = "",
    val isSending: Boolean = false,
    val isCancelling: Boolean = false,
    /** One-shot snackbar message. */
    val transientMessage: String? = null,
) {
    val isRunActive: Boolean
        get() = newestRun != null && !RunStatus.isTerminal(liveRunStatus)
}

/**
 * State holder for the agent detail screen: loads agent + runs, streams the
 * newest run over SSE with Last-Event-ID resume + exponential backoff, sends
 * follow-ups, and cancels runs.
 */
class AgentDetailViewModel(
    private val apiClient: CursorApiClient,
    private val runStreamClient: RunStreamClient,
    private val agentId: String,
) : ViewModel() {

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }

    private val _uiState = MutableStateFlow(AgentDetailUiState())
    val uiState: StateFlow<AgentDetailUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var lastEventId: String? = null

    /** Kind of the previous text-producing SSE event, for delta coalescing. */
    private enum class TextKind { ASSISTANT, THINKING }

    private var lastTextKind: TextKind? = null

    init {
        load(initial = true)
    }

    fun retryLoad() = load(initial = true)

    /** Lightweight refresh from the topbar — re-fetches agent + runs. */
    fun refresh() {
        if (_uiState.value.isLoading) return
        load(initial = false)
    }

    fun onFollowUpTextChange(text: String) {
        _uiState.update { it.copy(followUpText = text) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    /** Re-attaches the SSE stream after reconnect attempts were exhausted. */
    fun reconnect() {
        val run = _uiState.value.newestRun ?: return
        startStreaming(run.id)
    }

    fun sendFollowUp() {
        val text = _uiState.value.followUpText.trim()
        if (text.isEmpty() || _uiState.value.isSending) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            try {
                val run = apiClient.createRun(agentId, CreateRunRequest(prompt = PromptBody(text))).run
                lastEventId = null
                lastTextKind = null
                _uiState.update { state ->
                    state.copy(
                        isSending = false,
                        followUpText = "",
                        pastRuns = listOfNotNull(state.newestRun) + state.pastRuns,
                        newestRun = run,
                        liveRunStatus = run.status,
                        timeline = emptyList(),
                        showReconnect = false,
                    )
                }
                startStreaming(run.id)
            } catch (ex: CursorApiException) {
                val message = when (ex.code) {
                    "agent_busy" -> "Agent is busy — wait for the current run to finish."
                    "rate_limit_exceeded" -> "Rate limited — try again in a moment."
                    else -> ex.message
                }
                _uiState.update { it.copy(isSending = false, transientMessage = message) }
            } catch (ex: IOException) {
                _uiState.update { it.copy(isSending = false, transientMessage = "Check your connection") }
            }
        }
    }

    fun cancelRun() {
        val runId = _uiState.value.newestRun?.id ?: return
        if (_uiState.value.isCancelling) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCancelling = true) }
            try {
                apiClient.cancelRun(agentId, runId)
                _uiState.update { it.copy(isCancelling = false) }
                load(initial = false)
            } catch (ex: CursorApiException) {
                _uiState.update { it.copy(isCancelling = false) }
                if (ex.code == "run_not_cancellable") {
                    load(initial = false)
                } else {
                    _uiState.update { it.copy(transientMessage = ex.message) }
                }
            } catch (ex: IOException) {
                _uiState.update { it.copy(isCancelling = false, transientMessage = "Check your connection") }
            }
        }
    }

    // ---- Loading ----

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = initial, loadError = null) }
            try {
                val agent = apiClient.getAgent(agentId)
                val runs = apiClient.listRuns(agentId, limit = 20).items
                val newest = runs.firstOrNull()
                val runChanged = newest?.id != _uiState.value.newestRun?.id
                if (runChanged) {
                    lastEventId = null
                    lastTextKind = null
                }
                _uiState.update { state ->
                    val seed = runChanged || state.timeline.isEmpty()
                    val timeline = when {
                        !seed -> state.timeline
                        newest != null && RunStatus.isTerminal(newest.status) ->
                            listOf(TimelineItem.ResultCard(newest.status, newest.result, newest.durationMs, newest.git))
                        else -> emptyList()
                    }
                    state.copy(
                        isLoading = false,
                        loadError = null,
                        agent = agent,
                        newestRun = newest,
                        pastRuns = runs.drop(1),
                        liveRunStatus = newest?.status,
                        timeline = timeline,
                    )
                }
                if (newest != null && !RunStatus.isTerminal(newest.status)) {
                    if (runChanged || streamJob?.isActive != true) startStreaming(newest.id)
                } else {
                    streamJob?.cancel()
                    _uiState.update { it.copy(isStreaming = false, showReconnect = false) }
                }
            } catch (ex: CursorApiException) {
                reportLoadFailure(ex.message)
            } catch (ex: IOException) {
                reportLoadFailure("Check your connection")
            }
        }
    }

    private fun reportLoadFailure(message: String) {
        _uiState.update { state ->
            if (state.agent == null) {
                state.copy(isLoading = false, loadError = message)
            } else {
                state.copy(isLoading = false, transientMessage = message)
            }
        }
    }

    // ---- Streaming ----

    private fun startStreaming(runId: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            _uiState.update { it.copy(isStreaming = true, showReconnect = false) }
            var attempt = 0
            while (isActive) {
                var sawDone = false
                runStreamClient.streamRun(agentId, runId, lastEventId).collect { event ->
                    when (event) {
                        is RunStreamEvent.ConnectionClosed -> Unit
                        is RunStreamEvent.Done -> {
                            sawDone = true
                            attempt = 0
                        }
                        else -> attempt = 0 // healthy connection — reset backoff
                    }
                    handleStreamEvent(event)
                }
                // Flow completed: either the server said `done`, the run reached a
                // terminal state, or the connection dropped mid-run.
                val runStillActive = !RunStatus.isTerminal(_uiState.value.liveRunStatus)
                if (sawDone || !runStillActive) break
                attempt++
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    _uiState.update { it.copy(isStreaming = false, showReconnect = true) }
                    return@launch
                }
                delay(minOf(INITIAL_BACKOFF_MS shl (attempt - 1), MAX_BACKOFF_MS))
            }
            _uiState.update { it.copy(isStreaming = false) }
            syncNewestRun(runId)
        }
    }

    private fun handleStreamEvent(event: RunStreamEvent) {
        event.eventId?.let { lastEventId = it }
        when (event) {
            is RunStreamEvent.Assistant -> appendText(TextKind.ASSISTANT, event.text)

            is RunStreamEvent.Thinking -> appendText(TextKind.THINKING, event.text)

            is RunStreamEvent.ToolCall -> {
                lastTextKind = null
                _uiState.update { state ->
                    val timeline = state.timeline.toMutableList()
                    val index = event.callId?.let { callId ->
                        timeline.indexOfLast { it is TimelineItem.Tool && it.callId == callId }
                    } ?: -1
                    if (index >= 0) {
                        val existing = timeline[index] as TimelineItem.Tool
                        timeline[index] = existing.copy(
                            name = event.name ?: existing.name,
                            status = event.status ?: existing.status,
                        )
                    } else {
                        timeline += TimelineItem.Tool(event.callId, event.name, event.status)
                    }
                    state.copy(timeline = timeline)
                }
            }

            is RunStreamEvent.Status -> {
                val status = event.status ?: return
                _uiState.update { state ->
                    state.copy(
                        liveRunStatus = status,
                        newestRun = state.newestRun?.copy(status = status),
                    )
                }
            }

            is RunStreamEvent.Result -> {
                lastTextKind = null
                _uiState.update { state ->
                    state.copy(
                        timeline = state.timeline +
                            TimelineItem.ResultCard(event.status, event.text, event.durationMs, event.git),
                        liveRunStatus = event.status ?: state.liveRunStatus,
                        newestRun = state.newestRun?.let { run ->
                            run.copy(
                                status = event.status ?: run.status,
                                result = event.text ?: run.result,
                                durationMs = event.durationMs ?: run.durationMs,
                                git = event.git ?: run.git,
                            )
                        },
                    )
                }
                refreshAgentOnly() // pick up git/PR state on the agent
            }

            is RunStreamEvent.StreamError ->
                _uiState.update { it.copy(transientMessage = event.message) }

            is RunStreamEvent.Heartbeat,
            is RunStreamEvent.Done,
            is RunStreamEvent.Unknown,
            is RunStreamEvent.ConnectionClosed,
            -> Unit
        }
    }

    /**
     * Appends streamed text. Consecutive events of the same kind are treated as
     * deltas of one message and merged into the last timeline item.
     */
    private fun appendText(kind: TextKind, text: String) {
        val merge = lastTextKind == kind
        lastTextKind = kind
        _uiState.update { state ->
            val timeline = state.timeline.toMutableList()
            val last = timeline.lastOrNull()
            when {
                merge && kind == TextKind.ASSISTANT && last is TimelineItem.AssistantText ->
                    timeline[timeline.lastIndex] = last.copy(text = last.text + text)
                merge && kind == TextKind.THINKING && last is TimelineItem.Thinking ->
                    timeline[timeline.lastIndex] = last.copy(text = last.text + text)
                kind == TextKind.ASSISTANT -> timeline += TimelineItem.AssistantText(text)
                else -> timeline += TimelineItem.Thinking(text)
            }
            state.copy(timeline = timeline)
        }
    }

    private fun refreshAgentOnly() {
        viewModelScope.launch {
            try {
                val agent = apiClient.getAgent(agentId)
                _uiState.update { it.copy(agent = agent) }
            } catch (_: CursorApiException) {
                // Best-effort refresh — the result card already carries git info.
            } catch (_: IOException) {
            }
        }
    }

    /** After the stream ends, fetch the final run snapshot (duration, result, git). */
    private suspend fun syncNewestRun(runId: String) {
        try {
            val run = apiClient.getRun(agentId, runId)
            _uiState.update { state ->
                if (state.newestRun?.id == runId) {
                    state.copy(newestRun = run, liveRunStatus = run.status)
                } else {
                    state
                }
            }
        } catch (_: CursorApiException) {
        } catch (_: IOException) {
        }
    }
}
