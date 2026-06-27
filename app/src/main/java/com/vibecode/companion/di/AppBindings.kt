package com.vibecode.companion.di

import android.content.Context
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.RunStreamClient
import com.vibecode.companion.data.storage.ApiKeyStore
import com.vibecode.companion.data.storage.PromptStore
import com.vibecode.companion.data.storage.RepoCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Process-singleton bindings, aggregated into [AppGraph] by `@ContributesTo(AppScope::class)`.
 *
 * The five app singletons live here (instead of constructor `@Inject` on each class) so the
 * `data/` storage + api classes stay DI-framework-agnostic — the adapter boundary the project
 * keeps around the beta Cursor API. `@Provides` functions need **explicit return types**;
 * parameters are resolved from the graph (not by calling sibling providers directly).
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AppBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun apiKeyStore(@AppContext context: Context): ApiKeyStore = ApiKeyStore(context)

    @Provides
    @SingleIn(AppScope::class)
    fun repoCache(@AppContext context: Context): RepoCache = RepoCache(context)

    @Provides
    @SingleIn(AppScope::class)
    fun promptStore(@AppContext context: Context): PromptStore = PromptStore(context)

    @Provides
    @SingleIn(AppScope::class)
    fun apiClient(apiKeyStore: ApiKeyStore): CursorApiClient =
        CursorApiClient(apiKeyProvider = { apiKeyStore.get() })

    @Provides
    @SingleIn(AppScope::class)
    fun runStreamClient(apiClient: CursorApiClient, apiKeyStore: ApiKeyStore): RunStreamClient =
        RunStreamClient(
            sseClient = apiClient.sseClient,
            apiKeyProvider = { apiKeyStore.get() },
        )
}
