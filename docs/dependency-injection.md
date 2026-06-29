# Dependency Injection — Metro

> **Status: adopted.** Decision recorded in **ADR-001 — Adopt Metro** (Linear, *Dependency Injection* project); delivered by **CUR-1** (Kotlin upgrade) → **CUR-2** (conversion). The manual `AppContainer` is **gone** — Metro is the only DI in the app. This document is the standard for all DI work.

This is the in-repo, docs-grounded playbook for using [Metro](https://zacsweers.github.io/metro/) correctly in this codebase. Always cross-check against the **version-matched** Metro docs (we pin **1.2.1**): <https://zacsweers.github.io/metro/1.2.1/>.

## Why Metro

Compile-time graph safety (Dagger-class, no reflection at runtime) + Kotlin-first ergonomics + **graph extensions** that model `app → account → session` scopes + **aggregation** (`@Contributes*`) for multi-module growth + first-class **tracing/diagnostics**. Builds stay fast because Metro runs *inside* `kotlinc` (FIR/IR) — no `kapt`, no KSP round-trip. Full rationale and the alternatives we rejected (Hilt, Koin, kotlin-inject, Dagger, manual) are in ADR-001.

## Versions — pin them, bump them together

- **Metro `1.2.1`**. Metro is a **Kotlin compiler plugin, NOT KSP/kapt** — **never add KSP for Metro**. (KSP *is* in the build, but **only** for Room's `room-compiler` annotation processor, pinned to the Kotlin line — see **ADR-002** and `gradle/libs.versions.toml`. Don't route Metro or anything else through it.)
- Requires **Kotlin** at the version CUR-1 lands (target ~`2.4.0`; confirm against the compat table) and **Gradle 9+**.
- The compiler-plugin API is **version-locked to `kotlinc`**: not every Metro works with every Kotlin. **Never bump Kotlin without a Metro release that supports it.** Check the table first: <https://zacsweers.github.io/metro/1.2.1/compatibility/>.

## Golden rules (where almost all Metro mistakes come from)

1. **Constructor injection + factories only. Never member injection** (`MemberInjector`). This is the #1 "don't" from real Metro migrations (Cash App, Vinted).
2. **Compile to validate.** Some binding-graph errors surface only in the **IR backend at build time, not live in the IDE**. After any graph/wiring change, run `./gradlew :app:compileDebugKotlin` — a green IDE is *not* sufficient.
3. **Aggregate by contribution.** Prefer `@ContributesTo` / `@ContributesBinding` / `@ContributesIntoMap` / `@ContributesIntoSet` over `@Module(includes = …)`.
4. **`@Provides` functions need explicit return types.**
5. **Scope strictly.** Process singletons in `AppScope`; account-bound data in `AccountScope`; session state in `SessionScope`. Don't widen a scope for convenience.
6. **Don't annotate assisted ViewModels with `@ContributesIntoMap`** — contribute their *factory* instead.
7. **As an AI agent:** you almost certainly don't know Metro from training data (its corpus is post-2025). **Read the version-matched Metro docs in-context and lean on the compiler, not recall.** Metro ships its own `AGENTS.md` precisely for this workflow.

## Scopes in this app

| Scope | Lifetime | Holds |
|---|---|---|
| `AppScope` (`@SingleIn(AppScope::class)`) | process | `ApiKeyStore`, `RepoCache`, `PromptStore`, `CompanionDatabase` + its `RunModeStore`/`PreferenceProfileStore`, `CursorApiClient`, `RunStreamClient`, and the `@AppCoroutineScope` `CoroutineScope` for fire-and-forget work that must outlive a ViewModel |
| `AccountScope` | per signed-in account | account-bound data — the stores above (incl. the Room `CompanionDatabase`) move here when per-account scoping lands. Until then they live in `AppScope` but are wiped per-account at sign-out: `AccountStore.clearAccountData()` clears `CompanionDatabase.clearAllTables()` **first**, then the DataStore, so a failed wipe leaves the previous account intact rather than half-cleared |
| `SessionScope` | per agent run / session | the runtime `agentId` + session-lifetime state |

Define the future scopes (and stub their `@GraphExtension` factories) **now**, even unused, so the shape is locked in. **Metro does not own lifecycle cleanup** — teardown means dropping the extension reference and closing/cancelling any resources you provided.

## Root graph

```kotlin
@Scope annotation class AppScope
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppContext

@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph {
  val apiKeyStore: ApiKeyStore
  val repoCache: RepoCache
  val promptStore: PromptStore
  val runModeStore: RunModeStore               // Room-backed (CompanionDatabase)
  val preferenceProfileStore: PreferenceProfileStore  // Room-backed (CompanionDatabase)
  val apiClient: CursorApiClient
  val runStreamClient: RunStreamClient
  val workerFactory: CompanionWorkerFactory

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides @AppContext context: Context): AppGraph
  }
}

@ContributesTo(AppScope::class)
@BindingContainer
object AppBindings {
  @Provides @SingleIn(AppScope::class)
  fun apiKeyStore(@AppContext context: Context): ApiKeyStore = ApiKeyStore(context)

  @Provides @SingleIn(AppScope::class)
  fun apiClient(apiKeyStore: ApiKeyStore): CursorApiClient =
    CursorApiClient(apiKeyProvider = { apiKeyStore.get() })

  // …repoCache, promptStore, runStreamClient similarly. The Room layer follows the
  // same shape: @Provides the CompanionDatabase, then RunModeStore/PreferenceProfileStore
  // from its DAOs — plain data-layer classes, Metro-agnostic (see ADR-002).
}
```

`CompanionApp` builds the root graph with `createGraphFactory<AppGraph.Factory>().create(this)` (there is no `AppContainer` anymore), alongside `AgentNotifications.ensureChannel(this)` and `PollScheduler.ensureScheduled(this)`.

## Graph extensions (per-account / per-session)

```kotlin
@Scope annotation class AccountScope
@Scope annotation class SessionScope

@GraphExtension(AccountScope::class)
interface AccountGraph {
  val sessionFactory: SessionGraph.Factory
  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory { fun createAccount(@Provides accountId: AccountId): AccountGraph }
}

@GraphExtension(SessionScope::class)
interface SessionGraph : ViewModelGraph {
  val agentId: AgentId
  @ContributesTo(AccountScope::class)
  @GraphExtension.Factory
  interface Factory { fun createSession(@Provides agentId: AgentId): SessionGraph }
}
```

## ViewModels (MetroX)

Standard ViewModels — contribute into the ViewModel map:

```kotlin
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class LaunchViewModel(
  private val apiClient: CursorApiClient,
  private val repoCache: RepoCache,
  private val promptStore: PromptStore,
) : ViewModel()
```

`AgentDetailViewModel` takes a runtime `agentId` → **assisted** injection (don't put it in the graph):

```kotlin
@AssistedInject
class AgentDetailViewModel(
  private val apiClient: CursorApiClient,
  private val runStreamClient: RunStreamClient,
  @Assisted private val agentId: String,
) : ViewModel() {
  @AssistedFactory
  @ManualViewModelAssistedFactoryKey
  @ContributesIntoMap(AppScope::class)
  interface Factory : ManualViewModelAssistedFactory { fun create(agentId: String): AgentDetailViewModel }
}
```

At the Compose root provide `LocalMetroViewModelFactory`, then replace `companionViewModel { … }` call sites with `metroViewModel()` / `assistedMetroViewModel<AgentDetailViewModel, AgentDetailViewModel.Factory>(key = agentId) { create(agentId) }`.

## WorkManager (no official Metro guide)

Metro has no first-party Worker integration — wire a custom `WorkerFactory`:

```kotlin
@AssistedInject
class AgentPollWorker(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val apiKeyStore: ApiKeyStore,
  private val apiClient: CursorApiClient,
) : CoroutineWorker(context, params) {
  @AssistedFactory
  interface Factory { fun create(context: Context, params: WorkerParameters): AgentPollWorker }
}
```

`CompanionApp` implements `Configuration.Provider` and returns a `Configuration` built from `graph.workerFactory`. If more workers appear, prefer a `Map<String, ChildWorkerFactory>` multibinding keyed by worker class name over a hard-coded `when`.

## Tracing & diagnostics (enable from day one)

Low-overhead **tracing** stays on in the build:

```kotlin
metro {
  traceDestination.set(layout.buildDirectory.dir("metro/traces"))
}
```

Heavier **graph reports / visualization** stay CLI-gated (they enable `generateMetroGraphMetadata`, `analyzeMetroGraph`, `generateMetroGraphHtml`):

```bash
./gradlew :app:generateMetroGraphHtml -Pmetro.reportsDestination=metro/reports --rerun-tasks
```

See [debugging](https://zacsweers.github.io/metro/1.2.1/debugging/), [graph analysis](https://zacsweers.github.io/metro/1.2.1/graph-analysis/), and [validation & error reporting](https://zacsweers.github.io/metro/1.2.1/validation-and-error-reporting/).

## Greenfield note

We have **no Dagger/Anvil to migrate**, so **skip all interop** (`includeDagger`/`includeAnvil`), dual-build toggles, and codemods the Cash App/Vinted articles describe — those exist only to coexist with a legacy Dagger graph. Use Metro natively.

## Metro 1.2.1 reference

- [Compatibility](https://zacsweers.github.io/metro/1.2.1/compatibility/) · [Installation](https://zacsweers.github.io/metro/1.2.1/installation/) · [Dependency graphs](https://zacsweers.github.io/metro/1.2.1/dependency-graphs/) · [Scopes](https://zacsweers.github.io/metro/1.2.1/scopes/)
- [Injection types](https://zacsweers.github.io/metro/1.2.1/injection-types/) · [Aggregation](https://zacsweers.github.io/metro/1.2.1/aggregation/) · [MetroX ViewModel](https://zacsweers.github.io/metro/1.2.1/metrox-viewmodel/) · [MetroX Compose](https://zacsweers.github.io/metro/1.2.1/metrox-viewmodel-compose/)

*Code snippets are the intended shape, grounded in the Metro 1.2.1 docs; the CUR-2 conversion is the source of truth once merged — reconcile this doc with the real implementation if they diverge.*
