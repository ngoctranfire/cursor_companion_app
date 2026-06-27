# Agent Companion

A native Android companion app for **Cursor Cloud Agents**. Launch AI coding
agents on your repos, watch them work in real time, steer them with follow-ups,
and get notified when they finish — all from your phone.

> **Thesis:** the future of coding lives on mobile. The agents do the work; the
> phone is the steering wheel. Today this is a *companion* — monitor and steer
> agents you start from anywhere. The longer-term goal is to start and manage
> whole projects from the app: create a repo, let an agent scaffold and build
> it, review the diff, and ship a preview — without touching a laptop. See the
> [Roadmap](#roadmap) and [`PLAN.md`](PLAN.md).

> _Unofficial / learning project. Not affiliated with or endorsed by Cursor
> (Anysphere). "Cursor" is a trademark of its owner; the app is deliberately
> named "Agent Companion." It talks to Cursor's public Cloud Agents API and
> requires your own paid Cursor plan + API key._

---

## Contents

- [What it does](#what-it-does)
- [How it works](#how-it-works)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [Roadmap](#roadmap)
- [Documentation](#documentation)
- [Working with AI agents on this repo](#working-with-ai-agents-on-this-repo)

---

## What it does

Agent Companion is a thin, focused mobile client for the **Cursor Cloud Agents
API v1**. The agents themselves run in Cursor's cloud; the app gives you a phone
UI to drive them:

- **Onboard with an API key** — paste your `crsr_...` key once; it's validated
  against the API and stored encrypted on-device.
- **Browse your agents** — a paginated list of past and active agents, with
  archive and sign-out.
- **Launch an agent** — pick a repository, optionally pick a model, type *or
  dictate* a prompt (system voice input), and toggle options like auto-create-PR
  and plan mode.
- **Watch runs live** — a streaming timeline of the agent's assistant messages,
  thinking, and tool calls (file reads/edits, shell commands, searches),
  delivered over Server-Sent Events with automatic resume/reconnect.
- **Steer the run** — send follow-up instructions or cancel an in-progress run.
- **Get notified** — background polling raises a notification when a run reaches
  a terminal state (finished / error), with a deep link back to the agent and a
  one-tap "Open PR" action.

Everything funnels through Cursor's API; there are no on-device overlays,
accessibility services, or local process launching.

## How it works

```
┌─────────────────────────┐         REST (OkHttp)          ┌──────────────────────┐
│   Agent Companion        │  ───────────────────────────▶  │  Cursor Cloud Agents │
│   (Android, Compose)     │   launch / list / follow-up    │      API  (v1)       │
│                          │   / cancel / repositories      │  api.cursor.com      │
│  ┌───────────────────┐   │                                │                      │
│  │ ViewModels (UI)   │   │  ◀───────────────────────────  │  agents run remotely │
│  └───────────────────┘   │   SSE: live run event stream   │  (read/edit/run code)│
│  ┌───────────────────┐   │                                └──────────────────────┘
│  │ CursorApiClient   │   │
│  │ RunStreamClient   │   │   WorkManager poll (~15 min) ──▶ terminal-state checks
│  │ DataStore + Keystore  │                                  └─▶ local notifications
│  └───────────────────┘   │
└─────────────────────────┘
```

- **REST** (`CursorApiClient`, OkHttp) handles auth, repositories, and agent/run
  CRUD against `https://api.cursor.com`.
- **SSE** (`RunStreamClient`, `okhttp-sse`) streams live run events while a run
  view is open, with `Last-Event-ID` resume and exponential backoff on
  reconnect.
- **Persistence** is DataStore Preferences only (no SQLite/Room). The API key is
  encrypted with an AES-256/GCM key held in the Android Keystore.
- **Background notifications** come from a `WorkManager` worker that polls latest
  run statuses on a schedule and notifies on transitions to a terminal state —
  no foreground service, no push backend (yet; see [Roadmap](#roadmap)).

The API contract is documented in [`docs/cloud-agents-openapi.yaml`](docs/cloud-agents-openapi.yaml),
which is treated as the source of truth for the client DTOs.

## Tech stack

| Area            | Choice |
|-----------------|--------|
| Platform        | Native Android (single `:app` module) |
| Language        | Kotlin 2.4.0 |
| UI              | Jetpack Compose (Material 3), Navigation-Compose |
| Min / target SDK| `minSdk 28`, `target`/`compileSdk 36` |
| JVM target      | 11 (Gradle daemon runs on JDK 21) |
| Build           | Gradle 9.1.0 (wrapper) + Android Gradle Plugin 9.0.1 |
| Networking      | OkHttp 4.12.0 + `okhttp-sse` + logging interceptor |
| Serialization   | kotlinx.serialization (JSON) |
| Async           | Kotlin Coroutines + Flow |
| Storage         | DataStore Preferences + Android Keystore (encrypted key) |
| Background work | WorkManager |
| DI              | Metro (compile-time DI, Kotlin compiler plugin) — root `AppGraph` on the `Application` class |

Versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Project structure

```
cursor_companion/
├── app/                       # the only Gradle module
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/vibecode/companion/
│       │   ├── CompanionApp.kt        # Application + Metro graph root + WorkManager config
│       │   ├── MainActivity.kt        # single Activity hosting Compose
│       │   ├── di/                    # Metro graph: AppGraph + AccountGraph/SessionGraph extensions, scopes, VM factory
│       │   ├── data/
│       │   │   ├── api/               # CursorApiClient, RunStreamClient, DTOs
│       │   │   └── storage/           # ApiKeyStore, RepoCache, PromptStore, DataStore
│       │   ├── ui/                    # one package per screen (Composable + ViewModel)
│       │   │   ├── onboarding/        #   paste & validate API key
│       │   │   ├── agents/            #   agent list / paginate / archive / sign-out
│       │   │   ├── launch/            #   configure & launch an agent (+ voice input)
│       │   │   ├── detail/            #   live run timeline, follow-ups, cancel
│       │   │   ├── common/ theme/     #   status UI, theming
│       │   │   └── AppNavHost.kt      #   navigation graph
│       │   ├── notifications/         # channel + terminal-run notifications
│       │   └── work/                  # WorkManager poll worker + scheduler
│       └── res/                       # Compose-first; strings.xml, themes, icons
├── docs/cloud-agents-openapi.yaml     # Cursor Cloud Agents API spec (source of truth)
├── gradle/libs.versions.toml          # version catalog
├── AGENTS.md                          # guide for AI agents / contributors (read this)
├── CLAUDE.md                          # pointer to AGENTS.md
├── PLAN.md                            # product vision, decisions, milestones
└── README.md
```

## Getting started

### Prerequisites

- **JDK 21** — the Gradle daemon is pinned to Java 21 via
  `gradle/gradle-daemon-jvm.properties` (auto-detected/auto-provisioned).
- **Android SDK** with API level **36** (compile/target SDK 36). Android Studio
  is the easiest way to get the SDK + an emulator.
- A device or emulator running **Android 9 (API 28) or newer**.
- A **Cursor account on a paid plan with usage-based billing enabled** and a
  Cloud Agents **API key** (see [Configuration](#configuration)).

### Clone and open

```bash
git clone <this-repo-url> cursor_companion
cd cursor_companion
```

Open the folder in Android Studio (it will sync Gradle), or build from the CLI.
The repo uses the Gradle wrapper, so you don't need a system Gradle install.

### Build & run

```bash
# Build a debug APK
./gradlew :app:assembleDebug

# Install on a connected device / running emulator
./gradlew :app:installDebug

# (optional) boot an emulator first, e.g.:
~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_36 &
```

Useful extras:

```bash
./gradlew :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:lintDebug           # Android lint
./gradlew :app:assembleRelease     # release APK (see signing below)
```

Build outputs land under `app/build/outputs/` (e.g.
`apk/debug/app-debug.apk`).

### Release signing (optional)

Release builds are signed only when a `keystore.properties` file exists at the
repo root; otherwise the `release` build type produces an unsigned APK. The
properties file and any `*.jks` keystore are git-ignored — keep them out of
version control. Create `keystore.properties` with:

```properties
storeFile=companion-release.jks
storePassword=********
keyAlias=companion
keyPassword=********
```

## Configuration

The app needs a **Cursor Cloud Agents API key**:

1. In Cursor, go to the dashboard → **API Keys** and create a key (it starts
   with `crsr_`). This requires a paid plan with usage-based billing.
2. Launch the app and paste the key on the onboarding screen. It's validated via
   `GET /v1/me`.
3. The key is stored **encrypted at rest** (AES-256/GCM via the Android Keystore)
   inside DataStore. Signing out wipes all account-scoped local data.

No other configuration is required to run the app. `local.properties` (your
Android SDK path) is created automatically by Android Studio and is git-ignored.

## Roadmap

Current status: **MVP** (`versionName 0.1.0`). The phone-direct companion flow —
onboarding → agent list → launch → live SSE run view → follow-ups → local
notifications → PR deep link — is implemented. Later milestones are aspirational;
see [`PLAN.md`](PLAN.md) for the full plan, decisions, and risks.

1. **MVP (now)** — monitor and steer Cloud Agents directly from the phone.
2. **Push backend** — a thin Next.js/Vercel service to receive Cursor webhooks
   and deliver FCM push, replacing local polling.
3. **Review on phone** — in-app diff review and approve/merge of the agent's PR.
4. **Start-from-phone** — create a repo from a template, kick off a cloud agent
   on it, deploy a preview, and get the URL on your phone — the "build and manage
   a whole codebase from the app" vision.

## Documentation

- [`PLAN.md`](PLAN.md) — product vision, dated decisions, milestones, and risks.
- [`AGENTS.md`](AGENTS.md) — how to work in this codebase (build commands,
  architecture, conventions, gotchas). Read this before making changes.
- [`docs/cloud-agents-openapi.yaml`](docs/cloud-agents-openapi.yaml) — the Cursor
  Cloud Agents API v1 spec the client is built against.

## Working with AI agents on this repo

This repo is built with AI coding agents, so it ships an agent guide. The
canonical instructions live in [`AGENTS.md`](AGENTS.md); tool-specific files just
point at it:

- **`AGENTS.md`** — single source of truth for build/run commands, architecture,
  and conventions. This is the standard filename Cursor and Codex auto-discover.
- **`CLAUDE.md`** — read automatically by Claude Code; it just points to `AGENTS.md`
  so there's one place to maintain.
