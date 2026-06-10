# Agent Companion (Android)

Native Kotlin + Jetpack Compose app to monitor and steer Cursor Cloud Agents.
See PLAN.md for vision, decisions, and milestones.

## Build & run

```bash
./gradlew :app:assembleDebug          # build APK
./gradlew :app:installDebug           # install on connected device/emulator
~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_36 &   # boot emulator
```

- Daemon JVM: `gradle/gradle-daemon-jvm.properties` pins the daemon to JDK 21
  (auto-detected or auto-downloaded via the foojay resolver in settings.gradle.kts).
  Don't add `org.gradle.java.home` — the criteria file supersedes it and keeps
  CLI and IDE on the same daemon.
- Toolchain: Gradle 8.13 / AGP 8.13.2. Version pins live in
  `gradle/libs.versions.toml`. Don't bump majors casually.

## Architecture

- `AppContainer` (manual DI, no Hilt) on `CompanionApp` exposes:
  `apiKeyStore`, `repoCache`, `apiClient`, `runStreamClient`.
- `data/api/` — Cursor Cloud Agents API v1 adapter. DTOs in `CursorModels.kt`
  match `docs/cloud-agents-openapi.yaml` (the source of truth — re-fetch from
  https://cursor.com/docs-static/cloud-agents-openapi.yaml when the API changes).
  Status enums are raw strings on purpose (beta API; don't crash on new values).
- `data/api/RunStreamClient.kt` — SSE flow for live run events; `sseClient` uses a
  90s read timeout (heartbeats are guaranteed, so silence means a dead socket).
  Reconnect by re-collecting with `Last-Event-ID`; on 400/410 protocol errors the
  ViewModel clears the stored event id and reconnects fresh.
- `data/storage/` — Keystore-encrypted API key (`ApiKeyStore`), repo cache
  (`RepoCache`, mandatory — the repositories endpoint allows 1 req/min).
- `ui/<feature>/` — one package per screen: Composable + ViewModel.
  ViewModels get dependencies from `AppContainer` via a ViewModelProvider.Factory.

## Conventions

- Errors from the API surface as `CursorApiException` with the server's
  machine-readable `code` (e.g. `agent_busy`, `plan_required`) — switch on codes,
  not message text.
- 409 `agent_busy` on follow-ups is normal (one active run per agent) — queue or
  surface retry UX, don't treat as failure.
- String resources: per-feature files (`res/values/strings_<feature>.xml`) to
  avoid merge conflicts.
