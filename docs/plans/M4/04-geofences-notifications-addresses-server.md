---
id: M4-04
status: ready
depends_on: [M4-03]
requirements: [LOC-003, LOC-012, LOC-014, LOC-021, LOC-022, LOC-024]
---
# M4-04: Geofences, location notifications and addresses on the server

## Goal

The server knows who is in which place and since when, tells subscribers when a member arrives at or leaves a place,
tells administrators when a kid's phone stops sharing and when it is back, and turns coordinates into short street
addresses and address searches through the configured Nominatim-compatible service — on demand, cached, rate-limited.

## Read first

- [Location design](../../architecture/location.md) § Server: Tables (V11), Geofences, notifications, addresses; §
  Contract pure logic (`Geofence`)
- [ADR-0026](../../adr/0026-addresses-through-a-server-geocoder.md)
- [Location spec](../../product/features/location.md) LOC-012, LOC-014, LOC-021, LOC-022, LOC-024, § Notifications,
  § Privacy
- Code (templates): `calendar/EventNotifier.kt` and `tasks/TaskNotifier.kt` (channel, `enqueue`, `start`,
  `awaitIdle`, recipient filters), `notifications/NotificationTexts.kt`, `notifications/WebPushSender.kt` (JDK
  `HttpClient` use, timeouts), `notifications/FakePushService.kt` (a fake HTTP server to copy for a fake Nominatim),
  `identity/FailedAttemptLimiter.kt`, the M4-02/M4-03 `location` package

## Scope

- `V11__location_presence.sql`: `place_presence`.
- `location/LocationGeofencing.kt` (`apply(tx, householdId, memberId, fixes): List<PlaceEvent>`), called from the upload
  (step 6); presence reset in `PlaceSyncHandler.update` when centre or radius change; `PlaceSyncHandler.tombstone`
  deletes the place's presence rows; `MemberLocation.placeId/placeSince` filled in `now`, live and watchers.
- `location/LocationNotifier.kt` with events `PlaceTransition`, `SharingOff`, `SharingBack`; hooks in the upload
  (transitions, OFF→SHARING), `PUT /location/me/sharing` (→ OFF_ON_PHONE, OFF→SHARING) and `onSessionEnded`.
- `NotificationTexts.placeArrived/placeLeft/sharingOff(reason)/sharingBack`.
- `location/AddressLookup.kt` (interface), `location/NominatimLookup.kt`, `location/AddressService.kt`,
  routes `GET /location/address`, `GET /location/search`; `LocationConfig.addresses` from the config.
- `deploy/.env.example`, `deploy/docker-compose.yml`: `LARES_GEOCODER_URL` as `${LARES_GEOCODER_URL-https://nominatim.openstreetmap.org}`
  (no colon: set-but-empty stays empty = off), `LARES_GEOCODER_EMAIL`.
- Install manual (en, ru): "Addresses" in the optional "Map" section: what is sent (rounded coordinates of shown
  positions, typed searches), to whom, how to turn it off (`LARES_GEOCODER_URL=`) or use a self-hosted service.

Out of scope: client (M4-06+), schedule-aware alerts (LOC-023).

## Decisions (do not revisit)

- Geofencing as the design: fixes in `recordedAt` order, skipped when `!Geofence.usable`; presence rows created lazily
  (`inside = null`) for every live place of the household; only changed rows are written. The place reset on a centre /
  radius change sets `inside = null, candidate = null` for all its rows.
- A transition's `at` is the confirmed `since` (first agreeing reading). Transitions with `at ≥ now − 15 min` enqueue
  `PlaceTransition`; older ones update presence only (spec assumption).
- Recipients and texts exactly as the design. `PlaceTransition` recipients are computed in the consumer inside
  `dbQuery`: `place_alert` rows (not deleted) with `member_id` = the member and the place in `place_ids`; subscriber live,
  has `location.view`, `LocationVisibilityService.level(subscriber → member) ≥ CURRENT`, not the member. `SharingOff` /
  `SharingBack`: the member lacks `location.pause_own`; recipients = live administrators except the member.
