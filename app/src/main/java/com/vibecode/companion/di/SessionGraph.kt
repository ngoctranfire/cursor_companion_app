package com.vibecode.companion.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provides

/**
 * **Stub** session-scoped graph extension (per agent run / session).
 *
 * Its `Factory` is `@ContributesTo(AccountScope::class)`, so it's generated and validated as
 * a child of [AccountGraph]. `@GraphExtension(SessionScope::class)` implies
 * `@SingleIn(SessionScope::class)`.
 *
 * The runtime `agentId` is supplied to the session via the factory (`@Provides`) rather than
 * the graph — the same reason `AgentDetailViewModel` uses assisted injection. Session-lifetime
 * state will live here once sessions are wired through Metro.
 */
@GraphExtension(SessionScope::class)
interface SessionGraph {
    val agentId: String

    @ContributesTo(AccountScope::class)
    @GraphExtension.Factory
    interface Factory {
        fun createSessionGraph(@Provides agentId: String): SessionGraph
    }
}
