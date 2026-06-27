package com.vibecode.companion.di

/**
 * Type-safe identifiers handed to the scoped graph extensions via their factories.
 *
 * Each is a `@JvmInline value class` — zero runtime overhead (compiles down to the wrapped
 * `String`), but distinct types on the Metro graph. That stops a future real binding from
 * accidentally satisfying an `accountId` request with an `agentId` (or any other bare
 * `String`-shaped binding). See [AccountGraph] / [SessionGraph].
 */

/** Identifies a signed-in account; supplied to the [AccountGraph] extension factory. */
@JvmInline
value class AccountId(val value: String)

/** Identifies a single agent run / session; supplied to the [SessionGraph] extension factory. */
@JvmInline
value class AgentId(val value: String)
