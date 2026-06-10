package com.vibecode.companion

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.RunStreamClient
import com.vibecode.companion.data.storage.ApiKeyStore
import com.vibecode.companion.data.storage.PromptStore
import com.vibecode.companion.data.storage.RepoCache
import com.vibecode.companion.data.storage.companionDataStore

/**
 * Manual dependency container (no Hilt — keeps the build simple).
 * Feature code obtains it via `(context.applicationContext as CompanionApp).container`.
 */
class AppContainer(private val appContext: Context) {

    val apiKeyStore = ApiKeyStore(appContext)

    val repoCache = RepoCache(appContext)

    val promptStore = PromptStore(appContext)

    val apiClient = CursorApiClient(apiKeyProvider = { apiKeyStore.get() })

    val runStreamClient = RunStreamClient(
        sseClient = apiClient.sseClient,
        apiKeyProvider = { apiKeyStore.get() },
    )

    /**
     * Sign-out: everything in the shared DataStore is account-scoped (encrypted
     * API key, repo cache, poll-status baselines), so wipe it wholesale rather
     * than leaking the previous account's data to the next one.
     */
    suspend fun clearAccountData() {
        appContext.companionDataStore.edit { it.clear() }
    }
}
