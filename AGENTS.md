# Agent Companion — Agent Guide

`AGENTS.md` is the standard filename Cursor and Codex auto-discover; `CLAUDE.md`
just points here. **[README.md](README.md)** covers what the app is, how to
build/run it, and the project layout — this file is only the non-obvious rules
that keep changes from silently breaking things. Don't repeat the README here.

## PR review workflow (CodeRabbit)

Every PR is reviewed by **CodeRabbit** before a human merges. A green build is
**not** enough to merge — run the review loop to completion first:

1. **Open the PR and let CodeRabbit review it.** It runs automatically on a new
   PR; if it hasn't, comment `@coderabbitai review` to trigger it. Read its
   **whole** output, not just the inline **actionable comments**: the
   walkthrough/summary, the collapsed **🧹 Nitpick comments** section, the
   **pre-merge checks** (e.g. docstring coverage), any **tool** findings, and the
   **🤖 Prompt for AI Agents** block tucked inside each inline comment.
2. **Give it time — wait ~15–20 min** for the full pass to land before acting.
   Don't start working off a partial review.
3. **Reply to every CodeRabbit comment — and not only the inline "actionable"
   ones.** Nitpicks, summary-level notes, pre-merge-check warnings, and tool
   findings all count. Each must end with an explicit determination, so a real bug
   can't hide in an unread thread or a collapsed section:
   - If it's valid, **fix it and push.** CodeRabbit is **incremental** — each push
     re-reviews only the new changes, so keep pushing fixes and re-reading its
     feedback.
   - If it's a **non-issue / false positive**, don't just close it — **reply with
     `@coderabbitai` explaining why** (wrong assumption, intentional design,
     already handled elsewhere). It responds and, if convinced, acknowledges and
     resolves; if it pushes back, it may have caught something real — reconsider
     before dismissing.
   - **Nitpicks and non-code notes still get a reply** — fix them, or leave a
     one-line "leaving as-is because …". A **pre-merge-check** warning (docstring
     coverage, etc.) is cleared by fixing the code, or — if the bar is wrong for
     this repo — by adjusting `.coderabbit.yaml`, never by ignoring it.
   - **Each inline comment ships a `🤖 Prompt for AI Agents` block** — a
     machine-readable fix instruction. Treat it as a prompt to **verify, not a
     command to paste**: as the block itself says, check the finding against the
     **current code**, apply only the still-valid fixes, skip the rest with a
     brief reason, and reply with the determination (fixed, or false-positive +
     why). Never apply it blindly.
   Confirm each item is either fixed or has a recorded reason it's safe before
   moving on.
4. **Loop until CodeRabbit is fully clean** — **no actionable comments**, every
   thread (nitpicks included) resolved, and **pre-merge checks green**. Don't
   merge while anything is open or unanswered.
5. **Human merges.** Once CodeRabbit is satisfied and the human has reviewed,
   **the human** merges — agents never merge.

This is in addition to (not a replacement for) independent cross-vendor review of
the diff.

## Build / toolchain

- The Gradle daemon is pinned to **JDK 21** via
  `gradle/gradle-daemon-jvm.properties`. Don't add `org.gradle.java.home` — the
  criteria file supersedes it and is what keeps the CLI and IDE on one daemon.
- Toolchain: **Gradle 9.1.0 / AGP 9.0.1 / Kotlin 2.4.0**. Version pins live in
  `gradle/libs.versions.toml`. Don't bump majors casually.
- We use **AGP 9's built-in Kotlin** — there is **no** standalone
  `org.jetbrains.kotlin.android` plugin, and don't re-add one. Built-in Kotlin
  bundles KGP 2.2.10, so the root `build.gradle.kts` `buildscript` classpath pins
  `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0` to hold the whole compiler
  toolchain (incl. the Compose & serialization compiler plugins) on 2.4.0. Keep
  that pin in lockstep with the `kotlin` version — and note **Metro is
  version-locked to it** (see DI section).
