---
id: M4-02
status: done
depends_on: [M4-01]
requirements: [LOC-005, LOC-006, LOC-020, LOC-021]
---
# M4-02: Places, alerts and visibility rules on the server

## Goal

The server stores and syncs places and arrive/leave subscriptions, stores visibility rules with an admin REST API,
creates the default rules for new and existing households, and keeps the history retention setting. Positions come in
M4-03.

## Read first

- [Location design](../../architecture/location.md) § Server: Tables (V9), Sync handlers, Visibility service
- [ADR-0025](../../adr/0025-location-visibility-rules.md), [ADR-0023](../../adr/0023-owner-only-sync-rows.md)
- [sync.md](../../architecture/sync.md) § Entity types, § Housekeeping; [conventions](../../process/conventions.md)
  § Server ("New syncable type")
- Code (templates): `server/…/calendar/EventReminderSyncHandler.kt` (owner-only `canSee`, derived id, no client
  delete), `calendar/EventSyncHandler.kt`, `calendar/CalendarTables.kt`, `calendar/CalendarChecks.kt`
  (`requireAddedPersons`-style checks), `sync/ReferenceChecks.kt`, `Application.kt` (`AppDeps`, `SyncHandlers` list,
  `module()` routing), `household/HouseholdRoutes.kt`, `household/HouseholdAdminService.kt`,
  `household/GroupAdminService.kt`, `household/PermissionService.kt`, `household/PermissionChecks.kt`,
  `identity/SetupService.kt` (`createHousehold`), `identity/MemberRemoval.kt`, `sync/SyncHousekeeping.kt`,
  `db/migration/V8__calendar.sql`; tests `server/src/test/…/calendar/CalendarPermissionsTest.kt`,
  testFixtures `AdminFixtures.kt`, `SyncFixtures.kt`, `CalendarFixtures.kt`

## Scope

- `V9__location_places.sql`: `place`, `place_alert`, `location_rule`, `household.location_history_days`, default rules
  for existing households (design § Tables).
- Package `app.lares.server.location`: `LocationTables.kt` (`PlaceTable`, `PlaceAlertTable`, `LocationRuleTable`),
  `PlaceRepository.kt`, `PlaceSyncHandler.kt`, `PlaceAlertSyncHandler.kt`, `LocationChecks.kt`
  (`requireAddedPlaces`, `requireLiveMember`), `LocationVisibilityService.kt`, `LocationHub.kt` (the in-process bus,
  events `MemberChanged`, `VisibilityChanged`, `PlacesChanged`; only `VisibilityChanged`/`PlacesChanged` are published
  in this plan), `LocationAdminRoutes.kt`.
- `AppDeps`: `locationHub`, `locationVisibility`, handlers in `SyncHandlers` after the calendar ones; routes inside
  `protocolChecked {}`.
- `HouseholdData`/`HouseholdRepository`/`HouseholdAdminService`: `locationHistoryDays` read, synced in the `household`
  row, updated by `PATCH /household`.
- `SetupService.createHousehold`: default rules with the new Adults/Kids ids.
- `removeMember`: delete rules naming the member; tombstone (with `recordChange`) the `place_alert` rows whose
  `member_id` or `subscriber_id` is the member. No hub publish: the removal already records a change, which the live
  channel (M4-03) listens to.
- Group delete (groups are tombstoned, so no FK cascade fires): `GroupAdminService.delete` deletes the rule rows naming
  the group in the same transaction (no hub publish, same reason; no constructor change).
- Housekeeping: purge `place_alert`, then `place`.
- `testFixtures`: `LocationFixtures.kt` (`newPlace(...)`, `newPlaceAlert(...)`, `putRules(...)`).
- docs: sync.md entity table rows, api.md endpoints.

Out of scope: positions, state, live channel (M4-03), presence and notifications (M4-04), client (M4-06).

## Decisions (do not revisit)

- Handlers as the design § Sync handlers. `place.canSee` → `location.view`; `place_alert.canSee` → `location.view` and
  `subscriberId == viewer.memberId`; tombstones visible to all.
- `PlaceAlertSyncHandler.checkUpsert`: mutation id = `Ids.placeAlertRowId(memberId, subscriberId)` (`id:
  INVALID_FORMAT`); `memberId`, `subscriberId` immutable; the writer is the subscriber (`FORBIDDEN` via `authorize`);
  `memberId` must be a live member (`ENTITY_NOT_FOUND`); added `placeIds` must be live places of the household
  (`ENTITY_NOT_FOUND`). Ids of later deleted places stay in rows and are ignored by readers.
