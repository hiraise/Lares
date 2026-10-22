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
| `server/` | Ktor backend | planned (M0) |
| `client/` | KMP client: shared code, Android app, later web/iOS/desktop | planned (M0) |
| `contract/` | KMP module shared by server and client: DTOs, routes, sync protocol, shared domain logic | planned (M0) |
| `deploy/` | Docker Compose, Caddy, example configs, scripts | planned (M0) |
| `integration-tests/` | Multi-device sync tests: server + several clients in one JVM | planned (M0-06) |
| `tools/loadsim/` | Load simulation CLI | planned (M0-11) |

Layout is provisional until M0 scaffolding; see [architecture overview](docs/architecture/overview.md).

## Commands

None yet. M0 adds build, test, lint and local-run commands here.
