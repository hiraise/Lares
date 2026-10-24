---
id: M1-01
status: done
depends_on: [M0-11]
requirements: [TSK-001, TSK-002, TSK-003, TSK-004, TSK-010, TSK-011, TSK-012, TSK-013, TSK-015, TSK-016, TSK-030, TSK-034, TAG-002]
---
# M1-01: Tasks contract — data, schedule logic, write rules, validators

## Goal

All shared code of the tasks module in `:contract`: entity data classes, the recurrence and current-occurrence logic,
permission rules and validators, used unchanged by the server (M1-02, M1-03) and the client (M1-04). Pure Kotlin, no I/O,
fully unit-tested. No server or client code changes except what is needed to compile.

## Read first

- [Tasks design](../../architecture/tasks.md) — **binding**; whole document
- [ADR-0017](../../adr/0017-task-occurrences-derived-from-completions.md), [ADR-0018](../../adr/0018-tags-embedded-in-entities.md)
- [Tasks spec](../../product/features/tasks.md) § Requirements, § Permissions and privacy
- Existing contract code: `entities/Entities.kt` (style, lenient enums), `permissions/WriteRules.kt`, `PermissionEvaluator.kt`,
  `validation/Validators.kt` (`ValidationErrors`, `requiredText`), `serialization/Lenient.kt`, `sync/SyncDtos.kt` (`EntityTypes`),
  `api/notifications/NotificationDtos.kt`

## Scope

- `contract/build.gradle.kts`: kotlinx-datetime `implementation` → `api`.
- `entities/TaskEntities.kt`: `TaskData`, `Recurrence`, `RecurrenceMode`, `RecurrenceUnit`, `ChecklistItemData`,
  `TaskCompletionData` exactly as in the design (field order as listed — new fields only ever appended).
- `sync/SyncDtos.kt`: `EntityTypes.TASK`, `CHECKLIST_ITEM`, `TASK_COMPLETION`.
- `tasks/Recurrences.kt`, `tasks/TaskSchedule.kt` (package `app.lares.contract.tasks`).
- `permissions/TaskRules.kt`; `WriteRules.canMutate` gate for the three types.
- `validation/Validators.kt`: `task`, `checklistItem`, `taskCompletion` + constants.
- `NotificationType.TASK_REMINDER` (`task.reminder`, channel `REMINDERS`).

Out of scope: server tables and handlers (M1-02), client tables (M1-04), any UI.

## Decisions (do not revisit)

- Enum wire names lowercase snake (`fixed`, `after_completion`, `day`, `week`, `month`, `year`) via `@SerialName`, lenient
  serializers like `ContactKind` (fallback: **none** — decode unknown to `null`; `Recurrence` itself then fails to decode and the
  client adapter treats the task as a one-off; implement with a nullable lenient serializer pattern from `Lenient.kt`). If the
  lenient pattern cannot express "unknown → whole field null", use plain enums and let `EntityAdapter.decodeOrNull` drop the
  row; note it in the handoff.
- `Set<DayOfWeek>`: serialize as ISO names (`"MONDAY"`) with a lenient set serializer that drops unknown names.
- Schedule API (exact names; all pure, top-level `object`s):
  ```kotlin
  object Recurrences {
      fun firstOnOrAfter(rule: Recurrence, anchor: LocalDate, date: LocalDate): LocalDate   // fixed rules only
      fun lastOnOrBefore(rule: Recurrence, anchor: LocalDate, date: LocalDate): LocalDate?  // null if date < first
      fun plusUnits(date: LocalDate, unit: RecurrenceUnit, n: Int): LocalDate             // month/year clamp the day
  }
  data class TaskState(val occurrence: LocalDate?, val done: Boolean, val doneBy: TaskCompletionData?)
  enum class TaskGroup { OVERDUE, TODAY, UPCOMING, NO_DATE, DONE }
  object TaskSchedule {
      val DAY_START: LocalTime = LocalTime(9, 0)            // Q-016
      fun state(task: TaskData, completions: List<TaskCompletionData>, today: LocalDate, zone: TimeZone): TaskState
      fun dueMoment(task: TaskData, occurrence: LocalDate, zone: TimeZone): Instant?
      fun isOverdue(task: TaskData, state: TaskState, now: Instant, zone: TimeZone): Boolean
      fun group(task: TaskData, state: TaskState, now: Instant, zone: TimeZone): TaskGroup
      fun reminderAt(task: TaskData, occurrence: LocalDate, zone: TimeZone): Instant?   // null without reminder
      fun overdueNoticeAt(task: TaskData, occurrence: LocalDate, zone: TimeZone): Instant
      fun isChecklistItemDone(task: TaskData, item: ChecklistItemData, state: TaskState): Boolean
      fun nextOverdueCheck(task: TaskData, completions: List<TaskCompletionData>, notifiedOccurrence: LocalDate?,
                           now: Instant, zone: TimeZone): Instant?
  }
  ```
  Completions passed in are the task's non-deleted ones; callers filter. `doneBy` = the earliest non-skipped completion of a
  done one-off. Rules exactly as in the design (§ schedule logic, § overdue job). Use `kotlin.time.Instant` and
  kotlinx-datetime 0.8 APIs (`toInstant(zone)`, `toLocalDateTime(zone)`, `plus(DatePeriod)`).
