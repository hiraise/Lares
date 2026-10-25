---
id: M4-01
status: done
depends_on: [M3]
requirements: [LOC-003, LOC-004, LOC-005, LOC-006, LOC-010, LOC-012, LOC-020, LOC-021, LOC-022]
---
# M4-01: Location contract — data, DTOs, visibility, geofence and stop logic, write rules, validators

## Goal

Everything the server and the phones must agree on for location exists in `contract/` and is unit-tested: the sync
types `place` and `place_alert`, the REST and live-channel DTOs, visibility evaluation, distance and circle math, the
geofence hysteresis, stop detection, place write rules, validators, new notification types and the push kind. No
server or client behaviour yet.

## Read first

- [Location design](../../architecture/location.md) § Overview, § Contract (all of it is the specification)
- [ADR-0024](../../adr/0024-positions-outside-the-sync-log.md), [ADR-0025](../../adr/0025-location-visibility-rules.md)
- [Location spec](../../product/features/location.md) § Requirements, § Permissions and privacy
- Code (templates): `contract/…/entities/CalendarEntities.kt` (`EventKind` lenient enum with `key`/`fromKey`),
  `contract/…/Ids.kt` (`reminderRowId`, `derive`), `permissions/EventRules.kt`, `permissions/WriteRules.kt`
  (`canMutate`, `EVENT_REMINDER` upsert-only pattern), `validation/Validators.kt` (`event`, `eventReminder`,
  normalizers), `api/notifications/NotificationDtos.kt` (`NotificationType`, `PushPayload`), `api/admin/AdminDtos.kt`
  (`UpdateHouseholdRequest`), `entities/Entities.kt` (`HouseholdData`), `serialization/Lenient.kt`, `sync/SyncDtos.kt`
  (`EntityTypes`); tests under `contract/src/commonTest` or `jvmTest` (follow where the calendar tests live)

## Scope

- `entities/LocationEntities.kt`: `PlaceData`, `PlaceIcon`, `PlaceAlertData` exactly as the design.
- `EntityTypes.PLACE`, `PLACE_ALERT`; `Ids.placeAlertRowId(memberId, subscriberId)`; `derive` takes `marker: Int`
  (0, `0x5A` reminders, `0x3C` alerts) — existing ids must not change (test with the existing vectors).
- `api/location/LocationDtos.kt`: all DTOs of the design (`PositionFix` … `AddressSearch`), lenient enums
  `SharingStatus`, `OffReason`, `VisibilityLevel`, `PartyKind` (with `UNKNOWN`, the lenient fallback: `LenientEnumSerializer`
  needs a fallback value).
- `HouseholdData.locationHistoryDays: Int = 30`; `UpdateHouseholdRequest.locationHistoryDays: Int? = null`.
- `NotificationType.LOCATION_PLACE` (`location.place`, `FAMILY_ACTIVITY`), `LOCATION_SHARING` (`location.sharing`,
  `FAMILY_ACTIVITY`); `PushPayload.KIND_LOCATION_REFRESH`.
- `location/LocationLimits.kt`, `location/Geo.kt`, `location/LocationVisibility.kt`, `location/Geofence.kt`,
  `location/Stops.kt` with the signatures of the design.
- `permissions/PlaceRules.kt`; `WriteRules.canMutate` for `PLACE`, `PLACE_ALERT`.
- `Validators.place`, `Validators.placeAlert`, `Validators.positionFix`, `Validators.setSharing`,
  `Validators.locationRules(request)`, `Validators.locationHistoryDays`; `normalizePlace` (trim name and address,
  blank address → null).

- Client compile fix (the only client change): `NotificationType.label()` in
  `client/shared/…/ui/settings/NotificationSettings.kt` is an exhaustive `when` — add `notif_type_location_place` /
  `notif_type_location_sharing` strings (en: "Places: arrive and leave", "Location sharing turned off"; ru: «Места:
  прибытие и уход», «Передача геопозиции выключена»). Do not add them to the switchable list (M4-10).

Out of scope: server tables and handlers (M4-02…04), other client code (M4-06+).

## Decisions (do not revisit)

- All shapes, defaults, limits and codes as in the design § Contract. JSON keys are the property names; enums serialize
  as lowercase keys (`"sharing"`, `"off_on_phone"`, `"location_disabled"`, `"current"`, `"everyone"`, `"home"`) with the
  lenient fallback named in the design.
