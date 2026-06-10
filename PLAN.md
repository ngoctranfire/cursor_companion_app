# Agent Companion — Plan

A native Android companion app for Cursor's Cloud Agents. Thesis: the future of
coding lives on mobile — agents do the work, the phone is the steering wheel.
Start as a companion (monitor/steer agents), grow into starting whole projects
from the phone (create repo → agent scaffolds → preview deployed).

## Decisions (2026-06-10)

- **Stack**: native Kotlin + Jetpack Compose (user's choice). minSdk 28, target/compileSdk 36.
  AGP 8.9.1 / Kotlin 2.0.21 / Compose BOM 2026.01.01 / Gradle 8.11.1 — pinned to versions
  already cached on this machine.
- **No Hilt**: manual DI via `AppContainer` on the `Application` class.
- **API**: Cursor Cloud Agents API **v1** only (v0 is legacy). Spec snapshot:
  `docs/cloud-agents-openapi.yaml`. All access goes through `CursorApiClient`
  (the API is beta — isolate breaking changes there).
- **Auth**: paste-a-key onboarding (`crsr_...` from cursor.com/dashboard → API keys),
  validated via `GET /v1/me`. Requires a paid Cursor plan + usage billing. Key stored
  AES/GCM-encrypted (Android Keystore) in DataStore — see `ApiKeyStore`.
- **Sequencing**: phone-direct first; thin Vercel backend in milestone 2 (webhook → FCM push).
  Until then, notifications are local: WorkManager polls and notifies on terminal states.
- **Live updates**: SSE (`okhttp-sse`) from `GET /v1/agents/{id}/runs/{runId}/stream`
  in the foreground; resume with `Last-Event-ID`.
- **Repo list**: `GET /v1/repositories` is rate-limited to 1/min/user and slow —
  always read through `RepoCache`.
- **Naming**: avoid "Cursor" in user-facing app name (trademark). Working name: Agent Companion.

## Milestones

1. **MVP (now)**: onboarding → agent list → launch agent (repo picker, model picker,
   voice input, autoCreatePR) → live run view (SSE) + follow-ups → PR deep link to
   GitHub → local notifications on FINISHED/ERROR via WorkManager polling.
2. **Push backend**: thin Next.js on Vercel — Cursor webhook receiver (HMAC verify) → FCM
   data messages; repo-list cache; key vaulting for server-side polling.
3. **Review on phone**: in-app diff review (GitHub API), approve/merge PR.
4. **Start-from-phone**: GitHub device-flow auth → create repo from template →
   kick off cloud agent on fresh repo (optionally with MCP servers configured) →
   Vercel deploy → preview URL on phone.

## Risks

- API is beta (just had a v0→v1 breaking redesign) — keep everything behind the adapter.
- Cursor may ship official native mobile (high Sherlock risk) — this is a sample/learning app.
- v1 webhooks "coming soon" — milestone 2 depends on them (or legacy v0 webhooks).
