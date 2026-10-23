# AGENTS.md

Entry point for AI agents working on this repository. Read this file first, then [docs/STATUS.md](docs/STATUS.md).

## Project in one paragraph

**FamilyHub** (working name, see Q-001) is a self-hosted, open-source (AGPL-3.0-or-later) family organizer.
Modules: shared tasks and recurring chores, shopping lists, family calendar (events, birthdays of relatives),
location of family members on a map (phones; geofences), family budget (envelope-style income allocation,
category deficits and loans, real debts), and tags that cut across all modules. Access is controlled by
admin-defined groups and permissions; there is no hardcoded "child" role.
Server: Kotlin/Ktor + PostgreSQL, deployed with Docker Compose on a VPS. Client: Kotlin Multiplatform +
Compose Multiplatform, Android first; web, iOS, desktop later. No ads, no telemetry, no paid tiers,
no dependency on Google services.

## Roles

- **Customer** (human, project owner): defines goals and priorities, answers product questions, accepts milestones.
  Does not write or review code. Speaks Russian; address them in Russian, plainly, without jargon.
- **Agents** (you): design, implement, test, document. Make technical decisions yourselves and record them as ADRs.
  Escalate product decisions to the customer (see [workflow](docs/process/workflow.md#escalation)).

## Read order

1. [docs/STATUS.md](docs/STATUS.md) — current state, active milestone, next steps, blockers.
2. [docs/product/roadmap.md](docs/product/roadmap.md) — milestones and scope.
3. [docs/adr/README.md](docs/adr/README.md) — decision index. Accepted ADRs are binding.
4. [docs/process/workflow.md](docs/process/workflow.md) — how work is done, Definition of Done.
5. The feature spec for the area you touch: [docs/product/features/](docs/product/features/).
   For any client UI also [docs/design/visual-style.md](docs/design/visual-style.md) (binding look, ADR-0016).
6. [docs/domain/glossary.md](docs/domain/glossary.md) — use these terms in code, docs and UI.
7. If you were given a plan from [docs/plans/](docs/plans/README.md): follow it exactly; it lists what else to read.

Full documentation map: [docs/README.md](docs/README.md).

## Hard rules

1. **Accepted ADRs are binding.** To change a decision, write a new ADR that supersedes the old one.
2. **Product decisions belong to the customer.** If a spec is silent, add a question to
   [open-questions.md](docs/product/open-questions.md), choose a reversible default, and mark it `ASSUMPTION` in the spec.
3. **No proprietary dependencies** in client or server: no Google Play Services, Firebase, Google Maps,
   analytics or crash-reporting SDKs ([ADR-0006](docs/adr/0006-no-proprietary-dependencies.md)).
4. **No telemetry.** The app talks only to the family's own server and to services the server admin configures
   (map tiles, push distributor).
5. **Privacy by design.** Location and finance data are sensitive. Every feature spec states who can see and change what.
6. **Docs change with code.** A change is not done until STATUS, specs and ADRs reflect it ([DoD](docs/process/workflow.md#definition-of-done)).
7. **Language:** code, comments, commits, technical docs — English. Customer communication — Russian.
   UI strings are localized (en, ru) and never hardcoded.
8. **Commits:** until the MVP, commit to `main` only after the customer approves the change
   ([workflow](docs/process/workflow.md#git-until-mvp)). After the MVP a release flow replaces this.
9. **Publishing actions need explicit customer approval:** creating or publishing the repo, releases, store submissions,
   anything that costs money or creates external accounts.

## Repository map

| Path | Content | State |
| --- | --- | --- |
| `AGENTS.md`, `CLAUDE.md` | Agent entry point | exists |
| `README.md`, `README.ru.md` | Human-facing overview (en, ru) | exists |
| `docs/` | Technical documentation, see [docs/README.md](docs/README.md) | exists |
| `docs/user/` | User manuals (en, ru) | placeholder |
| `server/` | Ktor backend; test harness in `src/testFixtures` (shared with `integration-tests/`) | identity, administration, sync (M0-02…M0-05) |
| `client/` | KMP client: shared code, Android app, later web/iOS/desktop | client core (M0-06): local DB, API client, sync engine, repositories; UI skeleton |
| `contract/` | KMP module shared by server and client: DTOs, routes, sync protocol, shared domain logic | errors, protocol, instance info (M0-02) |
| `deploy/` | Docker Compose, Caddy, example configs, scripts | dev database only (`deploy/dev/`); production in M0-10 |
| `integration-tests/` | Multi-device sync tests: server + several clients in one JVM | sync invariants and scenarios (M0-06) |
| `tools/loadsim/` | Load simulation CLI | planned (M0-11) |

Layout is provisional until M0 scaffolding; see [architecture overview](docs/architecture/overview.md).

## Commands

Run from the repo root. Gradle uses JDK 21 via `~/.gradle/gradle.properties`; setup and troubleshooting:
[dev-setup](docs/process/dev-setup.md). Shell commands below assume
`export ANDROID_HOME=$HOME/Library/Android/sdk`.

| Task | Command |
| --- | --- |
| Format (run before `check`) | `./gradlew spotlessApply` |
| Lint + all tests | `./gradlew check` (server tests need Docker running: Testcontainers PostgreSQL) |
| Server tests only | `./gradlew :server:test` |
| Multi-device sync tests | `./gradlew :integration-tests:test` (Docker running; real server + several client cores) |
| Client core tests | `./gradlew :client:shared:jvmTest` |
| Full verification (as CI) | `./gradlew spotlessApply check :client:android:assembleDebug` |
| Start dev database | `docker compose -f deploy/dev/docker-compose.yml up -d --wait` (port 5432; if taken: `FAMILYHUB_DEV_DB_PORT=5434 …` and run the server with `FAMILYHUB_DB_URL=jdbc:postgresql://localhost:5434/familyhub`) |
| Run server (port 8080) | `./gradlew :server:run`, then `curl localhost:8080/health/ready` and `curl localhost:8080/api/v1/instance` |
| Stop dev database | `docker compose -f deploy/dev/docker-compose.yml down` (add `-v` to delete its data) |
| Build debug APK | `./gradlew :client:android:assembleDebug` → `client/android/build/outputs/apk/debug/android-debug.apk` |
| Start emulator (headless) | `$ANDROID_HOME/emulator/emulator -avd familyhub -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &` |
| Wait for boot | `$ANDROID_HOME/platform-tools/adb wait-for-device`; poll `adb shell getprop sys.boot_completed` until `1` |
| Install and launch app | `./gradlew :client:android:installDebug`; `adb shell am start -n app.familyhub.android.debug/app.familyhub.android.MainActivity` |
| Screenshot | `adb exec-out screencap -p > <scratchpad>/screen.png` |
| Stop emulator | `adb emu kill` |
