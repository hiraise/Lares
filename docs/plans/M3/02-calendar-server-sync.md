---
id: M3-02
status: ready
depends_on: [M3-01]
requirements: [CAL-001, CAL-002, CAL-003, CAL-005, CAL-006, CAL-013, CAL-015, CAL-016, CAL-017, TAG-002]
---
# M3-02: Calendar on the server — tables, sync handlers, permissions, cascade, birthday setting

## Goal

The server stores and syncs events, occurrence rows and personal reminders with the same rules as the contract, keeps
personal reminders private to their member, tombstones children with their event and stores the member's birthday
reminder lead time. No notifications yet (M3-03).

## Read first

- [Calendar design](../../architecture/calendar.md) § Overview, § Server (Tables, Handlers), § Derived ids, § Write rules
- [ADR-0022](../../adr/0022-event-series-with-occurrence-rows.md), [ADR-0023](../../adr/0023-owner-only-sync-rows.md)
- [Sync protocol](../../architecture/sync.md) § Entity types, § Housekeeping
- Code — the tasks server is the template: `server/src/main/resources/db/migration/V6__tasks.sql`, `V7__shopping.sql`,
  `V5__notifications.sql`; `server/…/tasks/TaskTables.kt`, `TaskRepository.kt`, `TaskSyncHandler.kt`,
  `ChecklistItemSyncHandler.kt`, `TaskChecks.kt`; `sync/WritableSyncHandler.kt`, `sync/SyncEntityHandler.kt` (`canSee`),
  `sync/ReferenceChecks.kt`, `sync/SyncHousekeeping.kt`, `platform/sync/ChangeRecorder.kt`, `Application.kt` (handler
  list); `notifications/NotificationTables.kt`, `NotificationService.kt` (`settings`/`setSettings`),
  `NotificationRoutes.kt`; tests `server/src/test/…/tasks/TaskSyncTest.kt`, `TaskPermissionsTest.kt`,
  testFixtures `TaskFixtures.kt`, `SyncFixtures.kt`, `AdminFixtures.kt`
- Contract from M3-01: `entities/CalendarEntities.kt`, `Ids.kt`, `permissions/EventRules.kt`, validators

## Scope

- `V8__calendar.sql`: the four tables of the design (column types as in `V6`/`V7`: dates `date`, times `time`, id sets
  JSON `text`, enums by `key`), indexes as listed.
- Package `app.lares.server.calendar`: `CalendarTables.kt`, `CalendarRepository.kt` (object; `load`, `toEventData`,
  `writeEvent`, children lookups, `liveSplitOf(householdId, eventId)`), `EventSyncHandler.kt`,
  `EventOccurrenceSyncHandler.kt`, `EventReminderSyncHandler.kt`, `CalendarChecks.kt` (`requireParentEvent`).
- `sync/ReferenceChecks.kt`: `requireAddedPersons(ctx, added: Set<String>)` (live persons of the household →
  else `participantIds: INVALID_FORMAT`).
- Handler registration in `Application.kt` (after shopping); `SyncHousekeeping` purge order and KDoc.
- `NotificationSettings.birthdayDaysBefore` through `GET/PUT /api/v1/me/notification-settings`
  (`notification_preference` row upserted on PUT; missing row → 3; values outside `BIRTHDAY_DAYS_CHOICES` →
  `VALIDATION_FAILED` with `birthdayDaysBefore: OUT_OF_RANGE`).
- testFixtures `CalendarFixtures.kt`: `AppDeps.newEvent(createdBy, title, startDate, …, extra, id)`,
  `newOccurrenceRow(eventId, occurrence, fields)`, `newEventReminder(eventId, memberId, minutes)`, `eventJson(...)`.
- sync.md § Entity types rows (`event`, `event_occurrence`, `event_reminder` — the last one "visible to its member only")
  and § Housekeeping list.

Out of scope: notifications (M3-03), the client (M3-04).

