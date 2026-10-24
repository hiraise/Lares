---
status: accepted  # design of M1; binding for the M1 plans
updated: 2026-10-23
---
# Tasks module design (M1)

Technical design for the [tasks spec](../product/features/tasks.md). Decisions behind it:
[ADR-0017](../adr/0017-task-occurrences-derived-from-completions.md) (occurrences) and
[ADR-0018](../adr/0018-tags-embedded-in-entities.md) (tags). Implemented by plans [M1-01…M1-07](../plans/README.md#m1-plans).

## Overview

- Three client-writable sync types: `task`, `checklist_item`, `task_completion`.
- A routine is a `task` with a `recurrence`. Occurrences are **not stored**: the current occurrence is computed from the task
  and its completions by shared code (`TaskSchedule` in `contract/`), the same on the phone and on the server.
- Completing or skipping an occurrence **creates** a `task_completion`; undo / reopen **deletes** it. Completions are
  never edited. Nothing in a task row changes when it is completed, so concurrent completions cannot conflict.
- Dates are local dates and wall-clock times in the **household time zone** (`HouseholdData.timeZone`).
- Tags and assignees are id sets inside the task (ADR-0018).
- Server-side extras: cascade tombstones on task delete, push notifications (assigned, completed, overdue), an overdue
  job. Reminders are local on each phone (FND-050).

## Contract data (`contract/…/entities/TaskEntities.kt`)

kotlinx-datetime becomes an `api` dependency of `:contract` (it is already `implementation`), so `LocalDate`/`LocalTime`
appear in data classes; their default serializers use ISO strings (`2026-10-23`, `20:00`).

```kotlin
@Serializable
data class TaskData(
    val title: String,                                  // trimmed, 1..200
    val createdBy: String,                              // member id; set on create, never changes
    val notes: String? = null,                          // trimmed, blank -> null, ≤ 2000
    val dueDate: LocalDate? = null,                     // one-off: due date; routine: series start (anchor), required
    val dueTime: LocalTime? = null,                     // household wall-clock; needs dueDate; whole minutes
    val assigneeIds: Set<String> = emptySet(),          // member ids; empty = "anyone" (TSK-002)
    val tagIds: Set<String> = emptySet(),               // TAG-002, ADR-0018
    val recurrence: Recurrence? = null,                 // null = one-off task
    val reminderMinutesBefore: Int? = null,             // TSK-030; needs dueDate; 0..43200
    val notifyCreatorOnDone: Boolean = false,           // TSK-032
)

@Serializable
data class Recurrence(
    val mode: RecurrenceMode,                           // "fixed" | "after_completion"
    val unit: RecurrenceUnit,                           // "day" | "week" | "month" | "year"
    val interval: Int = 1,                              // 1..999
    val weekdays: Set<DayOfWeek> = emptySet(),          // only for fixed + week, then non-empty
)

@Serializable
data class ChecklistItemData(
    val taskId: String,                                 // never changes
    val text: String,                                   // trimmed, 1..200
    val position: Int,                                  // ascending order, ties by id; 0..1_000_000
    val done: Boolean = false,
    val doneOccurrence: LocalDate? = null,              // routine: the occurrence the check belongs to
)

@Serializable
data class TaskCompletionData(
    val taskId: String,
    val completedBy: String,                            // member id of the author (always the writer)
    val completedAt: Instant,                           // phone time of the action (kotlin.time.Instant)
    val occurrence: LocalDate? = null,                  // occurrence completed or skipped; null for a one-off without date
    val skipped: Boolean = false,                       // "skip this occurrence" (routines only)
)
```

`RecurrenceMode`, `RecurrenceUnit` use lenient enum serializers without a fallback, and `TaskData.recurrence` uses
`LenientRecurrenceSerializer`: a rule with an unknown mode or unit decodes to `null`, so the client shows the task as a
one-off. The server shares the serializer, so its task handler rejects a mutation whose merged JSON has a non-null
`recurrence` that decoded to `null` (`recurrence: INVALID_FORMAT`). `DayOfWeek` sets are serialized as ISO names
(`MONDAY`) through a lenient set serializer (unknown values dropped).

A week rule's anchor is scheduled only if its weekday is chosen: with anchor Wednesday and weekdays Mon/Thu, the first
date is that Thursday.

Entity types: `EntityTypes.TASK = "task"`, `CHECKLIST_ITEM = "checklist_item"`, `TASK_COMPLETION = "task_completion"`.

## Schedule logic (`contract/…/tasks/`)

Pure functions, no clock or zone lookups inside: callers pass `today: LocalDate`, `now: Instant` and `zone: TimeZone`.

### Scheduled dates of a fixed rule (`Recurrences`)

Anchor `A = task.dueDate`, interval `n`:

| Unit | Scheduled dates |
| --- | --- |
| day | `A + k·n` days, k ≥ 0 |
| week | weeks counted from the Monday of A's week; in every week with index `k·n`, each day whose weekday is in `weekdays` and which is ≥ A |
| month | month `month(A) + k·n`, day `min(day(A), length of that month)` — no drift: Jan 31 → Feb 28 → Mar 31 |
| year | year `year(A) + k·n`, A's month and day, Feb 29 → Feb 28 in non-leap years |

Functions: `firstOnOrAfter(rule, anchor, date): LocalDate` and `lastOnOrBefore(rule, anchor, date): LocalDate?`
(null if before the first scheduled date). No unbounded loops: arithmetic for day/month/year, at most `7·n` days of scanning
for week. `plusUnits(date, unit, n)` (month/year clamp the day) is used by "after completion" rules.

### Current occurrence (`TaskSchedule.current`)

Input: the task, its non-deleted completions, `today`, `zone`.

- **One-off:** occurrence = `dueDate` (may be null). **Done** iff at least one completion with `skipped = false` exists.
- **Fixed routine:** `L` = the latest `occurrence` among all completions (done and skipped). `from = L + 1 day` (or `A` if
  there are none). `first = firstOnOrAfter(from)`. If `first ≤ today`, current = `lastOnOrBefore(today)` — older missed
  dates are dropped, so at most one occurrence is overdue (TSK-013); otherwise current = `first`.
- **After-completion routine:** `last` = the completion with the latest `completedAt` (ties: larger id). None → current = `A`.
  Otherwise current = `max(A, plusUnits(localDate(last.completedAt, zone), unit, n))` — so moving the start date later
  postpones the next time.
- A routine is never done; it always has a current occurrence.

Derived values (`TaskSchedule`):

| Value | Rule |
| --- | --- |
| `dueMoment(occ)` | `dueTime` set: `LocalDateTime(occ, dueTime)` in `zone`; else null |
| `isOverdue(now)` | not done, occurrence not null, and `now ≥ dueMoment` (timed) or `today > occ` (date only) |
| list group | `OVERDUE`, `TODAY` (occ = today, not overdue), `UPCOMING` (occ > today), `NO_DATE` |
| `reminderAt(occ)` | `(dueMoment ?: occ at DAY_START) − reminderMinutesBefore`; `DAY_START = 09:00` (Q-016) |
| `overdueNoticeAt(occ)` | `dueMoment` if timed; else `occ + 1 day` at `DAY_START` (Q-016) |
| checklist item shown done | `done && (one-off || doneOccurrence == current occurrence)` — routine checklists reset every occurrence |
| `nextOverdueCheck(...)` | see [overdue job](#overdue-job) |

Completing "early" is allowed: the client always completes the **current** occurrence, even if it is in the future.

## Write rules (`contract/…/permissions/TaskRules.kt`)

`WriteRules.canMutate` gets a coarse gate for the three task types: `tasks.view`. The entity-level decision is `TaskRules`,
called by the server handlers and by the client adapters (ADR-0014 § 6 ownership rules):

| Action | Allowed when (always also `tasks.view`) |
| --- | --- |
| create task | `tasks.create` and `createdBy == viewer` |
| edit / delete task, add / rename / reorder / delete checklist items | creator, or `tasks.edit_any` (`canEdit`) |
| complete, skip, check checklist items | an assignee, or the task has no assignees ("anyone"), or `canEdit` (`canComplete`) (Q-017) |
| delete a completion (undo, reopen) | its author, or `canEdit` |

A checklist item update that changes only `done`/`doneOccurrence` needs `canComplete`; any other field needs `canEdit`.

## Validation (`Validators.task`, `.checklistItem`, `.taskCompletion`)

Field error codes as in [api.md § errors](api.md#errors), keyed by field name.

- Task: `title` REQUIRED / TOO_LONG; `notes` TOO_LONG; `dueTime` without `dueDate` → `dueDate: REQUIRED`; `dueTime` with seconds
  → `dueTime: INVALID_FORMAT`; `recurrence` without `dueDate` → `dueDate: REQUIRED`; `recurrence.interval` outside 1..999 →
  `recurrence: OUT_OF_RANGE`; weekdays empty for fixed+week or non-empty otherwise → `recurrence: INVALID_FORMAT`;
  `reminderMinutesBefore` without `dueDate` → `dueDate: REQUIRED`, outside 0..43200 → OUT_OF_RANGE; more than 20 assignees or
  20 tags → OUT_OF_RANGE.
- Checklist item: `text` REQUIRED / TOO_LONG; `position` OUT_OF_RANGE.
- Completion: `skipped = true` without a routine → `skipped: INVALID_FORMAT` (checked by the handler, which knows the task).

Server-only checks in the handlers (they need the database):

- Task create with `createdBy` ≠ viewer → `FORBIDDEN`; a change of `createdBy` → `VALIDATION_FAILED createdBy: INVALID_FORMAT`.
- Ids **added** by the mutation (not already stored) in `assigneeIds` must be non-deleted members of the household, in
  `tagIds` non-deleted tags → else `assigneeIds`/`tagIds: INVALID_FORMAT`. Ids already stored stay valid even if the member or tag
  was deleted since (clients hide unknown ids).
- A routine cannot become a one-off (`recurrence` non-null → null) → `recurrence: INVALID_FORMAT` (ADR-0017). One-off → routine is allowed.
- Children (`checklist_item`, `task_completion`): `taskId` of a task of another household or missing → `ENTITY_NOT_FOUND`;
  tombstoned task → `ENTITY_DELETED`; `taskId` changed → `taskId: INVALID_FORMAT`.
- Completion: `completedBy` ≠ viewer → `FORBIDDEN`; any change of an existing completion → `VALIDATION_FAILED`
  (completions are immutable); `completedAt` later than server now + 1 day → `completedAt: OUT_OF_RANGE`.

## Server

### Tables (`V6__tasks.sql`)

```sql
task (id uuid PK, household_id uuid NOT NULL → household, title text NOT NULL, notes text NULL,
      created_by uuid NOT NULL → member, due_date date NULL, due_time time NULL,
      assignee_ids text NOT NULL DEFAULT '[]', tag_ids text NOT NULL DEFAULT '[]',   -- JSON arrays (like person.contacts)
      recurrence text NULL,                                                       -- JSON of Recurrence
      reminder_minutes_before int NULL, notify_creator_on_done boolean NOT NULL DEFAULT false,
      seq bigint NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
      deleted boolean NOT NULL DEFAULT false,
      overdue_check_at timestamptz NULL, overdue_notified_occurrence date NULL)   -- server-only, never synced
checklist_item (id uuid PK, household_id, task_id uuid NOT NULL → task ON DELETE CASCADE, text text NOT NULL,
      position int NOT NULL, done boolean NOT NULL DEFAULT false, done_occurrence date NULL, + sync columns)
task_completion (id uuid PK, household_id, task_id uuid NOT NULL → task ON DELETE CASCADE, completed_by uuid NOT NULL → member,
      completed_at timestamptz NOT NULL, occurrence date NULL, skipped boolean NOT NULL DEFAULT false, + sync columns)
```

Indexes: `(household_id, seq)` on each table; `checklist_item(task_id)`; `task_completion(task_id)`;
`task(overdue_check_at) WHERE deleted = false AND overdue_check_at IS NOT NULL`.

### Handlers (package `app.lares.server.tasks`)

`TaskSyncHandler`, `ChecklistItemSyncHandler`, `TaskCompletionSyncHandler` extend `WritableSyncHandler`, registered in
`AppDeps.syncHandlers`. `WritableSyncHandler` gets one new open hook, called after the merged data is decoded and normalized
and before `validate`:

```kotlin
open fun authorize(ctx: MutationContext, op: MutationOp, id: UUID, existing: D?, merged: D?) {}  // throw ApiException
```

The existing early `WriteRules.canMutate` check stays (for task types it is the `tasks.view` gate), so M0 error precedence is
unchanged. For a delete, `merged` is null.

- `canSee(viewer, row)` = `viewer.permissions.has(TASKS_VIEW)` for all three types. Permission changes already bump
  `access_version` (`GroupAdminService`), which resets the member's sync.
- **Cascade:** tombstoning a task also tombstones its non-deleted checklist items and completions in the same transaction,
  each with its own `recordChange` seq (rows must not share a seq: the pull cursor would skip rows cut off by the limit).
- Housekeeping: `SyncHousekeeping` purges `checklist_item`, `task_completion`, then `task` tombstones (children first);
  sync.md § housekeeping lists them.

### Notifications

- `JdbcTransaction.afterCommit(action: () -> Unit)` (in `platform/db`): actions are stored in `PendingChanges` and run by
  `AppDatabase.dbQuery` after the commit, after the change events; nothing runs on rollback.
- `TaskNotifier` (package `tasks`): handlers call it through `afterCommit`; it puts events into an unbounded `Channel`
  consumed by a coroutine started in `Application.module` (always, like `syncTickles`). The consumer loads names and
  permissions and calls `NotificationService.notify(...)` with `entityType = "task"`, `entityId = taskId`.
- Recipients are non-deleted members with `tasks.view`, never the actor.

| Type | When | To |
| --- | --- | --- |
| `task.assigned` (TSK-031) | task create/update adds member ids to `assigneeIds` | the added members |
| `task.completed` (TSK-032) | a completion with `skipped = false` is created, `notifyCreatorOnDone` is true, and it is the first non-skipped completion of that occurrence (one-off: of the task), by someone other than the creator | the creator |
| `task.overdue` (TSK-033) | overdue job | the creator |

Texts (`NotificationTexts`, en/ru, title shortened to 100 characters):

| Type | Title en / ru | Body en / ru |
| --- | --- | --- |
| assigned | New task for you / Вам поручена задача | `{title} — from {actor}` / `{title} — от {actor}` |
| completed | Task done / Задача выполнена | `{title} — {actor}` |
| overdue | Task overdue / Задача просрочена | `{title}` |

### Overdue job

`overdue_check_at` is the next moment the job must look at the task. `TaskOverdueTracker.refresh(householdId, taskId, now)` (inside `dbQuery`)
recomputes it after every write to the task or its completions (task and completion handlers, cascade) and after each job
visit:

```
c = current occurrence at now (today = now in household zone); one-off done or c == null -> null
if overdue_notified_occurrence != c -> overdueNoticeAt(c)                  // may be in the past: the job handles it
else: fixed routine -> overdueNoticeAt(firstOnOrAfter(c + 1 day)); otherwise -> null
```

`TaskOverdueJob.runOnce(now)`: for tasks with `overdue_check_at ≤ now` (oldest first, at most 500 per run), grouped by
household, one transaction per household starting with `lockHousehold`: recompute `c`; if `overdue_notified_occurrence != c`
and `overdueNoticeAt(c) ≤ now`: notify the creator only if `overdueNoticeAt(c) > task.updated_at` (no notice for a date that
was already past when the task was saved) and `now − overdueNoticeAt(c) ≤ 24 h` (no stale notices after downtime); in both
cases set `overdue_notified_occurrence = c`. Then `refresh`. These columns are server-only: no seq, no sync change.
`start(scope)`: first run after 30 s, then every 60 s, only when `AppDeps.backgroundJobs`.

## Client

### Local tables (`2.sqm`, `DATA_SCHEMA_VERSION = 2`)

`task(id PK, title, created_by, notes, due_date TEXT (ISO) NULL, due_time TEXT NULL, assignee_ids TEXT (JSON),
tag_ids TEXT (JSON), recurrence TEXT (JSON) NULL, reminder_minutes_before INTEGER NULL, notify_creator_on_done INTEGER)`;
`checklist_item(id PK, task_id, text, position, done, done_occurrence NULL)` + index on `task_id`;
`task_completion(id PK, task_id, completed_by, completed_at INTEGER (epoch ms), occurrence TEXT NULL, skipped)` + index on `task_id`.
Children of a task deleted locally stay until the server's cascade tombstones arrive; queries ignore children of missing tasks.

### Adapters and writer hooks

`EntityAdapter` gets two open hooks used by `LocalWriter`: `authorize(db, viewer, op, id, current: T?, merged: T?)` (throws
`LocalForbiddenException`; called after merge on upsert, with `merged = null` on delete) and `checkDelete(db, id, current)`.
`TaskAdapter`, `ChecklistItemAdapter`, `TaskCompletionAdapter` implement them with `TaskRules` (loading the parent task from
the typed table). The writer never creates task rows on behalf of the server's cascade.

### Views (`TasksRepository`)

The repository combines tasks, completions, checklist items, members, tags, the household zone (fallback: system zone),
own permissions and a **minute ticker** (`Clock`-driven flow, so overdue states change without data changes) and computes
`TaskView`s in Kotlin with `TaskSchedule`. Households have at most a few hundred tasks, so no SQL-side scheduling.

### Reminders

`TaskReminderPlanner.reconcile()` computes the wanted reminders for the logged-in member — open tasks with
`reminderMinutesBefore` set, where the member is an assignee or the task is "anyone" (Q-015), with
`reminderAt(current)` in the future and at most 30 days ahead, unless `task.reminder` is muted — and makes the stored
`reminder` rows with ids `task:<taskId>` match it (schedule new or changed, cancel the rest). Runs: on login, after every
successful sync cycle, after local task writes, and from `SyncWorker` after its sync. Text: title = task title; body =
"Due today at 20:00" / "Срок: сегодня в 20:00" style, rendered on the phone in the app language.

`NotificationType.TASK_REMINDER` (`task.reminder`, channel `reminders`) is added so members can mute task reminders; the
server stores it like other types but never sends it.

### Notification taps

Notifications with `entityType = "task"` and reminders with id `task:<id>` open the task detail screen.
