---
id: M1-03
status: done
depends_on: [M1-02]
requirements: [TSK-031, TSK-032, TSK-033, FND-051]
---
# M1-03: Task notifications on the server — assigned, completed, overdue job

## Goal

Event notifications through the existing UnifiedPush infrastructure (M0-09): "task assigned" and "task completed" sent after
commit, and a server job that detects overdue tasks (TSK-033) and notifies the creator, also while all phones are offline.

## Read first

- [Tasks design](../../architecture/tasks.md) § Server › Notifications, § Overdue job, § Schedule logic (`nextOverdueCheck`)
- [API § notification endpoint details](../../architecture/api.md#notification-endpoint-details-m0-09)
- Code: `server/…/notifications/NotificationService.kt` (`notify`), `NotificationTexts.kt`, `SyncTickles.kt` (consumer coroutine
  pattern), `platform/db/AppDatabase.kt`, `platform/sync/ChangeRecorder.kt` (`PendingChanges`), `sync/SyncHousekeeping.kt`
  (`start` pattern), `Application.kt`; tests `server/src/test/…/notifications/FakePushService.kt`, `NotificationsTest.kt`
- Handlers from M1-02 (`server/…/tasks/`)

## Scope

- `JdbcTransaction.afterCommit(action: () -> Unit)` in `platform/db` (stored in `PendingChanges`, run by `dbQuery` after the
  change events are published; actions must not throw — wrap and log).
- `tasks/TaskNotifier.kt`: event types `Assigned(householdId, taskId, actorMemberId, memberIds)`,
  `Completed(householdId, taskId, actorMemberId)`, `Overdue(householdId, taskId)`; unbounded `Channel`; `start(scope)` consumer
  (started always in `Application.module`, like `syncTickles`); `suspend fun process(event)` callable from tests.
- Handler hooks: task create/update computes added assignees → `afterCommit { notifier.enqueue(Assigned…) }`; completion create
  per design → `Completed`.
- `tasks/TaskOverdueTracker.kt` (`refresh`) and `tasks/TaskOverdueJob.kt` (`runOnce(now)`, `start(scope)`), wired in
  `AppDeps`/`Application.module` behind `backgroundJobs`.
- `NotificationTexts`: `taskAssigned(language, title, actor)`, `taskCompleted(…)`, `taskOverdue(…)`, en/ru per design.

Out of scope: client display and taps (M1-07), local reminders (M1-04).

## Decisions (do not revisit)

- Recipients (in `process`): load the task (skip if deleted), recipients' member rows (skip deleted), their effective
  permissions (`PermissionService`) — only members with `tasks.view`; never the actor. Actor name = the actor's person name;
  title shortened to 100 chars with `…`.
- `Assigned` on create too (all assignees except the actor); no event for "anyone" tasks or when only removals happen.
- `Completed`: only when `notifyCreatorOnDone`, `skipped = false`, `completedBy != createdBy`, and no other non-skipped
  completion of the same occurrence existed before this one (check inside the transaction).
- `refresh(tx, householdId, taskId, now)` is called at the end of: task insert/update/tombstone (tombstone → set
  `overdue_check_at = null`), completion insert/tombstone. It loads the task, its non-deleted completions and the household
  zone and writes `overdue_check_at` with `TaskSchedule.nextOverdueCheck` — **without** `recordChange` (server-only columns).
  `now` = `MutationContext.now` converted to `kotlin.time.Instant`.
- Job query: `SELECT id, household_id FROM task WHERE deleted = false AND overdue_check_at <= now ORDER BY overdue_check_at
  LIMIT 500`; per household one `dbQuery { lockHousehold(...); … }`; rules exactly as in the design (24 h staleness guard,
  `updated_at` guard, always set `overdue_notified_occurrence = c` once handled). Overdue notice → `afterCommit { enqueue(Overdue) }`.
- Job timing: first run 30 s after start, then every 60 s; exceptions logged, loop continues (same pattern as housekeeping).
- Logs: never task titles or names; ids and counts only.

## Steps

1. `afterCommit` + unit test (runs after commit, not on rollback, not on a retried attempt's rollback).
2. Texts; `TaskNotifier` with `process`; wiring.
3. Handler hooks for assigned/completed.
4. Tracker + job; wiring behind `backgroundJobs`.
5. Tests (`server/src/test/…/tasks/TaskNotificationsTest.kt`), using `FakePushService`/`FakeDevice` and `TestClock`
   (call `notifier.process` through the running consumer and await delivery, or call `process` directly — pick one and
   keep it deterministic, no sleeps):
   - TSK-031: Adult creates a task for a Kid → Kid's device receives `task.assigned` with title/body in the session language
     (`ru` and `en`); the actor gets nothing; adding a second assignee notifies only the new one; "anyone" task → nothing; muted
     type → nothing; assignee without `tasks.view` → nothing.
   - TSK-032: creator opted in, Kid completes → creator gets `task.completed`; creator completes own task → nothing; a second
     completion of the same occurrence → nothing; skip → nothing; opt-in off → nothing.
   - TSK-033 (call `job.runOnce(now)` with the test clock): timed one-off passes due time → creator notified once; second run →
     nothing; date-only task → notice at 09:00 next day in the household zone, not before; completed before due → nothing;
     task saved with a date already past → nothing; fixed routine Mon/Thu missed Monday → one notice Monday, next notice only
     for Thursday if Thursday is missed too; after-completion routine notified once until completed; job down for 3 days →
     at most one notice for the current occurrence, none for stale ones; deleted task → nothing; isolation: household B's job
     run never touches A's tasks (one job serves all — assert recipients).
   - `overdue_check_at` is recomputed on edits (due date moved later → no notice at the old time) and does not change `seq`.

## Acceptance

- `tools/dev/verify.sh :server:test` while iterating; full gate at the end.

## Docs to update

- api.md § notification details: list the task types and their triggers (link to the tasks design).
- Foundation spec FND-051: note that task notifications use the infrastructure (still `doing` until the real-phone check).
- Tasks spec progress notes under TSK-031…033 (server done).
- STATUS.md; plan status.

## Stop and report if

- `NotificationService.notify` cannot be used from the consumer without changing its signature in a way that breaks M0 tests.

## Handoff

Russian summary: the server now tells assignees about new tasks, tells the creator when a task is done (if asked) and when a
task is overdue — even if every phone is offline; the notice for tasks without a time comes at 09:00 next day (customer decision
Q-016). Next M1-04.
