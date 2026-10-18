# Forkcast — Claude Code project guide

Forkcast is a native Android calorie and nutrition tracker. The user logs meals by barcode, search, manual entry, or a photo plus a short description; an AI vision model estimates the meal, and the app tracks intake against a goal-based daily budget. Inspiration: MyNetDiary. Owner and primary user: Gabe (Windows dev machine, Samsung Galaxy S25 Ultra).

Read these before writing code, and keep them current:
- `docs/SPEC.md` — features, data model, math, acceptance criteria, build phases. **Source of truth.**
- `docs/AI_PROVIDERS.md` — provider abstraction, prompts, JSON schema, cost notes.
- `docs/DESIGN.md` — visual system, screens, interaction rules.
- `SETUP.md` — setup runbook **you** execute (toolchain, GitHub, phone, signing, keys, Google Cloud). Stop only at its [OWNER] steps. Mark stages done in `docs/PROGRESS.md`.
- `docs/PROGRESS.md` — where the build is right now. Read it first in every session.

The owner wants to hand off everything possible. If a task can be done with the shell, a CLI, or your tools, do it yourself instead of asking him to; ask him only for sign-ins, admin/UAC prompts, things on the phone, and choices that are his to make.

## Settled decisions (don't reopen without the owner)
- Application ID is `app.forkcast`, permanent
- Android only. No iOS, no Kotlin/Compose Multiplatform
- One build installed on the phone (no side-by-side dev app). The owner starts using it daily near the end of the build, then reports problems for fixes
- Owner is on the Claude Pro plan (see "Progress tracking and usage limits" below)

## Stack (use current stable versions; check before pinning)
- Kotlin, Jetpack Compose, Material 3, single-activity, Navigation Compose
- Hilt for DI; Room for the database; DataStore for settings
- WorkManager for background jobs (photo estimation, backups, notifications)
- CameraX + ML Kit Barcode Scanning (bundled model) + ML Kit Text Recognition (nutrition label OCR)
- Ktor client (OkHttp engine) + kotlinx.serialization for all network calls — no vendor AI SDKs
- Coil for images; Vico for charts
- Health Connect (androidx.health.connect:connect-client)
- Google Drive REST v3 via Google Identity `AuthorizationClient` (scope `drive.file`) for backups
- Tink (Android Keystore–backed AEAD) for encrypting API keys at rest
- minSdk 29, target/compile the latest stable SDK; Gradle version catalog (`gradle/libs.versions.toml`)
- Package: `app.forkcast` (single constant; owner may rename)

## Architecture
- Modules: `:app` (UI, navigation), `:core:model`, `:core:data` (Room, repositories, DataStore), `:core:network` (food databases, AI providers), `:core:domain` (math, use cases — pure Kotlin, no Android deps), `:feature:*` as screens grow. Don't over-modularize early; start with `:app` + `:core:*`.
- MVVM with unidirectional data flow: `UiState` data classes exposed as `StateFlow`, events as sealed interfaces.
- Repositories are the only layer that touches Room, network, or Health Connect.
- All nutrition math lives in `:core:domain` as pure functions with unit tests.
- Every AI provider implements one interface (`MealEstimator`, see `docs/AI_PROVIDERS.md`). Adding a provider must never require touching UI or domain code.

## Non-negotiables
- **Offline-first.** Every log, edit, and calculation works with no network. Network features degrade gracefully and queue.
- **Log entries snapshot their nutrients.** Editing a food later must never change past days.
- **Units:** store grams/kcal/mg internally; convert only at the UI edge. User chooses lb/kg, ft-in/cm.
- **Secrets:** API keys never logged, never in exports/backups, never in crash reports, never in Git. Encrypted at rest. Excluded from Android Auto Backup.
- **No analytics, no tracking SDKs, no ads.** The only network calls are to services the user configured (food DBs, their AI provider, their Google Drive).
- **Schema changes ship with a Room migration and a migration test.** Never use destructive fallback.
- **Export format is versioned** (`schemaVersion`). Import must accept every older version.
- Accessibility: content descriptions on every icon button, 48dp touch targets, supports font scaling to 200%, color is never the only signal.

## Phone safety (the owner's real data lives on the phone)
- **Never uninstall the app, clear its data, or run `adb uninstall`/`pm clear` on the owner's phone without asking first.** If an install fails with a signature or version-downgrade error, stop and explain; don't work around it
- Once the owner says he's using the app daily, do development testing on an Android Studio emulator, and install on the phone only when he asks
- Every database change ships with a migration (see non-negotiables), because updates install over existing data

## How we work (the whole build runs from this section; there are no per-phase prompts)

