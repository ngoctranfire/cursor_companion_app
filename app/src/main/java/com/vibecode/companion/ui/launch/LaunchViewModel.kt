package com.vibecode.companion.ui.launch

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibecode.companion.data.api.AgentMode
import com.vibecode.companion.data.api.CreateAgentRequest
import com.vibecode.companion.data.api.CreateAgentResponse
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.CursorApiException
import com.vibecode.companion.data.api.ModelListItem
import com.vibecode.companion.data.api.ModelRef
import com.vibecode.companion.data.api.PromptBody
import com.vibecode.companion.data.api.RepoConfig
import com.vibecode.companion.data.storage.LaunchDefaults
import com.vibecode.companion.data.storage.PreferenceProfileStore
import com.vibecode.companion.data.storage.PromptStore
import com.vibecode.companion.data.storage.RepoCache
import com.vibecode.companion.data.storage.RunModeStore
import com.vibecode.companion.di.AppCoroutineScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Immutable UI state for the launch screen (repo + model pickers, prompt, and launch options). */
data class LaunchUiState(
    // Repos
    val repoUrls: List<String> = emptyList(),
    val repoFetchedAtEpochMs: Long? = null,
    val selectedRepo: String? = null,
    val reposLoading: Boolean = false,
    val repoError: String? = null,
    // Models ("Default" = null id → server picks)
    val models: List<ModelListItem> = emptyList(),
    val selectedModelId: String? = null,
    // Prompt + options
    val prompt: String = "",
    val autoCreatePr: Boolean = true,
    val planMode: Boolean = false,
    // Launch
    val launching: Boolean = false,
    val launchError: String? = null,
    val launchedAgentId: String? = null,
) {
    val canLaunch: Boolean get() = selectedRepo != null && prompt.isNotBlank() && !launching
}

