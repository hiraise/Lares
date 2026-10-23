---
id: M1-02
status: ready
depends_on: [M1-01]
requirements: [TSK-001, TSK-002, TSK-003, TSK-004, TSK-005, TSK-015, TAG-002]
---
# M1-02: Tasks on the server — tables, sync handlers, permissions, cascade

## Goal

The server stores and syncs tasks, checklist items and completions: migration, three `WritableSyncHandler`s with
entity-level permission checks (`TaskRules`), reference checks, visibility by `tasks.view`, cascade tombstones and
housekeeping. Notifications and the overdue job are M1-03.

## Read first

- [Tasks design](../../architecture/tasks.md) § Contract data, § Write rules, § Validation, § Server › Tables, § Server › Handlers
- [Sync protocol](../../architecture/sync.md) § Push (processing order), § Housekeeping
- [Conventions § Server](../../process/conventions.md#server) — "New syncable type" checklist
- Code: `server/…/sync/WritableSyncHandler.kt` (whole), `server/…/tags/TagSyncHandler.kt` (example handler + table object),
  `server/…/household/HouseholdRefs.kt` (`requireMembers`), `server/…/sync/SyncHousekeeping.kt`, `Application.kt` (`AppDeps`,
  handler registration), test fixtures `testing/SyncFixtures.kt`, `AdminFixtures.kt`

## Scope

- `V6__tasks.sql` exactly as in the design (including the server-only `overdue_*` columns, unused until M1-03).
- Package `app.lares.server.tasks`: `TaskTables.kt` (Exposed `TaskTable`, `ChecklistItemTable`, `TaskCompletionTable`),
  `TaskSyncHandler`, `ChecklistItemSyncHandler`, `TaskCompletionSyncHandler`, `TaskRepository` (shared loads: task by id in
  household, completions of a task, household time zone).
- `WritableSyncHandler.authorize` hook (design § Handlers).
- Registration in `AppDeps.syncHandlers`; housekeeping purge of the three tables; `docs/architecture/sync.md` entity table.

Out of scope: notifications, `overdue_check_at` maintenance (M1-03); client (M1-04).

## Decisions (do not revisit)

- Columns: `due_date` → Exposed `date()` (java `LocalDate`), `due_time` → `time()` (java `LocalTime`), converted with
  kotlinx-datetime JVM converters (`toKotlinLocalDate()`, `toJavaLocalDate()` etc.); `assignee_ids`, `tag_ids`, `recurrence`
  as JSON text encoded with `LaresJson` (as `person.contacts`); `completed_at` → `timestamp()`.
- `authorize` is called in `WritableSyncHandler.upsert` right after `normalize(decodeMerged(…))` and before `validate`, and in
  `delete` after the early `requireAllowed` and before `checkDelete`. Default: no-op. Existing M0 tests must stay green.
- Task handler `authorize`: create → `TaskRules.canCreate(viewer, merged)`; update → `canEdit(viewer, existing)` (a member
  who loses edit rights cannot edit; an edit that takes the creator's own rights away is still allowed because the check uses
  the stored task); delete → `canEdit(viewer, existing)`. Violations → `FORBIDDEN`.
- Checklist handler `authorize`: loads the parent task (`ENTITY_NOT_FOUND` / `ENTITY_DELETED` as in the design); create and
  delete → `canEdit`; update → `canChangeChecklistItem(viewer, task, changedFields)` where `changedFields` = top-level keys
  whose values differ between `existing` and `merged`.
- Completion handler `authorize`: create → parent checks, `completedBy == viewer.memberId` else `FORBIDDEN`, then
  `canComplete`; update of an existing completion with any changed value → `VALIDATION_FAILED` (immutable); delete →
  `canDeleteCompletion`. `skipped = true` on a one-off → `VALIDATION_FAILED skipped: INVALID_FORMAT`; `completedAt` > now + 1 day
  → `completedAt: OUT_OF_RANGE`.
- Reference checks in `checkUpsert` (design § Validation, server-only checks): only ids added relative to the stored row;
  members via `HouseholdRefs` style query on `member` (non-deleted), tags via `TagTable` (non-deleted, same household).
  Routine → one-off → `recurrence: INVALID_FORMAT`; changed `createdBy` → `createdBy: INVALID_FORMAT`; changed `taskId` of a child
  → `taskId: INVALID_FORMAT`.
- Cascade: `TaskSyncHandler.tombstone` also tombstones non-deleted checklist items and completions of the task, one
  `tx.recordChange(householdId)` per row, children before the task row is returned.
- `canSee` = `viewer.permissions.has(Permission.TASKS_VIEW)` for all three handlers.
- Housekeeping order: `checklist_item`, `task_completion`, `task` (purge only tombstones; FKs cascade physical deletes anyway).

## Steps

1. Migration and tables; `TaskRepository`.
2. `authorize` hook in `WritableSyncHandler` (+ KDoc).
3. Three handlers (`changesSince`, `load`, `validate` = contract validators, `normalize` = contract normalizers, `checkUpsert`,
   `authorize`, `insert`/`update`/`tombstone`, `toJson`); register them.
4. Housekeeping and docs (sync.md: entity type table gets the three types, purge list).
5. Test fixture helpers in `testing/TaskFixtures.kt` (testFixtures, reused by M1-03 and `integration-tests`):
   `newTask(createdBy, title, …): Mutation`, `newCompletion(taskId, by, occurrence, at)`, `newChecklistItem(taskId, text, position)`.
6. Tests (`server/src/test/…/tasks/TaskSyncTest.kt`, `TaskPermissionsTest.kt`), HTTP level with `testApp`, names with
   requirement ids:
   - Create one-off and routine tasks; pull returns them with the data as sent; field-level edit of `title` and `assigneeIds`
     from two sessions both survive.
   - Validation: missing title, time without date, routine without date, bad weekdays, unknown assignee id, tag of another
     household, routine → one-off, changed `createdBy`.
   - Permissions (one test per spec permission row): Kids create own task ✓, edit an Adult's task ✗ `FORBIDDEN`, complete a task
     assigned to them ✓, complete a task assigned to someone else ✗, complete an "anyone" task ✓, tick a checklist item of an
     assigned task ✓ but rename it ✗; Adults edit any; creator undoes another member's completion ✓; Kid undoes another's ✗;
     completion with `completedBy` of another member ✗.
   - Member without `tasks.view` (custom group): pull contains no task rows, cursor still advances; push of a task → `FORBIDDEN`.
   - Completion immutability; child of a deleted task → `ENTITY_DELETED`; child pointing to another household's task →
     `ENTITY_NOT_FOUND`.
   - Cascade: delete a task with 2 items and 2 completions → pull returns 5 tombstones with distinct seqs; paging with `limit=2`
     returns all of them.
   - Isolation: household B cannot read or write household A's tasks, items or completions (ids reused).
   - Housekeeping: tombstones older than 90 days purged, `purge_horizon_seq` raised.
   - Previously stored tag that was deleted since stays in `tagIds` on an unrelated edit (no rejection).

## Acceptance

- `tools/dev/verify.sh :server:test` while iterating; full gate `tools/dev/verify.sh` at the end.

## Docs to update

- sync.md (entity types, housekeeping), tasks design if a detail changed.
- Tasks spec: keep requirement states `todo` (server only; states change with the UI plans), but add an italic progress note
  under TSK-001…005/015 like the foundation spec does ("*Server: … (M1-02)*").
- STATUS.md; plan status.

## Stop and report if

- The `authorize` hook cannot be placed without changing M0 error precedence (existing tests fail for a reason other than
  the `task` type name).

## Handoff

Russian summary: the server now keeps tasks, checklists and completions and syncs them between phones, checks who may do
what (kids complete their own tasks but cannot change others'), and does not mix families; how it was tested; next M1-03.
