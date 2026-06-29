package com.vibecode.companion.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibecode.companion.data.api.AgentMode
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
import com.vibecode.companion.data.storage.PromptStore
import com.vibecode.companion.data.storage.RunModeStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
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
    /** What the user asked this run to do (recalled from local PromptStore). */
    data class UserPrompt(val text: String) : TimelineItem
    /** A block of assistant-authored text (coalesced from consecutive deltas). */
    data class AssistantText(val text: String) : TimelineItem
    /** A block of the model's reasoning/thinking (coalesced from consecutive deltas). */
    data class Thinking(val text: String) : TimelineItem
    /** A tool invocation, updated in place as its status and result stream in. */
    data class Tool(
        val callId: String?,
        val name: String?,
        val status: String?,
        /** One-line summary: the path read, the pattern grepped, the command run… */
        val detail: String? = null,
        val argsPretty: String? = null,
        val resultPretty: String? = null,
    ) : TimelineItem
    /** The run's terminal summary card — final status, text, duration, and git/PR info. */
    data class ResultCard(
        val status: String?,
        val text: String?,
        val durationMs: Long?,
        val git: RunGit?,
    ) : TimelineItem
}

/** Immutable UI state for the agent detail screen. */
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
    /** Earlier run whose replayed history is displayed instead of the newest run's. */
    val viewedPastRun: Run? = null,
    /** True while a finished run's event history is replaying from the server. */
    val isReplaying: Boolean = false,
    val followUpText: String = "",
    val isSending: Boolean = false,
    val isCancelling: Boolean = false,
    /**
     * The agent's latest recorded launch mode (`plan` / `agent`, see [AgentMode]), or `null`
     * when unknown — e.g. the agent was launched outside this app or before mode was persisted.
     * Read from [RunModeStore]; this is the signal CUR-8's "Build" action will gate on.
     */
    val latestMode: String? = null,
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
@AssistedInject
class AgentDetailViewModel(
    private val apiClient: CursorApiClient,
    private val runStreamClient: RunStreamClient,
    private val promptStore: PromptStore,
    private val runModeStore: RunModeStore,
    @Assisted private val agentId: String,
) : ViewModel() {

    /**
     * Manual assisted factory: the runtime [agentId] is supplied at the call site
     * (`assistedMetroViewModel`), not from the graph. Contributed as the *factory* into the
     * MetroX manual-assisted multibinding — never `@ContributesIntoMap` the assisted VM itself.
     */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        /** Builds the ViewModel for the given [agentId], passed in at the call site. */
        fun create(agentId: String): AgentDetailViewModel
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }

    private val _uiState = MutableStateFlow(AgentDetailUiState())
    val uiState: StateFlow<AgentDetailUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var loadJob: Job? = null
    private var lastEventId: String? = null

    /** Kind of the previous text-producing SSE event, for delta coalescing. */
    private enum class TextKind { ASSISTANT, THINKING }

    private var lastTextKind: TextKind? = null

    /**
     * Non-null while a finished run's history is replaying. Replays must not
     * mutate live-run state: NEWEST suppresses status/lastEventId updates (the
     * replayed events are historical), PAST additionally protects newestRun.
     */
    private enum class ReplayMode { NEWEST, PAST }

    private var replayMode: ReplayMode? = null

    /** What [onScreenPaused] interrupted, so [onScreenResumed] can restore it. */
    private enum class PausedWork { STREAM, REPLAY_NEWEST, REPLAY_PAST }

    private var pausedWork: PausedWork? = null

    init {
        load(initial = true)
    }

    /**
     * Stops SSE work while the screen isn't visible — an open socket plus the
     * reconnect/backoff loop shouldn't burn battery from the background.
     */
    fun onScreenPaused() {
        if (streamJob?.isActive != true) return
        pausedWork = when (replayMode) {
            ReplayMode.PAST -> PausedWork.REPLAY_PAST
            ReplayMode.NEWEST -> PausedWork.REPLAY_NEWEST
            null -> PausedWork.STREAM
        }
        streamJob?.cancel()
        _uiState.update { it.copy(isStreaming = false, isReplaying = false) }
    }

    /**
     * Restores whatever [onScreenPaused] interrupted: a live stream resumes from
     * the last seen event id (the gap replays server-side); an interrupted
     * history replay restarts from scratch (it rebuilds the timeline anyway).
     * No-op on first composition or when nothing was paused.
     */
    fun onScreenResumed() {
        val resumed = pausedWork ?: return
        pausedWork = null
        val state = _uiState.value
        when (resumed) {
            PausedWork.STREAM -> {
                val newest = state.newestRun ?: return
                if (!RunStatus.isTerminal(state.liveRunStatus)) startStreaming(newest.id)
            }
            PausedWork.REPLAY_NEWEST -> state.newestRun?.let { replayRun(it, past = false) }
            PausedWork.REPLAY_PAST -> state.viewedPastRun?.let { replayRun(it, past = true) }
        }
    }

    /** Retries the full-screen load after a fatal load error. */
    fun retryLoad() = load(initial = true)

    /** Lightweight refresh from the topbar — re-fetches agent + runs. */
    fun refresh() {
        if (_uiState.value.isLoading) return
        load(initial = false)
    }

    /** Two-way binding for the follow-up text field. */
    fun onFollowUpTextChange(text: String) {
        _uiState.update { it.copy(followUpText = text) }
    }

    /** Clears the one-shot snackbar message after the UI has shown it. */
    fun consumeMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    /** Re-attaches the SSE stream after reconnect attempts were exhausted. */
    fun reconnect() {
        val run = _uiState.value.newestRun ?: return
        startStreaming(run.id)
    }

    /** Replays the step-by-step history of an earlier run in place of the latest timeline. */
    fun viewPastRun(run: Run) {
        val state = _uiState.value
        if (state.isReplaying && state.viewedPastRun?.id == run.id) return
        replayRun(run, past = true)
    }

    /** Returns from a past-run view to the latest run (live stream or replayed history). */
    fun viewLatest() {
        val newest = _uiState.value.newestRun ?: return
        if (_uiState.value.viewedPastRun == null) return
        if (RunStatus.isTerminal(newest.status)) {
            replayRun(newest, past = false)
        } else {
            startStreaming(newest.id)
        }
    }

    /** Creates a new run from the typed follow-up, then streams it as the newest run. */
    fun sendFollowUp() {
        val text = _uiState.value.followUpText.trim()
        if (text.isEmpty() || _uiState.value.isSending) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            try {
                // The run this follow-up supersedes — its persisted mode (if any) is what the new
                // run inherits, since the request sends no mode and the server continues in the
                // current one. Captured before the state swap below.
                val priorNewest = _uiState.value.newestRun
                val run = apiClient.createRun(agentId, CreateRunRequest(prompt = PromptBody(text))).run
                // A stale in-flight load must not overwrite the just-created run.
                loadJob?.cancel()
                promptStore.save(run.id, text)
                // Carry the mode forward ONLY when the prior newest run's mode is actually known.
                // For legacy or externally-created runs the mode is genuinely unknown, and stamping
                // a guess (e.g. "agent") would fabricate a mode row, poisoning the latest-mode
                // signal CUR-8's Build action gates on. Persist nothing and leave latestMode null
                // until a mode is genuinely observed.
                val inheritedMode = priorNewest?.let { runModeStore.modeForRun(it.id) }
                if (inheritedMode != null) {
                    runModeStore.recordMode(run.id, agentId, inheritedMode)
                }
                lastEventId = null
                lastTextKind = null
                _uiState.update { state ->
                    state.copy(
                        isSending = false,
                        followUpText = "",
                        pastRuns = listOfNotNull(state.newestRun) + state.pastRuns,
                        newestRun = run,
                        liveRunStatus = run.status,
                        latestMode = inheritedMode,
                        timeline = listOf(TimelineItem.UserPrompt(text)),
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

    /** Requests cancellation of the in-flight newest run, then refreshes to reflect the outcome. */
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
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
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
                val seed = runChanged || _uiState.value.timeline.isEmpty()
                val seedPrompt = if (seed && newest != null) promptStore.get(newest.id) else null
                // Mode is local-only (the API never returns it). Derive it from the NEWEST run's
                // persisted mode — not the agent's last-recorded mode, which can be stale relative
                // to the server's newest run (e.g. a plan-mode agent whose latest run was started
                // elsewhere). A newest run with no recorded mode (legacy/external run, or one
                // created before mode persistence) stays null, so CUR-8 hides "Build" rather than
                // defaulting to "agent" and wrongly enabling it.
                val latestMode = newest?.let { runModeStore.modeForRun(it.id) }
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        loadError = null,
                        agent = agent,
                        newestRun = newest,
                        pastRuns = runs.drop(1),
                        liveRunStatus = newest?.status,
                        latestMode = latestMode,
                        timeline = if (seed) {
                            listOfNotNull(seedPrompt?.let { TimelineItem.UserPrompt(it) })
                        } else {
                            state.timeline
                        },
                        viewedPastRun = if (runChanged) null else state.viewedPastRun,
                    )
                }
                if (newest != null && !RunStatus.isTerminal(newest.status)) {
                    if (runChanged || streamJob?.isActive != true) startStreaming(newest.id)
                } else if (newest != null && seed) {
                    // Finished run: rebuild the step-by-step history by replaying
                    // the server's retained event stream.
                    replayRun(newest, past = false)
                } else if (newest == null) {
                    streamJob?.cancel()
                    _uiState.update { it.copy(isStreaming = false, isReplaying = false, showReconnect = false) }
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

    /**
     * Rebuilds a finished run's timeline by replaying its SSE stream from the
     * beginning (no Last-Event-ID). The server retains run events for a
     * retention window; past it the endpoint returns 410 `stream_expired` and
     * we fall back to the final result card.
     */
    private fun replayRun(run: Run, past: Boolean) {
        streamJob?.cancel()
        replayMode = if (past) ReplayMode.PAST else ReplayMode.NEWEST
        lastTextKind = null
        streamJob = viewModelScope.launch {
            val promptItem = promptStore.get(run.id)?.let { TimelineItem.UserPrompt(it) }
            _uiState.update {
                it.copy(
                    isReplaying = true,
                    isStreaming = false,
                    showReconnect = false,
                    viewedPastRun = if (past) run else null,
                    timeline = listOfNotNull(promptItem),
                )
            }
            var failure: RunStreamEvent.ConnectionClosed? = null
            runStreamClient.streamRun(agentId, run.id).collect { event ->
                if (event is RunStreamEvent.ConnectionClosed) failure = event
                handleStreamEvent(event)
            }
            val closed = failure
            val expired = closed != null && (
                closed.httpCode == 410 || closed.httpCode == 400 ||
                    closed.errorCode == "stream_expired" || closed.errorCode == "invalid_last_event_id"
                )
            val fallbackCard = TimelineItem.ResultCard(run.status, run.result, run.durationMs, run.git)
            _uiState.update { state ->
                when {
                    closed == null -> state.copy(isReplaying = false)
                    expired -> state.copy(
                        isReplaying = false,
                        timeline = listOfNotNull(promptItem, fallbackCard),
                        transientMessage = "Step-by-step history has expired for this run — showing the final result. The full conversation is still on cursor.com.",
                    )
                    else -> state.copy(
                        isReplaying = false,
                        timeline = if (state.timeline.any { it is TimelineItem.ResultCard }) {
                            state.timeline
                        } else {
                            state.timeline + fallbackCard
                        },
                        transientMessage = "Couldn't load the full history — check your connection.",
                    )
                }
            }
        }
    }

    private fun startStreaming(runId: String) {
        streamJob?.cancel()
        replayMode = null
        streamJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isStreaming = true, isReplaying = false, showReconnect = false, viewedPastRun = null)
            }
            var attempt = 0
            while (isActive) {
                var sawDone = false
                runStreamClient.streamRun(agentId, runId, lastEventId).collect { event ->
                    when (event) {
                        is RunStreamEvent.ConnectionClosed -> {
                            // The server rejected our resume point — clear it so the
                            // next attempt restarts fresh (status is replayed on connect).
                            if (event.httpCode == 400 || event.httpCode == 410 ||
                                event.errorCode == "invalid_last_event_id" ||
                                event.errorCode == "stream_expired"
                            ) {
                                lastEventId = null
                            }
                        }
                        is RunStreamEvent.Done -> sawDone = true
                        else -> Unit
                    }
                    // Only real progress resets backoff: the replayed status (and
                    // heartbeats) carry no SSE id, so a connect-then-drop server
                    // must not keep the retry delay pinned at the minimum.
                    if (event !is RunStreamEvent.ConnectionClosed && event.eventId != null) {
                        attempt = 0
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
        // Replayed events are historical — they must not move the live stream's
        // resume cursor.
        if (replayMode == null) {
            event.eventId?.let { lastEventId = it }
        }
        when (event) {
            is RunStreamEvent.Assistant -> appendText(TextKind.ASSISTANT, event.text)

            is RunStreamEvent.Thinking -> appendText(TextKind.THINKING, event.text)

            is RunStreamEvent.ToolCall -> {
                lastTextKind = null
                val detail = toolCallDetail(event.name, event.args)
                val argsPretty = prettyToolJson(event.args)
                val resultPretty = prettyToolJson(event.result)
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
                            detail = detail ?: existing.detail,
                            argsPretty = argsPretty ?: existing.argsPretty,
                            resultPretty = resultPretty ?: existing.resultPretty,
                        )
                    } else {
                        timeline += TimelineItem.Tool(
                            event.callId, event.name, event.status,
                            detail, argsPretty, resultPretty,
                        )
                    }
                    state.copy(timeline = timeline)
                }
            }

            is RunStreamEvent.Status -> {
                // Replays emit historical statuses (CREATING/RUNNING) — ignore them
                // so a finished run doesn't flicker back to "active".
                if (replayMode == null) {
                    val status = event.status ?: return
                    _uiState.update { state ->
                        state.copy(
                            liveRunStatus = status,
                            newestRun = state.newestRun?.copy(status = status),
                        )
                    }
                }
            }

            is RunStreamEvent.Result -> {
                lastTextKind = null
                val touchesNewestRun = replayMode != ReplayMode.PAST
                _uiState.update { state ->
                    state.copy(
                        timeline = state.timeline +
                            TimelineItem.ResultCard(event.status, event.text, event.durationMs, event.git),
                        liveRunStatus = if (touchesNewestRun) event.status ?: state.liveRunStatus else state.liveRunStatus,
                        newestRun = if (!touchesNewestRun) state.newestRun else state.newestRun?.let { run ->
                            run.copy(
                                status = event.status ?: run.status,
                                result = event.text ?: run.result,
                                durationMs = event.durationMs ?: run.durationMs,
                                git = event.git ?: run.git,
                            )
                        },
                    )
                }
                if (replayMode == null) refreshAgentOnly() // pick up git/PR state on the agent
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
