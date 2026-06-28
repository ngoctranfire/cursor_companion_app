package com.vibecode.companion.di

import android.content.Context
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.RunStreamClient
import com.vibecode.companion.data.storage.ApiKeyStore
import com.vibecode.companion.data.storage.PreferenceProfileStore
import com.vibecode.companion.data.storage.PromptStore
import com.vibecode.companion.data.storage.RepoCache
import com.vibecode.companion.data.storage.RunModeStore
import com.vibecode.companion.work.CompanionWorkerFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph

/**
 * Root dependency graph (process scope).
 *
 * `@DependencyGraph(AppScope::class)` makes this the aggregation root: every
 * `@ContributesTo` / `@ContributesIntoMap` / `@ContributesBinding(AppScope::class)`
 * contribution (the [AppBindings] container, the ViewModel multibindings, the
 * `MetroViewModelFactory`, the contributed [AccountGraph] factory…) is merged in at
 * compile time. Declaring the scope is also an implicit `@SingleIn(AppScope::class)`,
 * so the graph can host the `@SingleIn(AppScope::class)` singletons.
 *
 * Extending [ViewModelGraph] adds the MetroX ViewModel multibindings + the
 * `metroViewModelFactory` accessor used to install `LocalMetroViewModelFactory`.
 *
 * The accessors below aren't all consumed directly (ViewModels get their deps via the
 * multibindings), but exposing them makes Metro validate each binding resolves at
 * compile time — a cheap smoke test for the whole singleton wiring.
 */
@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph {
    val apiKeyStore: ApiKeyStore
    val repoCache: RepoCache
    val promptStore: PromptStore
    val runModeStore: RunModeStore
    val preferenceProfileStore: PreferenceProfileStore
    val apiClient: CursorApiClient
    val runStreamClient: RunStreamClient
    val workerFactory: CompanionWorkerFactory

    /** Builds the root graph, seeding it with the one runtime input every singleton needs. */
    @DependencyGraph.Factory
    fun interface Factory {
        /** Creates the graph, providing the application [context] under the [AppContext] qualifier. */
        fun create(@Provides @AppContext context: Context): AppGraph
    }
}