/**
 * State holder for the launch screen: restores the user's saved launch defaults, loads the repo
 * list (cache-first) and model list, tracks the prompt and launch options, and creates the
 * agent. The launched prompt is saved to [PromptStore] (the API never echoes it back); the
 * chosen mode is saved to [RunModeStore] (the API never returns it); and the selections are
 * saved to [PreferenceProfileStore] so the next launch restores them.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class LaunchViewModel(
    private val apiClient: CursorApiClient,
    private val repoCache: RepoCache,
    private val promptStore: PromptStore,
    private val runModeStore: RunModeStore,
    private val preferenceProfileStore: PreferenceProfileStore,
    @AppCoroutineScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LaunchUiState())
    val uiState: StateFlow<LaunchUiState> = _uiState.asStateFlow()

    private companion object {
        const val TAG = "LaunchViewModel"
    }

    init {
        viewModelScope.launch {
            // Restore the user's saved launch defaults first, so a returning user lands on their
            // last repo / model / options AND the model/repo validation below runs against the
            // restored selection. Loading models concurrently (the old shape) let validation race
            // ahead of the restore, see a still-null selection, and let a stale/unsupported id
            // survive. These are local prefs — the API can't supply them.
            val defaults = preferenceProfileStore.launchDefaults()
            _uiState.update {
                it.copy(
                    selectedRepo = defaults.repoUrl,
                    selectedModelId = defaults.modelId,
                    autoCreatePr = defaults.autoCreatePr,
                    planMode = defaults.mode == AgentMode.PLAN,
                )
            }
            val cached = repoCache.get()
            if (cached == null) {
                // First open with no cache at all — fetch once automatically (refreshRepos drops a
                // restored repo the fetched list doesn't offer). A cached-but-empty list is
                // respected (zero repos is a valid state; re-fetching every open would burn the
                // 1/min rate limit).
                refreshRepos()
            } else {
                _uiState.update {
                    it.copy(
                        repoUrls = cached.urls,
                        repoFetchedAtEpochMs = cached.fetchedAtEpochMs,
                        // Drop a restored repo the cached list no longer offers, so canLaunch can't
                        // stay true for an inaccessible repo (the launch would fail server-side).
                        selectedRepo = it.selectedRepo?.takeIf { url -> url in cached.urls },
                    )
                }
            }
            // Load models only after the saved selection is restored, so loadModels() validates
            // the restored model id against the freshly loaded list and drops it if unsupported.
            loadModels()
        }
    }

    /**
     * Re-fetches GET /v1/repositories (rate-limited to 1/min and can take tens of
     * seconds — hence the explicit loading state) and persists through [RepoCache].
     */
    fun refreshRepos() {
        if (_uiState.value.reposLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(reposLoading = true, repoError = null) }
            try {
                val urls = apiClient.listRepositories().items.map { repo -> repo.url }
                val now = System.currentTimeMillis()
                repoCache.save(urls, now)
                _uiState.update {
                    it.copy(
                        reposLoading = false,
                        repoUrls = urls,
                        repoFetchedAtEpochMs = now,
                        // Drop a selected repo the fresh list no longer offers (mirrors the stale
                        // model drop in loadModels) so canLaunch can't stay true for a repo the
                        // user can no longer launch against.
                        selectedRepo = it.selectedRepo?.takeIf { url -> url in urls },
                    )
                }
            } catch (ex: CursorApiException) {
                val message = if (ex.isRateLimited || ex.code == "rate_limit_exceeded") {
                    "Repo list is rate-limited (1/min) — try again shortly"
                } else {
                    ex.message
                }
                _uiState.update { it.copy(reposLoading = false, repoError = message) }
            } catch (_: IOException) {
                _uiState.update { it.copy(reposLoading = false, repoError = "Check your connection") }
            }
        }
    }

    /** Loads the model picker options; failures degrade silently to the "Default" model only. */
    private fun loadModels() {
        viewModelScope.launch {
            try {
                val models = apiClient.listModels().items
                _uiState.update { state ->
                    // Drop a restored model default the server no longer offers, so the picker
                    // and the request agree (it falls back to the "Default" model).
                    val validSelection = state.selectedModelId?.takeIf { id -> models.any { it.id == id } }
                    state.copy(models = models, selectedModelId = validSelection)
                }
            } catch (_: CursorApiException) {
                // Model picker degrades gracefully to "Default" only.
            } catch (_: IOException) {
                // Same — launching with the default model still works offline-listed.
            }
        }
    }

    /** Selects the repo to launch the agent against. */
    fun selectRepo(url: String) = _uiState.update { it.copy(selectedRepo = url) }

    /** Pass null for the "Default" option (no explicit ModelRef sent). */
    fun selectModel(modelId: String?) = _uiState.update { it.copy(selectedModelId = modelId) }

    /** Two-way binding for the prompt text field. */
    fun setPrompt(text: String) = _uiState.update { it.copy(prompt = text) }

    /** Appends speech-recognized text to whatever is already typed, separated by a space. */
    fun appendVoiceText(text: String) {
        if (text.isBlank()) return
        _uiState.update {
            val combined = if (it.prompt.isBlank()) text else it.prompt.trimEnd() + " " + text
            it.copy(prompt = combined)
        }
    }

    /** Toggles whether the agent opens a PR automatically when it finishes. */
    fun setAutoCreatePr(enabled: Boolean) = _uiState.update { it.copy(autoCreatePr = enabled) }

    /** Toggles plan mode (the agent proposes a plan instead of editing directly). */
    fun setPlanMode(enabled: Boolean) = _uiState.update { it.copy(planMode = enabled) }

    /** Creates the agent from the current selection and prompt; sets [LaunchUiState.launchedAgentId] on success. */
    fun launch() {
        val state = _uiState.value
        val repo = state.selectedRepo ?: return
        if (state.prompt.isBlank() || state.launching) return
        val chosenMode = if (state.planMode) AgentMode.PLAN else AgentMode.AGENT

        viewModelScope.launch {
            _uiState.update { it.copy(launching = true, launchError = null) }
            try {
                val response = apiClient.createAgent(
                    CreateAgentRequest(
                        prompt = PromptBody(state.prompt),
                        repos = listOf(RepoConfig(url = repo)),
                        model = state.selectedModelId?.let { ModelRef(it) },
                        autoCreatePR = state.autoCreatePr,
                        mode = chosenMode,
                    ),
                )
                // The remote agent exists now, so navigate to it regardless of whether the
                // on-device bookkeeping below succeeds — a failed local write must never strand
                // the user on the launch screen.
                _uiState.update { it.copy(launching = false, launchedAgentId = response.agent.id) }
                // Persist on the app scope, not viewModelScope: publishing launchedAgentId above
                // pops the launch screen and clears this VM, cancelling viewModelScope and aborting
                // these writes mid-flight. The app scope outlives the screen so the prompt / run
                // mode / launch defaults are durably saved even on a fast navigation away.
                appScope.launch { persistLaunchResult(response, state, chosenMode, repo) }
            } catch (ex: CursorApiException) {
                val message = when (ex.code) {
                    "repository_access" ->
                        "Cursor's GitHub App can't access that repo — install it from the Cursor dashboard"
                    "plan_required" ->
                        "A paid Cursor plan with usage billing is required to launch agents"
                    "rate_limit_exceeded" ->
                        "Rate limited — try again shortly"
                    else -> ex.message
                }
                _uiState.update { it.copy(launching = false, launchError = message) }
            } catch (_: IOException) {
                _uiState.update { it.copy(launching = false, launchError = "Check your connection") }
            }
        }
    }

    /**
     * Best-effort local bookkeeping after a successful create: the launched prompt ([PromptStore],
     * the API never echoes it back), the run's mode ([RunModeStore], the API never returns it),
     * and the user's launch defaults ([PreferenceProfileStore]). Each write is isolated so one
     * failure doesn't skip the others, and all are non-fatal — the agent already exists, so a
     * storage error is logged, not surfaced, and never blocks navigation.
     */
    private suspend fun persistLaunchResult(
        response: CreateAgentResponse,
        state: LaunchUiState,
        chosenMode: String,
        repo: String,
    ) {
        bestEffort("launch prompt") { promptStore.save(response.run.id, state.prompt) }
        bestEffort("run mode") { runModeStore.recordMode(response.run.id, response.agent.id, chosenMode) }
        bestEffort("launch defaults") {
            preferenceProfileStore.saveLaunchDefaults(
                LaunchDefaults(
                    repoUrl = repo,
                    modelId = state.selectedModelId,
                    autoCreatePr = state.autoCreatePr,
                    mode = chosenMode,
                ),
            )
        }
    }

    /** Runs a non-essential persistence [block], logging (never throwing) on failure. */
    private suspend inline fun bestEffort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e // never swallow cancellation — structured concurrency depends on it
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist $what after launch", e)
        }
    }

    /** Call after navigating away so re-entering the screen doesn't re-trigger navigation. */
    fun consumeLaunched() = _uiState.update { it.copy(launchedAgentId = null) }

    /** Call after the snackbar has shown the error. */
    fun consumeLaunchError() = _uiState.update { it.copy(launchError = null) }
}
