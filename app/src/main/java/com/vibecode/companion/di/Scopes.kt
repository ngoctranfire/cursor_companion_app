package com.vibecode.companion.di

import dev.zacsweers.metro.Qualifier

/**
 * DI scope markers and qualifiers.
 *
 * `AppScope` is Metro's built-in process-singleton marker (`dev.zacsweers.metro.AppScope`)
 * — we don't redefine it. The two markers below lock in the future
 * **app → account → session** shape now (see [AccountGraph] / [SessionGraph]),
 * even though only `AppScope` is populated today.
 *
 * Scope markers are plain marker classes (mirroring Metro's `AppScope`), used as the
 * `KClass` argument to `@SingleIn(...)` / `@GraphExtension(...)` — they are NOT
 * `@Scope`-annotated annotations (that meta-annotation is for *minting* a new scope
 * annotation like `@Singleton`, which we don't need with the `@SingleIn` convention).
 */

/** Per signed-in account. Account-bound data (key, repo cache, prompts) moves here when per-account lands. */
abstract class AccountScope private constructor()

/** Per agent / session — holds the runtime `agentId` and session-lifetime state. */
abstract class SessionScope private constructor()

/**
 * Qualifies the application [android.content.Context] on the graph so it can never be
 * confused with any other `Context` (e.g. a Worker's context, which is supplied via
 * assisted injection and never enters the graph).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppContext

/**
 * Qualifies the process-lifetime [kotlinx.coroutines.CoroutineScope] (see `AppBindings`) used for
 * fire-and-forget work that must outlive the UI component that started it — e.g. persisting a
 * just-launched agent's bookkeeping after the launch screen is popped and its `viewModelScope`
 * is cancelled. A reusable primitive for the persistence foundation; qualified so it can never be
 * confused with a `viewModelScope` or any other ad-hoc scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppCoroutineScope
