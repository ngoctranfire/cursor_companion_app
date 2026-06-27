package com.vibecode.companion.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provides

/**
 * **Stub** account-scoped graph extension — locks in the app → account → session shape now,
 * even though nothing creates one at runtime yet.
 *
 * Because its `Factory` is `@ContributesTo(AppScope::class)`, Metro generates and *validates*
 * this extension when [AppGraph] is generated, so the shape is compile-checked from day one.
 * `@GraphExtension(AccountScope::class)` implies `@SingleIn(AccountScope::class)`.
 *
 * When per-account state lands, account-bound singletons move under this scope; for now it
 * just threads an `accountId` and exposes the child [SessionGraph] factory.
 */
@GraphExtension(AccountScope::class)
interface AccountGraph {
    /** The account this graph is scoped to (see [AccountId]). */
    val accountId: AccountId

    /** Factory for the child [SessionGraph] — opens a per-session scope under this account. */
    val sessionGraphFactory: SessionGraph.Factory

    /**
     * Contributed factory that creates an [AccountGraph] from the parent [AppGraph].
     * `@ContributesTo(AppScope::class)` is what makes Metro generate and compile-validate
     * the extension even though nothing instantiates one yet.
     */
    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    interface Factory {
        /** Opens an account scope for [accountId], provided onto the new graph. */
        fun createAccountGraph(@Provides accountId: AccountId): AccountGraph
    }
}
