package com.vibecode.companion.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibecode.companion.data.api.CloudAgent
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.CursorApiException
import com.vibecode.companion.data.storage.ApiKeyStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class AgentListUiState(
    val items: List<CloudAgent> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val nextCursor: String? = null,
    /** Transient message (archive failures etc.) — consume with [AgentListViewModel.snackbarShown]. */
    val snackbarMessage: String? = null,
)

class AgentListViewModel(
    private val apiClient: CursorApiClient,
    private val apiKeyStore: ApiKeyStore,
) : ViewModel() {

    private companion object {
        const val PAGE_SIZE = 50
    }

    private val _uiState = MutableStateFlow(AgentListUiState(isLoading = true))
    val uiState: StateFlow<AgentListUiState> = _uiState.asStateFlow()

    private var firstPageJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        loadFirstPage(fullScreen = true)
    }

    /** Silent reload of page one — used by the toolbar action, pull-to-refresh, and on resume. */
    fun refresh() {
        val state = _uiState.value
        if (state.isRefreshing || firstPageJob?.isActive == true) return
        loadFirstPage(fullScreen = false)
    }

    /** Full-screen reload — used by the error state's Retry button. */
    fun retry() {
        val state = _uiState.value
        if (state.isRefreshing || firstPageJob?.isActive == true) return
        loadFirstPage(fullScreen = true)
    }

    fun loadMore() {
        val state = _uiState.value
        val cursor = state.nextCursor ?: return
        if (state.isLoadingMore || state.isLoading || state.isRefreshing) return
        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            try {
                val page = apiClient.listAgents(limit = PAGE_SIZE, cursor = cursor)
                _uiState.update { current ->
                    current.copy(
                        items = (current.items + page.items).distinctBy { it.id },
                        nextCursor = page.nextCursor,
                        isLoadingMore = false,
                    )
                }
            } catch (e: CursorApiException) {
                _uiState.update { it.copy(isLoadingMore = false, snackbarMessage = describeError(e)) }
            } catch (e: IOException) {
                _uiState.update { it.copy(isLoadingMore = false, snackbarMessage = describeError(e)) }
            }
        }
    }

    fun archive(agentId: String) {
        viewModelScope.launch {
            try {
                apiClient.archiveAgent(agentId)
                _uiState.update { current ->
                    current.copy(
                        items = current.items.filterNot { it.id == agentId },
                        snackbarMessage = "Agent archived",
                    )
                }
            } catch (e: CursorApiException) {
                _uiState.update { it.copy(snackbarMessage = "Couldn't archive: ${describeError(e)}") }
            } catch (e: IOException) {
                _uiState.update { it.copy(snackbarMessage = "Couldn't archive: check your connection") }
            }
        }
    }

    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            apiKeyStore.clear()
            onSignedOut()
        }
    }

    fun snackbarShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun loadFirstPage(fullScreen: Boolean) {
        loadMoreJob?.cancel()
        firstPageJob?.cancel()
        firstPageJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = fullScreen,
                    isRefreshing = !fullScreen,
                    isLoadingMore = false,
                    error = if (fullScreen) null else it.error,
                )
            }
            try {
                val page = apiClient.listAgents(limit = PAGE_SIZE)
                _uiState.update {
                    it.copy(
                        items = page.items,
                        nextCursor = page.nextCursor,
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                    )
                }
            } catch (e: CursorApiException) {
                onFirstPageError(describeError(e))
            } catch (e: IOException) {
                onFirstPageError(describeError(e))
            }
        }
    }

    private fun onFirstPageError(message: String) {
        _uiState.update { current ->
            if (current.items.isEmpty()) {
                current.copy(isLoading = false, isRefreshing = false, error = message)
            } else {
                // Keep showing the stale list; surface the failure transiently.
                current.copy(isLoading = false, isRefreshing = false, snackbarMessage = message)
            }
        }
    }

    private fun describeError(e: Exception): String = when (e) {
        is CursorApiException -> when (e.code) {
            "rate_limit_exceeded" -> "Rate limited — wait a moment and try again"
            "plan_required" -> "Cloud agents require a paid Cursor plan with usage billing"
            "no_api_key" -> "No API key configured — sign out and add one"
            else -> when {
                e.isAuthError -> "API key was rejected — sign out and re-enter it"
                else -> e.message
            }
        }
        is IOException -> "Check your connection"
        else -> e.message ?: "Something went wrong"
    }
}
