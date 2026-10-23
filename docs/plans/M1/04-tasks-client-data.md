---
id: M1-04
status: ready
depends_on: [M1-02]
requirements: [TSK-001, TSK-002, TSK-003, TSK-004, TSK-005, TSK-007, TSK-012, TSK-013, TSK-015, TSK-020, TSK-021, TSK-030, TAG-002, TAG-003, FND-040]
---
# M1-04: Tasks in the client core — local tables, adapters, repository views, writer, reminders

## Goal

Everything below the UI on the phone: tasks, checklist items and completions sync and work offline; the repository
computes ready-to-show views (groups, overdue, done, checklist progress, permissions per task); a writer use case covers
every user action; local reminders for the member's tasks are scheduled automatically. Proven by unit and multi-device tests.

## Read first

- [Tasks design](../../architecture/tasks.md) § Client (all), § Schedule logic, § Write rules
- [Sync protocol § client data model](../../architecture/sync.md#client-data-model-sqlite) and client implementation notes
- [Conventions § Client](../../process/conventions.md#client) — "New syncable type on the client"
- Code: `client/…/sync/EntityAdapter.kt`, `Adapters.kt` (`TagAdapter`, `PersonAdapter`), `LocalWriter.kt`, `SyncState.kt`
  (`DATA_SCHEMA_VERSION`), `db/LocalDb.kt` (`clearAll`), `sqldelight/…/tag.sq`, `migrations/1.sqm`, `data/Repositories.kt`,
  `data/Models.kt`, `data/Writers.kt`, `di/Modules.kt`, `notifications/Reminders.kt`, `notifications/Notifications.kt`
  (`NotificationsCoordinator`, `NotificationSettingsRepository`), Android `notifications/SyncWorker.kt`;
  tests `jvmTest/…/sync/LocalWriterTest.kt`, `integration-tests/…/Harness.kt`

## Scope

- SQLDelight: `task.sq`, `checklist_item.sq`, `task_completion.sq` (columns per design) with queries `selectAll`, `selectById`,
  `selectByTask` (children), `upsert`, `deleteById`, `deleteAll`; `migrations/2.sqm` with the same DDL; `LocalDb.clearAll`.
- `DATA_SCHEMA_VERSION = 2`.
- `EntityAdapter` hooks `authorize` / `checkDelete` (design § Adapters) and their calls in `LocalWriter.upsert`/`delete`.
- `TaskAdapter`, `ChecklistItemAdapter`, `TaskCompletionAdapter` in `Adapters.kt`, registered in `defaultEntityAdapters`.
- `data/tasks/` (new package): `TaskModels.kt`, `TaskViews.kt` (pure functions), `TasksRepository.kt`, `TasksWriter.kt`,
  `TaskReminderPlanner.kt`; Koin `single`s in `sharedModule`.
- `tools/dev/seed.py tasks`: creates sample tasks for the test family through `POST /api/v1/sync/mutations` (one-off with
  date+time assigned to Дмитрий, routine Mon/Thu 20:00 assigned to Аня and Тимур, a weekly "anyone" routine with a checklist of 3 items, after-completion routine every
  3 months, overdue one-off yesterday, task with the tag «Машина», created if missing), so UI plans and the emulator tester have data.

Out of scope: screens (M1-05…07), notification taps (M1-07).

## Decisions (do not revisit)

- Models (`TaskModels.kt`):
  ```kotlin
  data class TaskView(
      val id: String, val data: TaskData, val state: TaskState, val group: TaskGroup, val overdue: Boolean,
      val dueMoment: Instant?, val assignees: List<MemberView>,   // unknown/deleted ids dropped
      val tags: List<Tag>,                                         // unknown/deleted ids dropped, archived kept
      val checklistDone: Int, val checklistTotal: Int,
      val canEdit: Boolean, val canComplete: Boolean,
      val completedToday: TaskCompletion?,   // latest non-skipped completion with completedAt today (for "done today" rows)
  )
  data class TaskCompletion(val id: String, val data: TaskCompletionData)
  data class ChecklistItem(val id: String, val data: ChecklistItemData, val shownDone: Boolean)
  data class TaskDetail(val task: TaskView, val checklist: List<ChecklistItem>, val history: List<TaskCompletion>, val creator: MemberView?)
  data class TaskFilter(val scope: TaskScope = ALL, val assigneeId: String? = null, val tagId: String? = null,
                        val showDone: Boolean = false, val query: String = "")
  enum class TaskScope { ALL, MINE, ASSIGNED_BY_ME }
  ```
- Pure list logic (`TaskViews.kt`), unit-tested without a database:
  `filter(views, filter, me)`: MINE = assignees contain me **or** "anyone"; ASSIGNED_BY_ME = created by me and assignees non-empty
  and not only me; tag/assignee filters; query = case-insensitive substring of the title. `showDone = false` → open tasks plus
  `completedToday` rows; `showDone = true` → done one-offs sorted by completion time desc.
  `sections(views)`: `OVERDUE` (by due ascending), `TODAY` (timed by time, then untimed, then done-today rows at the end),
  `UPCOMING` (by date, time), `NO_DATE` (by creation id = UUIDv7 order), `DONE`. Counts per scope for the chips.
- `TasksRepository`:
  `observeViews(): Flow<List<TaskView>>`, `observeDetail(id): Flow<TaskDetail?>`, `observeCounts(): Flow<Map<TaskScope, Int>>`,
  `observeForTag(tagId): Flow<List<TaskView>>`. Inputs combined: the three tables, members, tags, household zone (fallback
  `TimeZone.currentSystemDefault()`), own `Me` + `PermissionsRepository.observeMine()`, and `minuteTicks(clock)` — a flow that
  emits `clock.now()` immediately and then at every minute boundary. Children of tasks that are not in the `task` table are
  ignored. Clock from Koin (`getOrNull<Clock>() ?: Clock.System`) so tests control time.
- `TasksWriter` (all through `LocalWriter`; ids via `Ids.newId()`; `createdBy`/`completedBy` = me):
  `create(draft: TaskData, checklist: List<String> = emptyList()): String`, `update(id, newData: TaskData)` (sends only changed
  top-level fields, like `PersonsWriter.update`), `delete(id)`, `complete(id): String` (completion for the **current**
  occurrence computed with the same inputs as the repository; returns completion id for undo), `skip(id): String`,
  `undo(completionId)`, `reopen(taskId)` (deletes every non-skipped completion of a done one-off the viewer may delete;
  `LocalForbiddenException` if none), `addChecklistItem(taskId, text): String` (position = max + 1), `renameChecklistItem`,
  `setChecklistItemDone(itemId, done)` (writes `done` and `doneOccurrence` = current occurrence for routines, null otherwise),
  `deleteChecklistItem`.
- Adapters' `authorize` = the same `TaskRules` calls as the server handlers (M1-02 decisions), reading the parent task from the
  local `task` table; missing parent → `LocalWriteException(ENTITY_NOT_FOUND)`.
- Reminders: `TaskReminderPlanner.reconcile()` per design § Reminders; reminder ids `task:<taskId>`; it reads stored reminder rows
  by id prefix (add query `selectByPrefix` to `reminder.sq` — `id LIKE 'task:%'`); body text via Compose resources `getString`
  is **not** available in the core without UI, so the planner takes a `TaskReminderTexts` interface (`fun body(dueMoment: Instant?,
  occurrence: LocalDate): String`) bound in `uiModule()` to a resource-backed implementation and in the core to an English
  fallback. Triggers: a collector started by `NotificationsCoordinator` on login (debounced 1 s on `observeViews()`), and an
  explicit `reconcile()` in `SyncWorker` after `syncNow()`. Muted `task.reminder` → cancel all `task:` reminders.

## Steps

1. Tables, migration, clear-all, schema version; migration test: open a version-2 database file created with M0 schema
   (use `LaresDb.Schema.migrate` from 2 to 3 on JVM driver) and check the new tables exist.
2. Adapter hooks + LocalWriter calls; three adapters.
3. Models, pure view logic, repository, writer; Koin.
4. Reminder planner; coordinator and `SyncWorker` wiring.
5. `seed.py tasks`.
6. Tests:
   - jvmTest `TaskViewsTest` (pure): every filter and section rule above, chip counts, done-today rows, unknown assignee/tag ids.
   - jvmTest `TasksWriterTest` (in-memory DB like `LocalWriterTest`): outbox contents for each action; Kid cannot edit an
     Adult's task locally (`LocalForbiddenException`, nothing enqueued); complete → view moves to the next occurrence offline;
     undo restores it; reopen one-off; checklist done resets for the next routine occurrence; `update` sends only changed fields.
   - jvmTest `TasksRepositoryTest`: with a test clock, a timed task becomes overdue when the clock passes due time (ticker);
     zone change of the household moves groups.
   - jvmTest `TaskReminderPlannerTest` with `RecordingAlarms`: schedules for assignee, not for others; "anyone" → scheduled;
     changed due time reschedules; completion cancels; muted cancels all; past reminders not scheduled.
   - integration-tests `TaskSyncTest` (extend `Snapshot`, `TestClient` with `tasks`/`tasksWriter`): two kids complete the same
     routine occurrence offline → after sync both completions exist, same current occurrence on all clients, converged;
     two clients tick different checklist items offline → both ticked; one client deletes a task while another ticks an item
     offline → task and children gone everywhere; Kid's forbidden edit made possible by a stale permission → rejected and reverted
     (invariant 3); member without `tasks.view` (custom group) has no task rows after reset.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest` and `:integration-tests:test` while iterating; full gate at the end.

## Docs to update

- sync.md § client data model: typed tables list; tasks design if a detail changed.
- Tasks spec progress notes (client core).
- `tools/dev/README` or the `emulator` skill's command list if it documents `seed.py` commands — add `seed.py tasks`.
- STATUS.md; plan status.

## Stop and report if

- The materializer or full resync cannot handle child rows of a missing parent without engine changes beyond the two hooks.

## Handoff

Russian summary: the phone now stores tasks offline, syncs them, knows which are overdue or done and schedules task reminders
by itself; tested with three simulated phones (two kids completing the same chore offline). Next M1-05.
