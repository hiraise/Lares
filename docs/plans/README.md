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
| [M2-04](M2/04-shopping-lists-ui.md) | Shopping UI: the Shopping tab and the list page | M2-03 | done |
| [M2-05](M2/05-shopping-items-suggestions-tags.md) | Item sheet, suggestions, autocomplete and tags | M2-04 | done |
| [M2-06](M2/06-shopping-today-tags-manual.md) | Today shopping card, tag page section, manual, M2 wrap-up | M2-05 | done |

M2 acceptance (after all plans): APK on the family's phones, a real shopping trip with the list offline, the spec
scenarios, Russian changelog.

Shared references for all M2 plans: [shopping design](../architecture/shopping.md),
[ADR-0021](../adr/0021-shopping-items-kept-as-list-memory.md), [ADR-0018](../adr/0018-tags-embedded-in-entities.md),
[shopping spec](../product/features/shopping.md).

## M3 plans

Milestone [M3 Calendar](../product/roadmap.md#m3-calendar). [Calendar spec](../product/features/calendar.md) approved
by the customer on 2026-10-24 (Q-021…Q-024 answered the same day).

| Plan | Title | Depends on | Status |
| --- | --- | --- | --- |
| [M3-01](M3/01-calendar-contract.md) | Calendar contract: data, derived ids, schedule, birthdays, write rules, validators | M2 | done |
| [M3-02](M3/02-calendar-server-sync.md) | Calendar on the server: tables, sync handlers, permissions, cascade, birthday setting | M3-01 | done |
| [M3-03](M3/03-calendar-server-notifications.md) | Event notifications on the server: added, changed, cancelled | M3-02 | done |
| [M3-04](M3/04-calendar-client-data.md) | Calendar in the client core: tables, adapters, views, repository, writer, reminders | M3-02 | done |
| [M3-05](M3/05-calendar-month-list-event-ui.md) | Calendar UI: the Calendar tab (month, list, filters) and the event page | M3-04 | done |
| [M3-06](M3/06-event-editor-ui.md) | Event editor: times, several days, repeat with end, participants, memorable dates | M3-05 | done |
| [M3-07](M3/07-calendar-week-grid-ui.md) | Calendar week view: the grid by hours | M3-06 | done |
| [M3-08](M3/08-calendar-today-tags-settings-manual.md) | Today «Скоро», tag page, person dates, settings, notification taps, manual, M3 wrap-up | M3-07, M3-03 | done |

M3 acceptance (after all plans): APK on the family's phones, a week of real family events with repeats and birthdays,
the spec scenarios, Russian changelog.

Shared references for all M3 plans: [calendar design](../architecture/calendar.md),
[ADR-0022](../adr/0022-event-series-with-occurrence-rows.md), [ADR-0023](../adr/0023-owner-only-sync-rows.md),
[ADR-0018](../adr/0018-tags-embedded-in-entities.md), [calendar spec](../product/features/calendar.md).

## M4 plans

Milestone [M4 Location](../product/roadmap.md#m4-location). [Location spec](../product/features/location.md) detailed on
2026-10-25 with the customer's answers Q-025…Q-029 and approved by the customer the same day.

| Plan | Title | Depends on | Status |
| --- | --- | --- | --- |
| [M4-01](M4/01-location-contract.md) | Location contract: data, DTOs, visibility, geofence and stops, write rules, validators | M3 | done |
| [M4-02](M4/02-places-rules-server.md) | Places, alerts and visibility rules on the server | M4-01 | done |
| [M4-03](M4/03-positions-live-server.md) | Positions, sharing state and the live channel on the server | M4-02 | done |
| [M4-04](M4/04-geofences-notifications-addresses-server.md) | Geofences, location notifications and addresses on the server | M4-03 | done |
| [M4-05](M4/05-load-test-with-location.md) | Load simulation with location uploads (ADR-0015) | M4-04 | done |
| [M4-06](M4/06-location-client-core.md) | Location in the client core: places, live map data, sharing, queue, tracking policy | M4-04 | ready |
| [M4-07](M4/07-android-sharing-service.md) | Android sharing: foreground service, permissions, setup, pause, Settings › Геопозиция | M4-06 | ready |
| [M4-08](M4/08-map-screen.md) | The Map screen: MapLibre, pins, places, family sheet, member card, «Обновить» | M4-07 | ready |
| [M4-09](M4/09-history-and-places-ui.md) | History with stops; place list and editor with address search | M4-08 | ready |
| [M4-10](M4/10-alerts-rules-everywhere-manual.md) | Alerts, visibility rules, Today, person page, settings, taps, manual, M4 wrap-up | M4-09, M4-05 | ready |

M4-05 may run any time after M4-04 (it needs only the server). M4 acceptance (after all plans): APK on the family's
phones against the real server, sharing on every phone for a few days, a school day with arrive / leave alerts, pause,
«Обновить», a kid turning location off, battery use on the family's phone brands, Russian changelog.

Shared references for all M4 plans: [location design](../architecture/location.md), ADR-0024…ADR-0028
([index](../adr/README.md)), [location spec](../product/features/location.md), mockup `docs/design/mockups/Map.dc.html`.