- `SharingOff` when the status changes from SHARING or PAUSED to OFF_ON_PHONE (one notice per change, not per repeated
  PUT with the same reason); `SharingBack` when OFF_ON_PHONE → SHARING (by PUT or by an upload).
- `entityType = "location"`, `entityId = memberId` for both types; times in the household zone `HH:mm`.
- Address lookup exactly as the design (parameters, label building, User-Agent, timeouts, caches, 1 request/s, 5 s wait
  limit, failures cached 10 min). `NominatimLookup` parses with `LaresJson` into `JsonObject`; unknown shapes → null.
- Address endpoints: `address` needs `location.view`; `search` needs `location.manage_places`; per-member windows 60 / 10
  per minute (`RATE_LIMITED`); `lat`/`lon` parse errors or out of range → `VALIDATION_FAILED`; `q` trimmed 3..200 else
  `VALIDATION_FAILED` `q: TOO_SHORT|TOO_LONG`. Geocoder off → `{}` / `{"results":[]}`.
- Language: `accept-language` = the caller's push registration language if any, else the household `defaultLanguage`.
- The server never calls the geocoder except from these two endpoints (no background lookups) — a test asserts the fake
  server got no request during uploads.

## Steps

1. Migration, geofencing, presence in `MemberLocation`.
2. Notifier, texts, hooks.
3. Address lookup, service, routes, config, deploy and manual.
4. Tests:
   - `GeofencingTest` (LOC-022): arrival needs two consecutive inside readings; the first reading after creating a place
     never notifies; edge-zone fixes keep the state; inaccurate fixes skipped; moving the place resets presence; deleting
     it deletes the rows; `now` shows `placeId`/`placeSince` (smallest radius when nested).
   - `LocationNotificationsTest` (LOC-021, LOC-012): subscriber gets «Аня · Школа №12» / «Прибытие в 08:52» (ru) and
     the en text; nothing for a place not in the row; nothing when the subscriber may no longer see the member (rules
     changed); nothing for an old backlog transition; muted `location.place` → nothing; kid sets OFF_ON_PHONE /
     LOCATION_DISABLED → both admins get «Геопозиция выключена» with the reason text, the kid does not; the same PUT again
     → no second notice (another reason updates `off_reason` silently); an upload afterwards → «Геопозиция снова
     передаётся»; an adult (has `pause_own`) going off → nothing; kid's sharing session logout → SIGNED_OUT notice;
     removing the kid as a member → no notice.
   - `NominatimLookupTest` (LOC-014, LOC-024): against a fake HTTP server — query parameters, User-Agent, email; label
     «Тверская улица, 7» from the recorded reverse response (put the JSON in test resources); road missing → suburb;
     search results with city; HTTP 500 / timeout → null.
   - `AddressServiceTest`: rounding shares cache entries; one request per second (two lookups for different keys at the
     same instant: the second waits); wait > 5 s → null; failures cached; geocoder off → null without requests.
   - `AddressRoutesTest`: permissions, rate limits, validation codes, no geocoder traffic caused by uploads.

## Acceptance

- `tools/dev/verify.sh :server:test --tests '*Location*' --tests '*Geofenc*' --tests '*Address*' --tests '*Nominatim*'`
  while iterating; full gate at the end. Tests never reach the internet.

## Docs to update

- api.md location section: address and search endpoints, notification types; NotificationDtos table in api.md
  § Notification endpoint details.
- Install manual (en, ru) "Map" section: addresses.
- Spec progress notes for the listed requirements; STATUS.md; plan status.

## Stop and report if

- The public Nominatim response shape differs from the design so that the label rules give empty labels for ordinary
  Russian street addresses (check with the recorded sample only; do not call the live service in tests).

## Handoff

Russian summary: сервер сам понимает, кто пришёл в «Дом» или «Школу» и ушёл, и присылает уведомления подписанным;
родителям приходит уведомление, если у ребёнка выключили геопозицию; адреса и поиск адреса работают через сервер с
кешем и ограничением частоты, администратор может их выключить. Next M4-05 (load test) or M4-06.