## Decisions (do not revisit)

- Handler flow as `TaskSyncHandler`: `authorize` with `EventRules` (`canEdit(existing)` or `canCreate(merged)`; for
  child rows `canEdit(parent)` / `canSetReminder(merged ?: existing)`), validate with the M3-01 validators
  (`eventOccurrence` gets the stored parent), `checkUpsert` for immutable fields and references as design § Handlers.
- Derived-id check in `checkUpsert` of both child handlers: `mutation id != Ids.occurrenceRowId(eventId, occurrence)`
  (resp. `reminderRowId(eventId, memberId)`) → `invalidField("id")`. Compare canonical lowercase strings.
- Child rows: `WriteRules` already rejects `DELETE` (gate false) → `FORBIDDEN`; no `tombstone` path except the cascade.
- `EventReminderSyncHandler.canSee`: `CALENDAR_VIEW` and `row.data?.get("memberId")?.jsonPrimitive?.content ==
  viewer.memberId`; tombstone rows (`data == null`) are visible to `CALENDAR_VIEW` (a tombstone carries no data).
- Cascade in `EventSyncHandler.tombstone`: live occurrence rows, then live reminder rows, each ordered by id, each with
  its own `recordChange` seq — same comment as tasks.
- `split_from` is a plain `uuid NULL` without FK (the origin may be purged later).
- Housekeeping order: `event_reminder`, `event_occurrence`, `event` after `shopping_list`.
- `requireAddedPersons` checks only newly added ids (like `requireAddedTags`); a person deleted later stays in the set
  (clients drop unknown ids, spec CAL-002 assumption).

## Steps

1. Migration, tables, repository.
2. Three handlers, checks, registration, housekeeping.
3. Birthday setting in the notification settings service and routes.
4. Fixtures; sync.md.
5. Tests (`server/src/test/…/calendar/`, names start with requirement ids):
   - `CalendarSyncTest`: create/update/pull of an event with participants, tags, recurrence and `repeatUntil`; field
     merge of two updates; occurrence row create with the derived id, a second "phone" upsert of the same id merges
     fields; wrong derived id → rejected `id: INVALID_FORMAT`; child for a missing / deleted event →
     `ENTITY_NOT_FOUND` / `ENTITY_DELETED`; changed `eventId` / `occurrence` / `memberId` / `createdBy` / `kind` /
     `splitFrom` rejected; unknown participant or tag → rejected; `splitFrom` to another household's event → rejected;
     event delete tombstones its rows with distinct seqs; child delete → `FORBIDDEN`; housekeeping purges children first.
   - `CalendarPermissionsTest`: Adults / Kids / view-only custom group / admin × create, edit own, edit others', delete,
     occurrence change of others' event, reminder for self and for someone else; a member without `calendar.view` pulls
     no event rows; member A never pulls member B's `event_reminder` row (also not an admin), but pulls own; household
     isolation (second household cannot reference the first's event or person).
   - `NotificationSettingsTest` (extend the existing one): default 3, PUT 7 then GET 7, PUT 5 → `VALIDATION_FAILED`,
     muted set untouched by a days-only change.

## Acceptance

- `tools/dev/verify.sh :server:test --tests '*Calendar*'` and `--tests '*NotificationSettings*'` while iterating; full gate
  at the end.

## Docs to update

- sync.md (types, housekeeping); calendar design if a detail changed; spec progress notes (*Server: …*) under CAL-001,
  CAL-003, CAL-006, CAL-015, CAL-016.
- STATUS.md; plan status.

## Stop and report if

- The pull filter cannot hide `event_reminder` rows per member without changing the cursor logic (ADR-0023 assumes it can).

## Handoff

Russian summary: the server keeps the family calendar — events, changed and cancelled occurrences, everyone's personal
reminders (seen only by their owner) and the birthday reminder setting; Kids cannot change others' events. Next M3-03.
