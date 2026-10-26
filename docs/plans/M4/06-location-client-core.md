---
id: M4-06
status: done
depends_on: [M4-04]
requirements: [LOC-003, LOC-004, LOC-007, LOC-010, LOC-011, LOC-013, LOC-014, LOC-019, LOC-020, LOC-021, LOC-027]
---
# M4-06: Location in the client core — places, live map data, sharing controller, queue, tracking policy

## Goal

The shared client core can do everything location needs without Android specifics: places and alert subscriptions sync
and are written offline; the map state comes live from the server with polling and offline fallbacks; the phone's own
sharing (enable, pause, resume, report off) is controlled and remembered; fixes are queued and uploaded; the pure
tracking policy decides moving / still and what to record. Proven by unit and multi-device tests. Dev tools can seed
positions and move the emulator.

## Read first

- [Location design](../../architecture/location.md) § Client (Local tables, Core, Tracking policy), § Testing aids
- [ADR-0024](../../adr/0024-positions-outside-the-sync-log.md), [ADR-0028](../../adr/0028-battery-aware-tracking-service.md)
- [conventions](../../process/conventions.md) § Client
- Code (templates): `client/shared/…/sync/EntityAdapter.kt`, `sync/Adapters.kt` (`defaultEntityAdapters`),
  `sync/SyncState.kt` (`DATA_SCHEMA_VERSION`), `sqldelight/…/event_reminder.sq`, `sqldelight/migrations/4.sqm`,
  `db/LocalDb.kt` (`clearAll`), `data/calendar/CalendarWriter.kt` (derived rows, changed fields only),
  `data/calendar/CalendarRepository.kt` (inputs, ticks), `data/Ticks.kt`, `api/LaresApi.kt` (`authed`, `events()`
  WebSocket), `notifications/NotificationSettingsRepository` (REST + local mirror), `notifications/NotificationPlatform.kt`
  (platform interfaces with no-op defaults), `di/Modules.kt` (`coreModule`); tests `jvmTest/…/data/calendar/*`,
  `db/MigrationTest.kt`, `api/LaresApiTest.kt`; `integration-tests/…/Harness.kt`, `CalendarSyncTest.kt`;
  `tools/dev/seed.py` (`calendar()`), `tools/dev/emu.sh`, `.claude/skills/emulator/SKILL.md`

## Scope

- SQLDelight: `place.sq`, `place_alert.sq`, `position_outbox.sq`, `location_pref.sq`, `location_snapshot.sq`;
  `migrations/5.sqm` (schema 5 → 6); `DATA_SCHEMA_VERSION = 5`; `LocalDb.clearAll()`; `MigrationTest` table list.
- `data/location/`: `PlaceAdapter`, `PlaceAlertAdapter` (registered), `PlacesRepository`, `PlacesWriter`,
  `LocationRepository`, `LocationModels.kt` (`MapState`, `MemberOnMap`, `Connection`, `MySharing`, `DayHistory`),
  `LocationTexts.kt` (pure texts and classes), `SharingController`, `PositionQueue`, `PositionUploader`,
  `TrackingPolicy`, `LocationPlatform.kt` (`LocationTracking`, `LocationDevice`, `DeviceLocationStatus`, no-op
  implementations).
- `LaresApi`: the location calls of the design incl. `locationLive(): Flow<LiveMessage>` (same token handling as
  `events()`).
- `PushMessageHandler`: `KIND_LOCATION_REFRESH` → `SharingController.onRefreshRequest()`.
- Koin `coreModule()` bindings; `getOrNull` defaults for the platform interfaces (no-op).
- `tools/dev/seed.py location`, `tools/dev/emu.sh geo LAT LON` and `emu.sh route home-school`; skill command list.

Out of scope: the Android service and permissions (M4-07), all UI (M4-07…M4-10).

## Decisions (do not revisit)

- Adapters mirror the M4-02 handlers (same `PlaceRules`, validators, immutable fields, derived-id check, client delete
  of `place_alert` refused).