- Week numbering: week index = `(mondayOf(date) − mondayOf(anchor)) / 7`; a date is scheduled iff index ≥ 0,
  `index % n == 0`, weekday ∈ weekdays, date ≥ anchor.
- `TaskRules` (object, `Viewer` from `WriteRules.kt`):
  ```kotlin
  fun canCreate(viewer: Viewer, task: TaskData): Boolean     // TASKS_VIEW && TASKS_CREATE && createdBy == viewer.memberId
  fun canEdit(viewer: Viewer, task: TaskData): Boolean       // TASKS_VIEW && (createdBy == me || TASKS_EDIT_ANY)
  fun canComplete(viewer: Viewer, task: TaskData): Boolean   // TASKS_VIEW && (assignees empty || me in assignees || canEdit)
  fun canDeleteCompletion(viewer: Viewer, task: TaskData, completion: TaskCompletionData): Boolean // author || canEdit
  fun canChangeChecklistItem(viewer: Viewer, task: TaskData, changedFields: Set<String>): Boolean
      // changedFields ⊆ {done, doneOccurrence} → canComplete; otherwise canEdit
  ```
  `WriteRules.canMutate` for `TASK`, `CHECKLIST_ITEM`, `TASK_COMPLETION` returns `permissions.has(TASKS_VIEW)` (coarse gate;
  update its KDoc to say the entity-level check is `TaskRules`).
- Validators return field maps like `Validators.tag`; constants `TASK_TITLE_MAX = 200`, `CHECKLIST_TEXT_MAX = 200`,
  `TASK_MAX_ASSIGNEES = 20`, `TASK_MAX_TAGS = 20`, `RECURRENCE_INTERVAL_MAX = 999`, `REMINDER_MINUTES_MAX = 43200`,
  `CHECKLIST_POSITION_MAX = 1_000_000`; notes reuse `NOTES_MAX`. Normalization helpers `Validators.normalizeTask(data)`
  (trim title and notes, blank notes → null) and `normalizeChecklistItem` (trim text), used by server and client adapters.

## Steps

1. Gradle change; data classes and `EntityTypes`; `TASK_REMINDER`.
2. `Recurrences` with the four units; then `TaskSchedule`.
3. `TaskRules`, `WriteRules` gate; validators and normalizers.
4. Tests (`contract/src/commonTest`, names start with requirement ids):
   - Serialization round trips of every class, incl. ISO dates/times, weekday sets, unknown enum values, missing optional fields.
   - `Recurrences`: every N days; weekly Mon/Thu, every 2 weeks with anchor mid-week; monthly from Jan 31 (Feb 28, Feb 29 in a
     leap year, Mar 31), every 3 months; yearly from Feb 29; `lastOnOrBefore` before the first date → null.
   - `TaskSchedule.state` — TSK-012/013: fixed Mon/Thu, Monday missed → on Tuesday current = Monday (overdue), on Thursday
     current = Thursday; completing Monday on Wednesday → current Thursday; two completions of the same occurrence → same
     result as one; skip advances like a completion; undo (completion removed) restores the occurrence; completing early.
   - TSK-011/016: after-completion 3 months — completed early, late, twice concurrently (latest `completedAt` wins); start date
     moved later postpones.
   - One-off: done with one completion; skipped-only does not count; `doneBy` = earliest.
   - `isOverdue`/`group` for timed and date-only tasks around midnight in `Europe/Moscow` and in a zone with DST
     (`Europe/Berlin` on the last Sunday of October); `reminderAt` with `DAY_START`; `overdueNoticeAt`.
   - Checklist reset per occurrence for routines.
   - `nextOverdueCheck`: not notified → notice time of current; notified fixed routine → notice time of the next scheduled
     date; notified after-completion → null; done one-off → null.
   - `TaskRules` matrix: admin, Adults, Kids × creator / assignee / other × anyone task — one test per permission row of the spec.
   - Validators: every field error listed in the design.

## Acceptance

- `tools/dev/verify.sh :contract:check` passes; then the full gate `tools/dev/verify.sh` (server and client still compile;
  the server test `SyncPushTest` that expects `task` to be an unknown type and `WriteRulesTest` line about `task` must be
  updated: `task` is now a known type — change those tests to another unknown name such as `"widget"`).

## Docs to update

- Tasks design only if a detail had to change (explain in the handoff).
- STATUS.md; plan status.

## Stop and report if

- kotlinx-datetime 0.8 lacks an API the logic needs and a workaround would change the rules.

## Handoff

Russian summary: shared rules of tasks are ready — how routines compute the next date, who may do what; tested on
examples from the spec (trash on Mon/Thu, filter every 3 months). Next plan M1-02.
