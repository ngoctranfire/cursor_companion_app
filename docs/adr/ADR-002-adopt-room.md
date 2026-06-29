# ADR-002 — Adopt Room (SQLite) for relational local state

- **Status:** Accepted (CUR-9, June 2026)
- **Supersedes:** the "No database — DataStore Preferences only, don't add
  Room/SQLite" rule previously stated in `AGENTS.md` and `README.md`.
- **Related:** ADR-001 — Adopt Metro (Linear, *Dependency Injection* project).

## Context

All on-device persistence was **Jetpack DataStore Preferences**, in a single
process-wide store (`companion_prefs`): the AES-256/GCM-encrypted Cursor API token
(`ApiKeyStore`), the repo-URL cache (`RepoCache`), prompt history (`PromptStore`),
and the background poller's run-status baselines. A prior rule explicitly banned
Room/SQLite — DataStore was "enough."

Two new needs broke that assumption:

1. **Per-run launch mode (`plan` / `agent`).** The Cloud Agents API never returns a
   run's mode in any response, and the launch-time choice was in-memory only and
   discarded after create. CUR-8's "Build" action needs to know an agent's *latest*
   mode to gate correctly — i.e. a per-agent, time-ordered, queryable history.
2. **User preference profiles.** We want to restore a returning user's launch
   defaults (repo, model, auto-create-PR, mode) and, later, support **multiple named
   profiles**.

Both are **relational / queryable** state: "the latest mode for this agent",
"profiles ordered by id". Modeling these in DataStore means hand-rolled JSON blobs
in single keys (as `PromptStore` already does, bounded to 200 by manual LRU) with
no indexing, no query ordering, and growing serialization code. That is exactly
what a database is for.

## Decision

**Adopt Room (SQLite) as a second persistence backend, alongside DataStore**, split
by the *shape* of the data:

- **DataStore Preferences** keeps the encrypted token and simple key/value caches
  (`ApiKeyStore`, `RepoCache`, `PromptStore`, poll baselines). The encrypted token
  is **not** migrated — it stays in DataStore.
- **Room** holds queryable/relational state in `data/storage/db/`:
  `CompanionDatabase` (version 1) with two entities/DAOs, wrapped by thin
  `*Store` classes that mirror the existing store convention.

### Schema (version 1, `exportSchema = false`)

`run_modes` — one row per run:

| column             | type     | notes                                            |
|--------------------|----------|--------------------------------------------------|
| `runId`            | TEXT PK  | one mode per run                                 |
| `agentId`          | TEXT     | indexed with `recordedAtEpochMs`                 |
| `mode`             | TEXT     | raw API string (`plan`/`agent`); not an enum     |
| `recordedAtEpochMs`| INTEGER  | orders an agent's history → "latest mode"         |

`preference_profiles` — one row per profile (a single seeded "Default" today):

| column           | type             | notes                                     |
|------------------|------------------|-------------------------------------------|
| `id`             | INTEGER PK (auto)| `1` = the seeded default profile          |
| `name`           | TEXT             | for future multi-profile UI               |
| `defaultRepoUrl` | TEXT?            | nullable — no repo selected               |
| `defaultModelId` | TEXT?            | nullable — server's default model         |
| `autoCreatePr`   | INTEGER (bool)   | mirrors `LaunchUiState.autoCreatePr`      |
| `defaultMode`    | TEXT             | raw API mode string                       |

`id`/`name` exist now so multiple named profiles can be added later **without a
migration**.

### Build / tooling

- Add **KSP** (`com.google.devtools.ksp` `2.3.9`, pinned to the Kotlin 2.4.0 line;
  ≥2.3.6 is the floor AGP 9 built-in Kotlin requires) **only** for Room's
  `room-compiler`. **Metro stays a pure `kotlinc` compiler plugin — no KSP for
  Metro.** The old "never add KSP" rule is now clarified as *Metro-specific*.
- `room-runtime` + `room-ktx` on the main classpath; `room-compiler` via `ksp(...)`.
- `version = 1`, `exportSchema = false` — no migrations yet. The first schema change
  must bump the version, enable `exportSchema` with a `room.schemaLocation`, and add
  migration + migration tests.

### DI & lifecycle

- The database and stores are **plain, DI-framework-agnostic** data-layer classes;
  Metro wiring lives in `di/AppBindings.kt` (`@Provides @SingleIn(AppScope::class)`),
  mirroring the existing DataStore-backed stores.
- **Scope: `AppScope` now**, with the DB **wiped on sign-out** by extending
  `AccountStore.clearAccountData()` (`CompanionDatabase.clearAllTables()` alongside
  the existing DataStore wipe). The default profile re-seeds lazily on next launch.
  This matches today's "wipe-all on sign-out" behavior and is designed to move to
  `AccountScope` later (the stub `AccountGraph`/`SessionGraph` already anticipate it).
- The background `AgentPollWorker` does **not** need the database (it only polls run
  statuses), so it was left untouched. If a worker ever needs DB access, route the
  DAO through the assisted-injection path (`CompanionWorkerFactory`), never via
  `applicationContext.companionDataStore`.

## Consequences

**Positive**

- A durable, queryable home for per-run mode (unblocks CUR-8) and preference
  profiles, with real indexing/ordering instead of hand-rolled JSON blobs.
- A foundation for the broader "build & ship from mobile" direction, which needs
  richer local state.
- Compile-time safety preserved: Metro still validates the graph (the new singletons
  are `@Provides`'d and exposed on `AppGraph`); Room generates and verifies its DAOs
  at build time via KSP.

**Negative / costs**

- A second persistence mechanism and a new annotation processor (KSP) in the build —
  more moving parts and a small build-time cost.
- Schema-migration discipline now applies: once shipped, schema changes need
  versioned migrations (not needed yet at version 1).

## Alternatives considered

- **Stay on DataStore (Proto or Preferences).** Rejected: relational queries
  ("latest mode per agent", "profiles ordered by id") degrade into manual JSON +
  in-memory filtering, exactly the smell `PromptStore`'s bounded blob already shows.
- **SQLDelight.** Viable, but Room is the platform-standard, integrates cleanly with
  KSP + coroutines, and is already within our SDK/JVM range — lower-friction for an
  Android-only single module.
- **Encrypted-token migration to Room (e.g. SQLCipher).** Out of scope and
  unnecessary; the Keystore + DataStore approach is sound, so the token stays put.