- `PlacesWriter.setAlert(memberId, placeIds)` writes the derived row with the full set; only when it differs.
- `LocationRepository.observeNow()`: a cold `Flow` shared with `shareIn(appScope, WhileSubscribed(5 s), 1)`.
  Connection: LIVE while the socket is up; POLLING after a socket failure (poll `GET /now` every 60 s, retry the socket
  with backoff 2 s → 1 min); OFFLINE when `SyncStatus` is offline (show the cached `location_snapshot` row `now`).
  Snapshot messages replace the member map; `member` messages replace one member; `gone` removes one.
- `MemberOnMap.stale` = status SHARING and `last.recordedAt < now − 30 min`; `moving` = `last.speedMps ≥ 2` and age
  < 5 min. Recomputed on minute ticks.
- `LocationTexts.status(member, placeName, address, now, zone, lang)` gives the status line of the spec (LOC-003,
  LOC-010, LOC-012, LOC-013, LOC-014): order of checks — NEVER; PAUSED («На паузе до 15:30» / «На паузе»); OFF_ON_PHONE
  (reason text «Геопозиция выключена на телефоне», «Нет доступа к геопозиции», «Передача остановлена», «Выход из
  приложения» + « · последнее место в 14:20»); stale «Нет данных с 18:05»; in place «Дома · с 15:10»; moving «В пути ·
  сейчас»; else «{address | "2 км от Дома" | "Без адреса"} · {age}». Texts come through a `LocationTextResources`
  interface (English fallback in the core, resources in `uiModule()` like `CalendarReminderTexts`).
- `address(lat, lon)` is only called by UI for members not in a place and not moving; cache key = coordinates rounded
  to 4 decimals; null when `config().addresses` is false.
- `SharingController` exactly as the design; «До утра» = the next 08:00 in the household zone after now. Pending
  sharing requests are kept in `location_pref` (`pending` JSON) and sent by `PositionUploader.flush()` before positions.
- `PositionUploader.flush()`: sends at most 5 batches per call; on `sharingHere = false` → `controller.disable()` and
  stops; HTTP/network errors keep the queue and return false.
- `TrackingPolicy` exactly as the design table; time comes from the injected clock; pure and fully unit-tested.
- `seed.py location`: uses the existing family; creates places «Дом» (55.7963, 37.5378, 120 m, home) and «Школа №12»
  (55.8001, 37.5329, 150 m, school) by sync push as the admin; for each other member gets a session — «Дмитрий
  Петров» by password (`dima`), the kids «Аня» and «Тимур» by a login code (`POST /login-codes` as the admin, then the
  public login-code exchange, device "seed script"); tokens kept in `seed.json` — then `PUT sharing` and uploads: Аня —
  today 07:00–08:10 at home, a walk, 08:25–14:40 at school, back home from 15:00; Тимур — at home since 15:10; Дмитрий —
  a position 2 km away 1 min ago. Ольга (admin, the emulator user) gets nothing: her phone shares itself. Idempotent like
  the other subcommands. `seed.py move NAME LAT LON` uploads one fix now for that member.
- `emu.sh geo LAT LON` → `adb emu geo fix LON LAT`; `emu.sh route home-school` replays 40 points from «Дом» to «Школа
  №12» one per 5 s, with a velocity when the emulator console accepts it (`geo fix LON LAT ALT SATELLITES VELOCITY` —
  check `adb emu help geo`; without it positions only, and note it in the skill).
- Seed sessions: access tokens live 1 h and `call --as` logs in by password, so `seed.json` keeps `{accessToken,
  refreshToken}` per seeded member (Дмитрий Петров, Аня, Тимур); a helper `session(name)` refreshes through `POST
  /auth/refresh` on 401 and stores the rotated pair. `call --as NAME` (also `--as Аня`) and `move` use the stored session
  for seeded names — the same session that set SHARING, so uploads and OFF_ON_PHONE are accepted. `olga` keeps the
  password login. Ольга's member id (`GET /me`) is stored in `seed.json` (`olga_id`) for plans and the tester.
- Seed times are relative to now in the household zone (fixed clock times would lie in the future before 15:10):
  Аня at home now − 9 h … now − 7 h 50 min, a walk, at school now − 7 h 35 min … now − 1 h 20 min, a walk, at home from
  now − 1 h; Тимур at home since now − 40 min; Дмитрий 2 km away 1 min ago.
