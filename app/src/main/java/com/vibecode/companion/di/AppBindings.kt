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

    /** Encrypted store for the Cursor API key — single instance so writes are seen everywhere. */
    @Provides
    @SingleIn(AppScope::class)
    fun apiKeyStore(@AppContext context: Context): ApiKeyStore = ApiKeyStore(context)

    /** Cache of the user's repository list (the API rate-limits the live fetch to 1/min). */
    @Provides
    @SingleIn(AppScope::class)
    fun repoCache(@AppContext context: Context): RepoCache = RepoCache(context)

    /** Local store of launch prompts — the API never echoes them back, so we keep our own copy. */
    @Provides
    @SingleIn(AppScope::class)
    fun promptStore(@AppContext context: Context): PromptStore = PromptStore(context)

    /**
     * The Cursor REST client. Reads the key lazily through [ApiKeyStore.get] so a sign-in or
     * sign-out is picked up without rebuilding the client.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun apiClient(apiKeyStore: ApiKeyStore): CursorApiClient =
        CursorApiClient(apiKeyProvider = { apiKeyStore.get() })

    /**
     * The SSE client for live run streams. Shares [CursorApiClient]'s configured OkHttp SSE
     * client and the same lazy key provider so auth stays consistent with REST calls.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun runStreamClient(apiClient: CursorApiClient, apiKeyStore: ApiKeyStore): RunStreamClient =
        RunStreamClient(
            sseClient = apiClient.sseClient,
            apiKeyProvider = { apiKeyStore.get() },
        )
}
