# Implementation plans

Detailed, self-contained plans written by a planning session (high effort) and executed by implementation sessions
with a clean context (medium effort). One plan = one session = one approved commit.

## How to execute a plan

Start a new session in the repository root and give it this prompt (replace the plan path):

```text
Ты исполнитель в проекте FamilyHub. Прочитай AGENTS.md и docs/STATUS.md, затем выполни план
docs/plans/M0/NN-name.md от начала до конца. Работай строго по плану и по принятым ADR.
Если шаг невозможно выполнить или план противоречит ADR — остановись и опиши проблему, не импровизируй.
Не делай коммит. В конце обнови документацию по разделу "Docs to update" и выдай сводку для заказчика
на русском по разделу "Handoff". Коммит — только после одобрения заказчика.
```

The session:

1. Reads AGENTS.md → STATUS.md → the plan → the documents listed in its "Read first".
2. Sets the plan status to `in-progress` in this README.
3. Implements the steps, runs the acceptance commands.
4. Updates docs, sets the plan status to `done` (or `blocked` with a reason), updates STATUS.md.
5. Presents the Russian handoff summary and waits for the customer's approval; then commits
   (Conventional Commit, plan id in the subject, e.g. `feat(server): foundation (M0-02)`).

## Plan format

Every plan has: front matter (`id`, `status`, `depends_on`, `requirements`), Goal, Read first, Scope / Out of scope,
Decisions (already made — do not revisit), Steps, Tests, Acceptance, Docs to update, Stop and report if, Handoff.

Rules for executors:

- Do not change decisions listed in a plan or in accepted ADRs. If one is wrong, stop and report.
- Use only dependencies from `gradle/libs.versions.toml`. Adding one requires a license check (ADR-0003, ADR-0006)
  and a note in the handoff.
- Keep the build green: `./gradlew check` must pass before the handoff.
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
| [M0-09](M0/09-notifications.md) | Notifications: reminders, UnifiedPush, background sync | M0-08 | ready |
| [M0-10](M0/10-deploy-and-ops.md) | Deploy and operations | M0-05 | ready |
| [M0-11](M0/11-load-simulation.md) | Load simulation and resource budget | M0-10 | ready |

M0 acceptance (after all plans): full stack via Docker Compose, APK on a phone or emulator, walkthrough of the
foundation scenarios, Russian changelog for the customer. Done by the planning session together with the customer.

Shared references for all M0 plans: [versions](M0/versions.md), [sync protocol](../architecture/sync.md),
[auth design](../architecture/auth.md), [API](../architecture/api.md), [foundation spec](../product/features/foundation.md).