- `Geo.distanceM`: haversine with R = 6 371 008.8 m. `Geo.circle(lat, lon, r, points = 48)`: destination-point formula on
  the sphere, returns `points + 1` pairs `(lat, lon)` with the first repeated last.
- `LocationVisibility.level` exactly as the design; rules with level NONE or a party of kind UNKNOWN match nothing.
  `defaultRules(adults, kids)` = `[Input(GROUP adults → EVERYONE, HISTORY), Input(GROUP kids → GROUP adults, CURRENT)]`.
- `Geofence.reading`: `d ≤ r` → true; `d − acc > r + margin(r)` → false; else null. `step` exactly as the design
  (two consecutive readings confirm; first knowledge never transitions; `since` of a confirmed change = the first
  agreeing reading's time; the transition's `at` = that `since`).
- `Stops.detect` exactly as the design (100 m, 10 min, accuracy ≤ 100 m, merge same place within 15 min, `ongoing`).
- `PlaceRules.canCreate` also requires `createdBy == viewer.memberId`; editing and deleting any place needs only
  `canManage` (places are family data, no ownership).
- `Validators.locationRules`: per input — EVERYONE with an id, GROUP/MEMBER without an id, kind UNKNOWN → `rules.N.subject|target:
  INVALID_FORMAT`; level NONE → `rules.N.level: INVALID_FORMAT`; more than 100 → `rules: OUT_OF_RANGE`. Existence of
  ids is the server's job.

## Steps

1. Entities, ids, DTOs, household fields, notification additions.
2. `LocationLimits`, `Geo`, `LocationVisibility`.
3. `Geofence`, `Stops`.
4. `PlaceRules`, `WriteRules`, validators and normalizer.
5. Tests (names start with requirement ids):
   - `LocationDtosTest`: round trip of every DTO; unknown enum keys fall back (`SharingStatus` → NEVER, `OffReason` →
     STOPPED, `VisibilityLevel` → NONE, `PlaceIcon` → PLACE); `HouseholdData` without `locationHistoryDays` decodes to 30.
   - `IdsTest`: existing `occurrenceRowId` / `reminderRowId` vectors unchanged; `placeAlertRowId` deterministic,
     different from `reminderRowId` for the same inputs, version 8.
   - `GeoTest`: Moscow–St Petersburg ≈ 634 km (±1 km); 100 m north ≈ 100 m; circle ring closed, every vertex at r ± 0.5 m.
   - `LocationVisibilityTest` (LOC-006): self → HISTORY; admin → HISTORY; no `location.view` → NONE even for self; the
     defaults: adult → kid HISTORY, kid → adult CURRENT, kid → kid NONE; member rule beats nothing; widest of two rules
     wins; EVERYONE subject; rules naming unknown groups match nothing.
   - `GeofenceTest` (LOC-022): first reading sets state without transition; inside → two outside readings → LEFT with
     `since` of the first; one outside reading then inside again → no transition; edge zone (`null`) keeps state;
     inaccurate fix (`usable` false) skipped; margin for r = 50 (25 m) and r = 500 (100 m).
   - `StopsTest` (LOC-004): home 07:00–08:10, walk, school 08:25–14:40 → two stops with place ids; a 5-minute pause → no
     stop; inaccurate points ignored; ongoing last stop; same place twice within 15 min merges.
   - `PlaceRulesTest`, `ValidatorsLocationTest`: every row of the design's validation table; `WriteRules` for both types
     (adult with `manage_places`, kid without; alert upsert yes, delete no).

## Acceptance

- `tools/dev/verify.sh :contract:jvmTest` while iterating (tests in `commonTest`; `IdsTest` where the existing id tests
  live, `jvmTest`); full gate at the end.

## Docs to update

- Location design only if a signature had to change (note it under a new "Implementation notes (M4-01)").
- Glossary § Location: add Sharing status (Состояние передачи), Pause (Пауза), Visibility rule (Правило видимости
  геопозиции), Visibility level (Где сейчас / С историей), Stop (Остановка), Place alert (Подписка на место), Sharing
  phone (Телефон, который передаёт). Domain model § Location: `PLACE_ALERT`, `LOCATION_STATE`.
- STATUS.md; plan status.

## Stop and report if

- Changing `derive` would change any existing derived id.
- A lenient enum pattern cannot express the fallbacks above without breaking existing decoders.

## Handoff

Russian summary: общая часть геолокации готова — места, подписки, правила «кто кого видит», расчёт расстояний,
прибытия и ухода, остановок; всё покрыто тестами. Next M4-02.
