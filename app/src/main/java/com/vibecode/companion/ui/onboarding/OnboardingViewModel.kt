package com.vibecode.companion.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibecode.companion.data.api.ApiKeyInfo
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.CursorApiException
import com.vibecode.companion.data.storage.ApiKeyStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** Immutable UI state for the API-key onboarding screen. */
data class OnboardingUiState(
    val key: String = "",
    val isValidating: Boolean = false,
    val error: String? = null,
    /** Non-null once the key validated and was persisted — the screen navigates onward. */
    val connectedInfo: ApiKeyInfo? = null,
) {
    val canConnect: Boolean get() = key.isNotBlank() && !isValidating && connectedInfo == null
}

/**
 * State holder for onboarding: validates the entered Cursor API key against the API and, on
 * success, persists it via [ApiKeyStore] so the rest of the app is authenticated.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class OnboardingViewModel(
    private val apiClient: CursorApiClient,
    private val apiKeyStore: ApiKeyStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Two-way binding for the key field; clears any prior error as the user edits. */
    fun onKeyChanged(value: String) {
        _uiState.value = _uiState.value.copy(key = value, error = null)
    }

    /** Validates the entered key and, if accepted, persists it and marks onboarding complete. */
    fun connect() {
        val current = _uiState.value
        val key = current.key.trim()
        if (key.isEmpty() || current.isValidating || current.connectedInfo != null) return

        _uiState.value = current.copy(isValidating = true, error = null)
        viewModelScope.launch {
            try {
                val info = apiClient.validateKey(key)
                apiKeyStore.save(key)
                _uiState.value = _uiState.value.copy(isValidating = false, connectedInfo = info)
            } catch (ex: CursorApiException) {
                _uiState.value = _uiState.value.copy(isValidating = false, error = messageFor(ex))
            } catch (_: IOException) {
                _uiState.value = _uiState.value.copy(
                    isValidating = false,
                    error = "Couldn't reach Cursor — check your connection and try again.",
                )
            }
        }
    }

    /** Maps a key-validation failure to a user-facing message (auth vs plan vs rate-limit). */
    private fun messageFor(ex: CursorApiException): String = when {
        ex.isAuthError -> "That key didn't work — check it and try again."
        ex.isPlanError || ex.code == "plan_required" ->
            "Cloud Agents require a paid Cursor plan with usage-based billing enabled. " +
                "Enable it at cursor.com/dashboard, then try again."
        ex.code == "rate_limit_exceeded" -> "Too many attempts — wait a moment and try again."
        else -> ex.message
    }
}
