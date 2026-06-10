package com.vibecode.companion

import android.content.Context
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.RunStreamClient
import com.vibecode.companion.data.storage.ApiKeyStore
import com.vibecode.companion.data.storage.RepoCache

/**
 * Manual dependency container (no Hilt — keeps the build simple).
 * Feature code obtains it via `(context.applicationContext as CompanionApp).container`.
 */
class AppContainer(appContext: Context) {

    val apiKeyStore = ApiKeyStore(appContext)

    val repoCache = RepoCache(appContext)

    val apiClient = CursorApiClient(apiKeyProvider = { apiKeyStore.get() })

    val runStreamClient = RunStreamClient(
        sseClient = apiClient.sseClient,
        apiKeyProvider = { apiKeyStore.get() },
    )
}
