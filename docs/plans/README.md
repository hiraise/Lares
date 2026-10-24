# Implementation plans

Detailed, self-contained plans written by a planning session (high effort) and executed by implementation sessions
with a clean context (medium effort). One plan = one session = one approved commit.

## How to execute a plan

Start a new session in the repository root and type `/plan-run M0-09` (or just "делаем M0-09"). The skill
[`plan-run`](../../.claude/skills/plan-run/SKILL.md) holds the executor instructions; without skills, use this prompt:

```text
Ты исполнитель в проекте Lares. Выполни план docs/plans/M0/NN-name.md по правилам скила
.claude/skills/plan-run/SKILL.md. Коммит — только после одобрения заказчика.
```

The session:

1. Reads STATUS.md → the plan → the sections listed in its "Read first" (AGENTS.md is already loaded).
2. Sets the plan status to `in-progress` in this README.
3. Implements the steps, runs the acceptance commands (`tools/dev/verify.sh`); hands manual emulator checks to the
   `emulator-tester` agent.
4. Closes with the [`plan-finish`](../../.claude/skills/plan-finish/SKILL.md) skill: docs, plan status `done` (or
   `blocked` with a reason), STATUS.md and history.md.
5. Presents the Russian handoff summary and waits for the customer's approval; then commits
   (Conventional Commit, plan id in the subject, e.g. `feat(server): foundation (M0-02)`).

## Plan format

Every plan has: front matter (`id`, `status`, `depends_on`, `requirements`), Goal, Read first (name sections, not just
files), Scope / Out of scope,
Decisions (already made — do not revisit), Steps, Tests, Acceptance, Docs to update, Stop and report if, Handoff.

Rules for executors:

- Do not change decisions listed in a plan or in accepted ADRs. If one is wrong, stop and report.
- Use only dependencies from `gradle/libs.versions.toml`. Adding one requires a license check (ADR-0003, ADR-0006)
  and a note in the handoff.
- Keep the build green: `tools/dev/verify.sh` (= `spotlessApply check :client:android:assembleDebug`) must pass
  before the handoff.
- Prefer small, readable code over clever abstractions. Follow [conventions](../process/conventions.md).

## M0 plans

Statuses: `ready` → `in-progress` → `done` | `blocked`.

| Plan | Title | Depends on | Status |
| --- | --- | --- | --- |
| [M0-01](M0/01-dev-env-and-skeleton.md) | Dev environment and Gradle monorepo skeleton | — | done |
| [M0-02](M0/02-server-foundation.md) | Server foundation | M0-01 | done |
| [M0-03](M0/03-identity.md) | Identity: setup, login, sessions, permissions catalogue | M0-02 | done |
| [M0-04](M0/04-household-admin.md) | Household administration: groups, members, invites, login codes | M0-03 | done |
| [M0-05](M0/05-sync-server.md) | Sync server: pull, push, events, persons, tags | M0-04 | done |
| [M0-06](M0/06-client-data-sync.md) | Client data layer and sync engine | M0-05 | done |
| [M0-07](M0/07-android-shell.md) | Android app: onboarding, auth, main scaffold, settings | M0-06 | done |
| [M0-08](M0/08-family-management-ui.md) | Android app: family management screens | M0-07 | done |
| [M0-09](M0/09-notifications.md) | Notifications: reminders, UnifiedPush, background sync | M0-08 | done |
| [M0-10](M0/10-deploy-and-ops.md) | Deploy and operations | M0-05 | done |
| [M0-11](M0/11-load-simulation.md) | Load simulation and resource budget | M0-10 | done |

M0 acceptance (after all plans): full stack via Docker Compose, APK on a phone or emulator, walkthrough of the
foundation scenarios, Russian changelog for the customer. Done by the planning session together with the customer.

Shared references for all M0 plans: [versions](M0/versions.md), [sync protocol](../architecture/sync.md),
[auth design](../architecture/auth.md), [API](../architecture/api.md), [foundation spec](../product/features/foundation.md).

## M1 plans

Milestone [M1 Tasks](../product/roadmap.md#m1-tasks). [Tasks spec](../product/features/tasks.md) approved by the customer on 2026-10-23 (Q-014…Q-017 answered the same day).

| Plan | Title | Depends on | Status |
| --- | --- | --- | --- |
| [M1-01](M1/01-tasks-contract.md) | Tasks contract: data, schedule logic, write rules, validators | M0 | done |
| [M1-02](M1/02-tasks-server-sync.md) | Tasks on the server: tables, sync handlers, permissions, cascade | M1-01 | done |
| [M1-03](M1/03-tasks-server-notifications.md) | Task notifications on the server: assigned, completed, overdue job | M1-02 | done |
| [M1-04](M1/04-tasks-client-data.md) | Tasks in the client core: tables, adapters, views, writer, reminders | M1-02 | done |
| [M1-05](M1/05-tasks-list-and-detail-ui.md) | Tasks UI: the Tasks tab and the task page | M1-04 | done |
| [M1-06](M1/06-task-editor-ui.md) | Task editor: repeat rules, assignees, tags, reminder | M1-05 | done |
| [M1-07](M1/07-tasks-today-tags-notifications.md) | Today block, tag page, notification settings and taps, M1 docs | M1-06, M1-03 | done |

M1 acceptance (after all plans): APK on the family's phones against the real server, the spec scenarios, Russian changelog.

Shared references for all M1 plans: [tasks design](../architecture/tasks.md), [ADR-0017](../adr/0017-task-occurrences-derived-from-completions.md),
[ADR-0018](../adr/0018-tags-embedded-in-entities.md), [tasks spec](../product/features/tasks.md).

## M2 plans

Milestone [M2 Shopping lists](../product/roadmap.md#m2-shopping-lists). [Shopping spec](../product/features/shopping.md)
approved by the customer on 2026-10-24 (Q-018…Q-020 answered the same day).

| Plan | Title | Depends on | Status |
| --- | --- | --- | --- |
| [M2-01](M2/01-shopping-contract.md) | Shopping contract: data, names, quick add, departments, write rules, validators | M1 | done |
| [M2-02](M2/02-shopping-server-sync.md) | Shopping on the server: tables, sync handlers, permissions, cascade | M2-01 | done |
| [M2-03](M2/03-shopping-client-data.md) | Shopping in the client core: tables, adapters, views, repository, writer | M2-02 | done |
| [M2-04](M2/04-shopping-lists-ui.md) | Shopping UI: the Shopping tab and the list page | M2-03 | ready |
| [M2-05](M2/05-shopping-items-suggestions-tags.md) | Item sheet, suggestions, autocomplete and tags | M2-04 | ready |
| [M2-06](M2/06-shopping-today-tags-manual.md) | Today shopping card, tag page section, manual, M2 wrap-up | M2-05 | ready |

M2 acceptance (after all plans): APK on the family's phones, a real shopping trip with the list offline, the spec
scenarios, Russian changelog.

Shared references for all M2 plans: [shopping design](../architecture/shopping.md),
[ADR-0021](../adr/0021-shopping-items-kept-as-list-memory.md), [ADR-0018](../adr/0018-tags-embedded-in-entities.md),
[shopping spec](../product/features/shopping.md).