- `SharingController` takes `device: LocationDevice`, `poster: NotificationPoster`, `texts: LocationTextResources` and the
  household repository (design § Sharing on this phone); `onRefreshRequest()` is the only path from a push or an upload
  response to `tracking.freshFix()` and does nothing unless sharing is enabled here and not paused locally.
- `NotificationsCoordinator` gets `sharingController: SharingController? = null` as its last parameter (tests build it
  directly).
- `LocationTexts` / `LocationTextResources` exactly as the design (no language parameter): `EnglishLocationTexts` in
  `coreModule()`, `LocationResourceTexts` in `ui/common/` bound in `uiModule()` with `loc_status_*` strings en/ru (this
  plan adds them — they are the only UI-module code here). `LocationTextsTest` checks ru through `LocationResourceTexts`.
- `LocationRepository` constructor with `live` and `LocationTimings` (design); `ticks` is `minuteTicks(clock)` by
  default, tests pass a flow. The snapshot cache follows the design's privacy rules (`gone`, `FORBIDDEN`, lost
  `location.view`).
- `TrackingPolicy` starts in MOVING (also after a resume).

## Steps

1. Tables, migration, adapters, places repository and writer.
2. API calls, `LocationRepository` with live / polling / offline, texts.
3. `PositionQueue`, `PositionUploader`, `SharingController`, push kind.
4. `TrackingPolicy`.
5. Dev tools (`seed.py location`, `seed.py move`, `emu.sh geo|route`, skill list).
6. Tests (names start with requirement ids):
   - `PlacesWriterTest`, `PlaceAdaptersTest` (LOC-020, LOC-021): offline create / edit / delete; kid refused locally;
     alert derived id, own rows only.
   - `LocationRepositoryTest` (LOC-003, LOC-013): MockEngine + fake live flow — snapshot, member update, gone; socket
     failure → polling; offline → cached snapshot; stale after 30 min by ticks; names and colors joined; `gone` removes
     the member from the cache; `FORBIDDEN` clears it.
   - `LocationTextsTest`: every status line branch in ru and en; age texts.
   - `SharingControllerTest` (LOC-010, LOC-019): enable online / refused offline; pause choices incl. «До утра» across
     midnight; pending PUT sent before positions; expired pause resumes; `sharingHere = false` disables and posts the
     notice; `refreshRequested` → `freshFix`, but not while paused locally nor when not enabled here.
   - `PositionQueueTest`, `PositionUploaderTest` (LOC-027): order, batches of 200, trim > 24 h and > 5 000, failure keeps
     the queue.
   - `TrackingPolicyTest` (LOC-007): MOVING records by 30 s / 50 m; inaccurate fixes ignored; STILL after 3 min within
     75 m; STILL → MOVING by motion and by a 150 m fix; requests per SDK level and sensor presence; upload decisions.
   - `integration-tests/…/LocationSyncTest.kt`: Anna (admin) and Carol (kid) — places sync to both; Boris uploads via
     `PositionUploader` and Anna's `observeNow()` gets the update live; Carol sees Boris (adult, CURRENT) but her
     `history(Boris)` fails with `FORBIDDEN`; a second client of Boris enabling sharing makes the first disable itself.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest --tests '*Location*' --tests '*Place*' --tests '*Tracking*'` and
  `tools/dev/verify.sh :integration-tests:test --tests '*Location*'` while iterating; full gate at the end.
- `tools/dev/server.sh reset && tools/dev/seed.py family && tools/dev/seed.py location` runs cleanly and
  `seed.py call GET /location/now` lists the members with positions.

## Docs to update

- Location design "Implementation notes (M4-06)"; `.claude/skills/emulator/SKILL.md` commands; dev-setup if `emu.sh`
  usage changed.
- Spec progress notes (*Client core: …*); STATUS.md; plan status.

## Stop and report if

- The live WebSocket cannot share the token refresh path of `events()` without changing `LaresApi` structure.
- The emulator's `geo fix` does not reach the platform `fused` provider (then the Android plan must use `gps` on the
  emulator — note it for M4-07).

## Handoff

Russian summary: в приложении появилась основа геолокации — места и подписки работают и без сети, карта получает данные
сразу при изменениях, телефон умеет копить точки без связи и отправлять их потом, паузу и включение передачи
приложение помнит; добавлены команды для тестовых точек и движения эмулятора. Next M4-07.
