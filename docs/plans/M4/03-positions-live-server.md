---
id: M4-03
status: ready
depends_on: [M4-02]
requirements: [LOC-001, LOC-003, LOC-004, LOC-005, LOC-010, LOC-011, LOC-012, LOC-015, LOC-019, LOC-027]
---
# M4-03: Positions, sharing state and the live channel on the server

## Goal

Phones can upload positions and set their sharing state; viewers get "now", day histories and live changes filtered by
the visibility rules; «Обновить» reaches the sharing phone by push and by the upload response; history is deleted after
the retention period. Geofences and notifications follow in M4-04.

## Read first

- [Location design](../../architecture/location.md) § Server: Tables (V10), Positions, state, live channel; § Contract
  REST DTOs
- [ADR-0024](../../adr/0024-positions-outside-the-sync-log.md)
- [api.md](../../architecture/api.md) § Conventions, § Errors; [sync.md](../../architecture/sync.md) § Realtime events
- Code (templates): `server/…/sync/SyncRoutes.kt` and `sync/SyncEventsService.kt` (authenticated WebSocket, ping),
  `notifications/NotificationService.kt` (`activeTargets`, `sendAll`, `tickle`), `notifications/WebPushSender.kt`,
  `notifications/NotificationRoutes.kt`, `identity/RequireSession.kt` (`memberPrincipal.sessionId`),
  `identity/AuthService.kt` (logout, session revoke), `identity/MemberRemoval.kt`, `tasks/TaskOverdueJob.kt` (job loop,
  `runOnce`), `tasks/TaskRepository.householdTimeZone`, `platform/Time.kt`, `identity/FailedAttemptLimiter.kt` (a
  sliding window to copy for per-member rate limits), the M4-02 `location` package; tests
  `server/src/test/…/notifications/FakePushService.kt`, `sync/…` WebSocket tests if any

## Scope

- `V10__location_positions.sql`: `position`, `location_state` (design § Tables).
- `location/PositionRepository.kt`, `location/LocationStateRepository.kt`, `location/LocationService.kt`,
  `location/LocationRoutes.kt` (all endpoints of the design table except address/search),
  `location/LocationLiveService.kt` (WS `/api/v1/location/live`), `location/LocationSessions.kt`
  (`onSessionEnded`, `onUserSessionsEnded`), `location/LocationRetentionJob.kt`.
- `NotificationService.sendToSession(sessionId, payload, ttl, urgency): PushSendResult?`.
- `AppConfig.location` with the four variables (design § Configuration — the geocoder URL is read without `value()`:
  unset → default, empty → off; `testDeps()` sets it to null); geocoder values are only read here and used in M4-04;
  `GET /location/config` (`addresses = false` until M4-04).
- Hooks (design § Sessions ending): `AuthService.logout`, `AccountService.revokeSession`, both revoke branches of
  `AuthService.refresh` → `onSessionEnded`; `MemberAdminService.resetPassword` → `onUserSessionsEnded`; each publishes
  `LocationHub.SessionEnded` after commit. `removeMember` does not call them; it deletes the member's `position` and
  `location_state` rows (no notice).
- `AppDeps`: services, job start next to the housekeeping when `backgroundJobs`.
- `deploy/.env.example` and `deploy/docker-compose.yml`: the map style variables (commented defaults; `${VAR:-default}`).
- testFixtures `LocationFixtures`: `upload(token, fixes)`, `setSharing(token, …)`, `fix(lat, lon, at, acc = 10f)`.

Out of scope: geofences, presence in `MemberLocation` (fields stay null), notifications, addresses (M4-04).

## Decisions (do not revisit)

- Endpoint behaviour exactly as the design table, § Effective state, § Concurrency and the numbered upload steps. Responses: upload 200 with
  `UploadPositionsResponse`; sharing 204; refresh 202 with no body; history 200.
- `PUT /location/me/sharing` SHARING from session B while A shares → B becomes the sharing session (A learns on its next
  upload). PAUSED without `location.pause_own` → `FORBIDDEN`. `pausedUntil` validated by `Validators.setSharing`.
- Upload drops invalid fixes silently (counts them as not accepted) unless the request itself is malformed (> 200 or
  empty → `VALIDATION_FAILED` `positions: OUT_OF_RANGE`).
- `last_*` columns hold the newest fix by `recorded_at`; while PAUSED nothing is stored, so `last` is the position before
  the pause (Q-025).
- `GET /now` returns members with level ≥ CURRENT, always including the caller (level HISTORY), members without a state
  row as `status = NEVER`. Members are live members of the household (tombstoned ones never). `serverTime` = now.
- History: day bounds are household-local midnight to midnight (`TaskRepository.householdTimeZone`); points ordered by
  `recorded_at`; `accuracy_m ≤ 200`; thinning: at most 3 000 points including the last (k = ceil((n − 1) / 2999), every
  k-th from the first, plus the last).
- Refresh: `NOT_FOUND` when level is NONE or the member is unknown; `CONFLICT` when status ≠ SHARING (paused, off,
  never); push only when `refresh_sent_at` is null or ≥ 60 s ago; a member may refresh themselves (allowed, harmless).
- Live channel: the design § Live channel. Implementation: one coroutine per connection collecting
  `merge(locationHub.events.filter { it.householdId == h }, changeBus.events.filter { it.householdId == h })`, subscribed
  before the first snapshot is built; `MemberChanged` → one `dbQuery` with the member's `MemberLocation` and a fresh
  level, sent only when ≥ CURRENT; other events → recompute levels (debounce 500 ms), and only if the levels map changed
  (or on `PlacesChanged`) send `gone` for members that dropped to NONE and a `snapshot`; `SessionEnded` of its session →
  close 4401; a 60 s check of session and member liveness. No client messages are read. `location.view` checked on connect (close with 4403 / `FORBIDDEN` otherwise); a viewer who loses the permission
  gets an empty snapshot on the next recompute.
