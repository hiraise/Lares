# AGENTS.md

Entry point for AI agents working on this repository. Read this file first, then [docs/STATUS.md](docs/STATUS.md).

## Project in one paragraph

**Lares** (named after the Roman guardian spirits of the home; see Q-001) is a self-hosted, open-source (AGPL-3.0-or-later) family organizer.
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
   Past work is in [docs/history.md](docs/history.md); read it only when you need a detail from it.
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
| `.claude/skills/`, `.claude/agents/` | Project skills and the `emulator-tester` agent (see below) | exists |
| `README.md`, `README.ru.md` | Human-facing overview (en, ru) | exists |
| `docs/` | Technical documentation, see [docs/README.md](docs/README.md) | exists |
| `docs/user/` | User manuals (en, ru) | placeholder |
| `server/` | Ktor backend; test harness in `src/testFixtures` (shared with `integration-tests/`) | identity, administration, sync (M0-02…M0-05) |
| `client/` | KMP client: shared code, Android app, later web/iOS/desktop | client core (M0-06); Android app shell (M0-07): onboarding, main scaffold, settings; family management (M0-08) |
| `contract/` | KMP module shared by server and client: DTOs, routes, sync protocol, shared domain logic | errors, protocol, instance info (M0-02) |
| `deploy/` | Docker Compose, Caddy, ntfy, example configs, backup/restore/smoke scripts | production stack (M0-10); dev database in `deploy/dev/` |
| `integration-tests/` | Multi-device sync tests: server + several clients in one JVM | sync invariants and scenarios (M0-06) |
| `tools/dev/` | Agent dev scripts: short-output build/test, local server, test family, emulator driving | exists |
| `tools/release/` | Release script, release notes, APK signature check ([release process](docs/process/release.md)) | exists |
| `tools/design-assets/` | Generates bundled fonts and icons (Python + fonttools) | exists (M0-07) |
| `tools/loadsim/` | Load simulation CLI (simulated family against a running server) | exists (M0-11) |

Layout is provisional until M0 scaffolding; see [architecture overview](docs/architecture/overview.md).

## Skills

| Skill / agent | Use |
| --- | --- |
| `/plan-run M0-09` | Execute a plan from `docs/plans/` (replaces the long executor prompt) |
| `plan-finish` | End of a plan or work item: DoD, docs, STATUS + history, Russian handoff, commit after approval |
| `build-check` | Build and tests via `tools/dev/verify.sh` with short output |
| `emulator` | Local server, test family, emulator, tap-by-text, small screenshots |
| `emulator-tester` agent | Manual walkthrough in its own context; returns a short report with screenshot paths |

## Working economically

Every step re-reads the whole session, so a long session makes each later step expensive.

- Read sections and line ranges (`grep -n`, `sed -n`), not whole files; do not re-read a file you just edited.
- Build and test through `tools/dev/verify.sh`: narrow tasks while iterating, the full gate once at the end.
- Long commands go to the background (`run_in_background`); never wait with `sleep N; cat …`.
- Manual emulator checks go to the `emulator-tester` agent, never into the main implementation session.
- Navigate the app by `tools/dev/ui.sh dump` (text); take screenshots only for visual checks and the handoff.

## Commands

Run from the repo root. Gradle uses JDK 21 via `~/.gradle/gradle.properties`; setup, raw `adb` commands and
troubleshooting: [dev-setup](docs/process/dev-setup.md).

| Task | Command |
| --- | --- |
| Full verification (as CI): format, lint, all tests, debug APK | `tools/dev/verify.sh` (Docker running: Testcontainers PostgreSQL) |
| One module or test class | `tools/dev/verify.sh :server:test`, `tools/dev/verify.sh :client:shared:jvmTest --tests '*Name*'` |
| Multi-device sync tests | `tools/dev/verify.sh :integration-tests:test` (real server + several client cores) |
| Local server for manual checks (port 8080, database `lares_e2e`) | `tools/dev/server.sh start` / `stop` / `reset` / `log` |
| Test family and API calls | `tools/dev/seed.py family`, `seed.py token`, `seed.py invite`, `seed.py login-code Аня`, `seed.py call GET /me` |
| Emulator: boot, install + launch, clear data, deep link, stop | `tools/dev/emu.sh start` / `install` / `fresh` / `link URL` / `stop` |
| Drive the app by text, screenshot | `tools/dev/ui.sh dump` / `tap TEXT` / `field N` / `type TEXT` / `shot NAME` / `crash` |
| Start dev database only | `docker compose -f deploy/dev/docker-compose.yml up -d --wait` (port taken: `LARES_DEV_DB_PORT=5434 …`) |
| Run server with Gradle (main dev DB) | `./gradlew :server:run`, then `curl localhost:8080/health/ready` |
| Server image (local) | `./gradlew :server:installDist && docker build -f server/Dockerfile -t lares-server:dev .` |
| Production stack smoke test (ports 80/443 free) | `./gradlew :server:installDist && deploy/scripts/smoke.sh` |
| Load test: simulated family, latency, memory, cold start (ports 80/443 free, ~35 min; `--duration 5m` for a short run) | `./gradlew :server:installDist :tools:loadsim:installDist && deploy/scripts/loadtest.sh` → `build/loadtest/<time>/` |
| Backup / restore (on the server host) | `deploy/scripts/backup.sh` / `deploy/scripts/restore.sh <file>` ([manual](docs/user/en/install-server.md)) |
| Release (customer approval only) | `tools/release/release.sh X.Y.Z --push` ([release process](docs/process/release.md)) |
| Build debug APK | `./gradlew :client:android:assembleDebug` → `client/android/build/outputs/apk/debug/android-debug.apk` |
| Regenerate fonts/icons | `tools/design-assets/.venv/bin/python tools/design-assets/build.py` ([README](tools/design-assets/README.md)) |

In the app the local server address is `http://localhost:8080` (adb reverse; the emulator cannot reach `10.0.2.2` on
this Mac). Fresh server setup code from `tools/dev/server.sh`: `DEVSETUP01`.
