package com.vibecode.companion.ui.launch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibecode.companion.data.api.AgentMode
import com.vibecode.companion.data.api.CreateAgentRequest
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.CursorApiException
import com.vibecode.companion.data.api.ModelListItem
import com.vibecode.companion.data.api.ModelRef
import com.vibecode.companion.data.api.PromptBody
import com.vibecode.companion.data.api.RepoConfig
import com.vibecode.companion.data.storage.RepoCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

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

class LaunchViewModel(
    private val apiClient: CursorApiClient,
    private val repoCache: RepoCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LaunchUiState())
    val uiState: StateFlow<LaunchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = repoCache.get()
            if (cached == null) {
                // First open with no cache at all — fetch once automatically.
                // A cached-but-empty list is respected (zero repos is a valid state;
                // re-fetching every open would burn the 1/min rate limit).
                refreshRepos()
            } else {
                _uiState.update {
                    it.copy(repoUrls = cached.urls, repoFetchedAtEpochMs = cached.fetchedAtEpochMs)
                }
            }
        }
        loadModels()
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
                    it.copy(reposLoading = false, repoUrls = urls, repoFetchedAtEpochMs = now)
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

    private fun loadModels() {
        viewModelScope.launch {
            try {
                val models = apiClient.listModels().items
                _uiState.update { it.copy(models = models) }
            } catch (_: CursorApiException) {
                // Model picker degrades gracefully to "Default" only.
            } catch (_: IOException) {
                // Same — launching with the default model still works offline-listed.
            }
        }
    }

    fun selectRepo(url: String) = _uiState.update { it.copy(selectedRepo = url) }

    /** Pass null for the "Default" option (no explicit ModelRef sent). */
    fun selectModel(modelId: String?) = _uiState.update { it.copy(selectedModelId = modelId) }

    fun setPrompt(text: String) = _uiState.update { it.copy(prompt = text) }

    /** Appends speech-recognized text to whatever is already typed, separated by a space. */
    fun appendVoiceText(text: String) {
        if (text.isBlank()) return
        _uiState.update {
            val combined = if (it.prompt.isBlank()) text else it.prompt.trimEnd() + " " + text
            it.copy(prompt = combined)
        }
    }

    fun setAutoCreatePr(enabled: Boolean) = _uiState.update { it.copy(autoCreatePr = enabled) }

    fun setPlanMode(enabled: Boolean) = _uiState.update { it.copy(planMode = enabled) }

    fun launch() {
        val state = _uiState.value
        val repo = state.selectedRepo ?: return
        if (state.prompt.isBlank() || state.launching) return

        viewModelScope.launch {
            _uiState.update { it.copy(launching = true, launchError = null) }
            try {
                val response = apiClient.createAgent(
                    CreateAgentRequest(
                        prompt = PromptBody(state.prompt),
                        repos = listOf(RepoConfig(url = repo)),
                        model = state.selectedModelId?.let { ModelRef(it) },
                        autoCreatePR = state.autoCreatePr,
                        mode = if (state.planMode) AgentMode.PLAN else AgentMode.AGENT,
                    ),
                )
                _uiState.update { it.copy(launching = false, launchedAgentId = response.agent.id) }
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

    /** Call after navigating away so re-entering the screen doesn't re-trigger navigation. */
    fun consumeLaunched() = _uiState.update { it.copy(launchedAgentId = null) }

    /** Call after the snackbar has shown the error. */
    fun consumeLaunchError() = _uiState.update { it.copy(launchError = null) }
}