- That legacy `buildscript {}` block needs its **own `repositories {}`**
  (`pluginManagement` only feeds the `plugins {}` block); don't delete it expecting
  the settings repositories to apply.
- `jvmTarget` is set in app's `kotlin { compilerOptions { } }` block (new AGP DSL),
  not the removed `kotlinOptions {}`.

## Cursor API (`data/api/`)

- It's a **beta v1 API** that just went through a v0→v1 breaking redesign. Keep
  everything that touches it behind the `data/api/` adapter so the churn stays
  isolated.
- DTOs in `CursorModels.kt` mirror `docs/cloud-agents-openapi.yaml`. When the API
  changes, re-fetch the spec from
  `https://cursor.com/docs-static/cloud-agents-openapi.yaml` — don't hand-edit
  DTOs to guess at the shape.
- Status enums are **raw strings on purpose**. Don't convert them to sealed
  enums: the beta API can return new/unknown values and must not crash on them.
- API errors surface as `CursorApiException` carrying a machine-readable `code`
  (`agent_busy`, `plan_required`, `rate_limit_exceeded`, …). **Switch on `code`,
  never on message text.**
- `409 agent_busy` on a follow-up is **normal** (one active run per agent) —
  queue or surface retry UX; don't treat it as a hard failure.

## Live runs (SSE)

- `RunStreamClient` uses a **90s read timeout** because heartbeats are guaranteed
  — prolonged silence means a dead socket, not a slow run.
- Reconnect by re-collecting with `Last-Event-ID`. On `400`/`410` protocol
  errors, clear the stored event id and reconnect fresh — don't keep replaying a
  rejected id.

## Storage (`data/storage/`, DataStore only)

- `RepoCache` is **mandatory, not an optimization**: `GET /v1/repositories` is
  rate-limited to ~1 req/min/user. Don't bypass it.
- `PromptStore` exists because the API **never echoes the prompt back** — persist
  it locally per run or it's lost.
- The API key is encrypted at rest (AES-256/GCM via the Android Keystore). Don't
  log it or move it into plaintext preferences.

## Dependency injection (Metro)

We're standardizing DI on **[Metro](https://zacsweers.github.io/metro/1.2.1/)**
(pinned **1.2.1**), migrating off the manual `AppContainer`. Full playbook:
**[docs/dependency-injection.md](docs/dependency-injection.md)** — read it before
touching DI. The non-obvious, mistake-preventing rules:

- Metro is a **Kotlin compiler plugin, not KSP/kapt** — never add KSP for it, and
  **never bump Kotlin without a compatible Metro release** (the plugin is
  version-locked to `kotlinc`; check the compat table first).
- **Constructor injection + factories only — never member injection.**
- **Compile to validate:** some graph errors appear only at build time (IR
  backend), not in the IDE — run `./gradlew :app:compileDebugKotlin` after any
  wiring change. A green IDE is not enough.
- Prefer aggregation (`@ContributesTo` / `@ContributesBinding` /
  `@ContributesIntoMap`) over `@Module(includes = …)`; `@Provides` needs explicit
  return types; scope strictly (`AppScope` / `AccountScope` / `SessionScope`).
- Keep **tracing on** (`metro { traceDestination… }`); heavier graph-report tasks
  stay CLI-gated.
- **You (an LLM) likely don't know Metro from memory** — it's post-2025. Read the
  version-matched Metro docs in-context and trust the compiler, not recall.

## Intentional constraints (don't "fix" these)

- **DI is standardizing on Metro** (migrating off the manual `AppContainer`).
  Don't introduce Hilt, Koin, Dagger, or fresh hand-rolled wiring — see the
  **Dependency injection (Metro)** section above.
- **No database.** Persistence is DataStore Preferences by design — don't add
  Room/SQLite.
- The user-facing name avoids "Cursor" (trademark); the working name is "Agent
  Companion."

## Secrets

- `local.properties`, `keystore.properties`, and `*.jks` are git-ignored — never
  commit them.