**Every session starts the same way:** read `docs/PROGRESS.md` (create it if missing), then tell the owner in 2–3 lines where the project is and what's next. If he says "continue", pick up exactly there. Re-read the relevant SPEC/DESIGN/AI_PROVIDERS sections before working on a feature; don't rely on memory of an earlier session.

**Order:** SETUP Stage 1 → Phase 0 → 1 → 2 → 3 → 4 → 5, as defined in `docs/SPEC.md` §3. Within a phase, go feature by feature in the order SPEC lists them.

**Setup stages run at these points** (from `SETUP.md`):
| When | Stage |
|---|---|
| Before Phase 0 | Stage 1: toolchain, GitHub repo, phone |
| During Phase 0, once CI workflows exist | Stage 2: signing + GitHub secrets |
| Phase 1, when starting food search (§4.3) | Stage 3 step 1: USDA key |
| End of Phase 1, after tagging `v0.1.0` | Stage 6: Obtainium on the phone |
| Start of Phase 2 | Stage 3 steps 2–5: Gemini/Claude keys, limits; remind the owner about the kitchen-scale test set |
| Start of Phase 3 | Stage 4: Health Connect |
| Start of Phase 4 | Stage 5: Google Cloud + Drive OAuth |
| When the owner says he's using the app daily | Stage 7: emulator, then Phone safety rules apply |

**Phase gates (the only routine stops):**
1. **Start of each phase:** short plan (features in order, new libraries with versions you verified, data-model or export changes, any SETUP stage due) plus any questions, batched in one message. Wait for the owner's OK.
2. **End of each phase:** definition of done met for every feature, SPEC checkboxes ticked, CI green, app installed on the phone (or emulator after Stage 7). Tag a release (`v0.<phase>.0`) from Phase 1 on. Give a short summary (what works now, what to try on the phone, anything waiting on him), then ask: "Ready for Phase N?" Wait for a yes.

**Between gates, keep going without asking**, except when:
- A choice changes the data model, export format, or the `MealEstimator` interface in a way the spec didn't anticipate
- The spec is wrong, contradictory, or outdated (API shapes, library versions, model IDs): propose the fix
- You need the owner for an [OWNER] setup step, a sign-in, or something on the phone
- A requirement is genuinely ambiguous and a wrong guess would be costly to undo
Batch questions when possible. For small judgment calls, decide, note it in `docs/DECISIONS.md`, and keep going.

**Phase-specific checks:**
- Phase 2: before coding providers, verify the current Claude and Gemini structured-output request shapes and model IDs in the official docs and update `docs/AI_PROVIDERS.md`. After Phase 2, offer to run the owner's kitchen-scale test set through each AI profile and report accuracy
- Phase 3: verify current Health Connect permission and privacy-policy requirements first
- End of Phase 5: review the codebase for drift from this file and list it before fixing

**Progress tracking and usage limits (owner is on Claude Pro):**
- After every finished feature, update `docs/PROGRESS.md`: current phase, features done, next step, open questions, setup stages done. Keep it short; it's how the next session resumes
- Commit at every working step so a usage-limit stop never leaves the repo broken. If you're warned about limits, commit what works and update PROGRESS.md before anything else
- At each phase gate, suggest the owner start a fresh session (or `/clear`) before saying yes; the new session resumes from PROGRESS.md

## Working rules
- Commit in small, logical commits with clear messages. Push to the private GitHub repo `forkcast`.
- When a library API or model ID is uncertain, check the official docs instead of guessing. Model IDs are never hardcoded in logic — they come from settings, with defaults in one config file.
- Tag uncertainty in code comments with `// VERIFY:` so the owner can grep for it.
- Keep `docs/SPEC.md` updated when decisions change; add a dated line to `docs/DECISIONS.md` for any non-obvious choice.

## Secrets
- Never print, echo, log, or commit secrets (keystore password, API keys, tokens), and never ask the owner to paste one into the conversation. Generate them inside scripts that write straight to their destination (file outside the repo, `gh secret set`)
- Signing files live in `%USERPROFILE%\Documents\keys\`, never in the repo

## Commands (Windows, PowerShell)
- Build debug: `./gradlew assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Instrumented tests (device connected): `./gradlew connectedDebugAndroidTest`
- Lint: `./gradlew lintDebug`
- Install on phone: `./gradlew installDebug` (USB debugging on, `adb devices` shows the phone)
- Release APK: built by GitHub Actions on a `v*` tag (see `.github/workflows/release.yml`)

## Definition of done (every feature)
1. Unit tests for logic; Compose UI test for the main happy path.
2. `assembleDebug`, `testDebugUnitTest`, `lintDebug` pass.
3. Works in light and dark theme, at 200% font scale, and with no network.
4. SPEC acceptance criteria for that feature are checked off in `docs/SPEC.md`.