- `location_rule.position` keeps the order of the PUT list; `GET` returns that order. `PUT` deletes all rows of the
  household and inserts the new ones in one `dbQuery` under `lockHousehold`. Validation: `Validators.locationRules`
  first; then every GROUP id must be a live group (`group_entity`, not deleted) of the household and every MEMBER id a
  live member, else `VALIDATION_FAILED` with `rules.N.subject|target: INVALID_FORMAT`. Rule ids are new UUIDv7 on every
  PUT.
- `LocationVisibilityService.levels(tx, householdId, viewerMemberId)` loads rules, live members, group memberships and
  the viewer's effective permissions once and calls `LocationVisibility.level` per member. Group ids of a member = their
  `group_membership` rows (Administrators included).
- Default rules for existing households in SQL: Adults/Взрослые and Kids/Дети matched by `lower(name)` among live groups;
  if a household has several matches, the oldest group wins; if either group is missing, insert nothing.
- `PATCH /household` with `locationHistoryDays` outside `[7, 14, 30, 60, 90]` → `VALIDATION_FAILED`
  (`locationHistoryDays: OUT_OF_RANGE`); the value is part of the synced `household` row (recordChange as today).
- No access-version bump for rule changes (ADR-0025): rules never change sync visibility. `location_rule` group and
  member FKs are `ON DELETE CASCADE` (housekeeping hard-deletes old tombstones; a dangling rule would fail the purge);
  V9's default-rule inserts use `gen_random_uuid()`.
- `PartyKind.UNKNOWN` is refused by `Validators.locationRules` and never stored.

## Steps

1. Migration and tables; repository; household field end to end.
2. Place and alert handlers, checks, registration, housekeeping.
3. Visibility service, admin routes, setup defaults, member and group hooks, hub.
4. Tests:
   - `PlaceSyncTest` (LOC-020): adult with `manage_places` creates, edits, deletes; kid gets `FORBIDDEN`; validation
     codes; `createdBy` immutable; kid with `location.view` pulls places; a member without `location.view` pulls none;
     another household's place → `ENTITY_NOT_FOUND`.
   - `PlaceAlertSyncTest` (LOC-021): derived id required; writer must be the subscriber; self-alert rejected; unknown
     place → `ENTITY_NOT_FOUND`; rows visible only to the subscriber (admin sees none, cursor still advances); client
     delete `FORBIDDEN`; tombstone of the place leaves the alert row unchanged.
   - `LocationRulesTest` (LOC-006): GET defaults after setup (two rules, ids of Adults/Kids); PUT replaces and keeps
     order; non-admin `FORBIDDEN`; foreign group or member id → `VALIDATION_FAILED` with the field; deleting a group
     removes its rules; removing a member removes rules naming them and tombstones the alert rows about / by them;
     `levels()` for the default family (admin, adult, kid, viewer without `location.view`); the rules endpoints are
     added to `AdminPermissionMatrixTest`; a second household's admin sees only their own rules.
   - `HouseholdLocationSettingsTest` (LOC-005): default 30 in the pulled household row; PATCH 14 → synced; 45 →
     `VALIDATION_FAILED`.
   - `V9MigrationTest`: `TestDatabase` migrates once to the latest version, so this test creates its own database in
     the same container (`CREATE DATABASE v9_test`), runs `Flyway.configure().dataSource(…).target("8").migrate()`,
     inserts a household with groups «Взрослые»/«Дети» by SQL, migrates to latest and asserts the two default rules; a
     household without such groups gets none.

## Acceptance

- `tools/dev/verify.sh :server:test --tests '*Location*' --tests '*Place*' --tests '*V9*'` while iterating; full gate at the end.
- `tools/dev/server.sh reset && tools/dev/seed.py family && tools/dev/seed.py call GET /location/rules` prints the two
  default rules.

## Docs to update

- sync.md § Entity types (`place`, `place_alert` owner-only), § Housekeeping (M4 purge list); api.md: `GET/PUT
  /location/rules` and `PATCH /household` `locationHistoryDays` (new section "Location endpoint details (M4)").
- Spec progress notes: LOC-005 (*Server: setting stored and synced (M4-02); purge in M4-03.*), LOC-006, LOC-020,
  LOC-021 (*Server: …*).
- STATUS.md; plan status.

## Stop and report if

- The `household` row cannot carry the new field without breaking older clients' decoding (they must ignore it).

## Handoff

Russian summary: сервер хранит места и подписки «сообщать о прибытии и уходе», администратор может менять правила «кто
кого видит», для семьи созданы правила по умолчанию, срок хранения истории настраивается. Next M4-03.