- `LocationHub`: `MutableSharedFlow<LocationHubEvent>(extraBufferCapacity = 256, onBufferOverflow = DROP_OLDEST)`;
  publish only from `afterCommit`.
- `onSessionEnded(tx, sessionId)` / `onUserSessionsEnded(tx, userId)`: if a state row has `sharing_session_id` among the
  ended sessions → status OFF_ON_PHONE, reason SIGNED_OUT, `sharing_session_id = null`, `status_since = now`; publish
  `MemberChanged` and `SessionEnded` after commit.
- `LocationRetentionJob.runOnce(now)`: per household `location_history_days`; deletes positions in batches of 10 000
  until none and clears `last_*` of states older than the cutoff; `start` waits 2 min, then every hour.
- `refreshRequested` uses server times only (design upload step 7).
- Rate limits: refresh is naturally limited by the 60 s push rule; uploads are not rate-limited.
- No position, coordinate or address in any log line (conventions). Log counts only.

## Steps

1. Migration, repositories, config, `/location/config`.
2. Sharing state and upload.
3. Now, history, refresh with `sendToSession`, watchers.
4. Live channel and hub wiring.
5. Session hooks, member removal cleanup, retention job, deploy variables.
6. Tests (`server/src/test/…/location/`, names start with requirement ids):
   - `LocationUploadTest`: LOC-001 upload after SHARING stores fixes and updates `last`; before any SHARING →
     `sharingHere = false`, nothing stored; LOC-019 second session takes over, the first gets `sharingHere = false`;
     LOC-010 paused → dropped with `paused`/`pausedUntil`; expired pause counts as sharing; kid PAUSED → `FORBIDDEN`;
     LOC-027 a batch of 120 old (but < 24 h) fixes is stored, a re-upload is idempotent, a 25-h-old fix and a future fix
     are dropped; OFF_ON_PHONE + fix → SHARING; two concurrent uploads of one member both succeed without a key
     violation.
   - `LocationNowTest` (LOC-003, LOC-006): default family — adult sees everybody with HISTORY, kid sees adults with
     CURRENT and not the other kid, member without `location.view` → `FORBIDDEN`; NEVER members listed; paused member
     shows the last fix before the pause; an expired pause shows SHARING.
   - `LocationHistoryTest` (LOC-004): household-zone day bounds (a fix at 23:30 local belongs to that day); kid →
     adult history `FORBIDDEN`; kid → kid `NOT_FOUND`; a member of another household → `NOT_FOUND`; thinning of 6 000
     points → exactly 3 000 incl. the last; outside retention → empty.
   - `LocationRefreshTest` (LOC-011): refresh sends one `location_refresh` push to the sharing session only
     (`FakePushService`), a second within 60 s sends none; the next upload response says `refreshRequested` until a
     fix is received (a fix with a device time far in the past also clears it); paused → `CONFLICT`; invisible or another
     household → `NOT_FOUND`.
   - `LocationLiveTest` (LOC-003): snapshot on connect; an upload by a visible member produces `member`; an upload by an
     invisible member produces nothing; PUT rules removing visibility → `gone` then a snapshot without them; a shopping
     write sends nothing; logout of the viewer's session closes the socket.
   - `LocationSessionsTest` (LOC-012): logout, `revokeSession` and `resetPassword` of the sharing session →
     OFF_ON_PHONE / SIGNED_OUT; logout of another session changes nothing; member removal deletes positions and state
     without a state change.
   - `LocationRetentionTest` (LOC-005): positions older than the household's days are deleted, newer kept; another
     household with 90 days keeps its 60-day-old fixes; a state whose last fix is older than the cutoff loses `last`.
   - `LocationWatchersTest` (LOC-015): viewers list for a kid under the defaults = both adults (and admins); alerts
     listed only for live places and subscribers who still see the member; nothing from another household.

## Acceptance

- `tools/dev/verify.sh :server:test --tests '*Location*'` while iterating; full gate at the end.
- With the local server: `seed.py family`; `T=$(tools/dev/seed.py token)` (one session — `seed.py call` logs in anew on
  every call, so it cannot be used for the sharing session); `curl` with `Authorization: Bearer $T` and
  `X-Lares-Protocol: 1`: `PUT /api/v1/location/me/sharing {"status":"sharing"}`, then `POST /api/v1/location/positions`
  with one fix; then `seed.py call GET /location/now --as dima` shows it.

## Docs to update

- api.md "Location endpoint details (M4)": all endpoints and the live messages; sync.md § Realtime events: a pointer to
  the separate location channel.
- Location design "Implementation notes (M4-03)" for anything decided while implementing.
- Install manual (en, ru): optional section "Map" with the two style variables.
- Spec progress notes for the listed requirements (*Server: …*); STATUS.md; plan status.

## Stop and report if

- Ktor cannot serve a second authenticated WebSocket route next to `/sync/events` with the existing plugin setup.
- `activeTargets` cannot select one session's registration without changing the push registration model.

## Handoff

Russian summary: сервер принимает точки с телефона, знает, кто на паузе и у кого выключено, отдаёт «где сейчас» и
историю за день только тем, кому можно, присылает изменения сразу, передаёт телефону просьбу «Обновить» и удаляет
старую историю. Next M4-04.
