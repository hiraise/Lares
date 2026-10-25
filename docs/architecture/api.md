---
status: accepted
updated: 2026-10-22
---
# HTTP API conventions and M0 endpoints

All request/response types live in `contract/` as `@Serializable` classes; server and client never define their own copies.

## Conventions

- Base path `/api/v1`. JSON only, UTF-8. Field names camelCase.
- Header `X-Lares-Protocol: 1` on every request (see [sync.md § versioning](sync.md#versioning)).
- Secrets (setup code, invite code, login code, passwords, tokens) travel only in request bodies or headers, never in URLs.
- Every id referenced in a request body or path (group, member, person, invite ids) must belong to the caller's household;
  otherwise the server answers `NOT_FOUND` (never `FORBIDDEN`, to avoid revealing existence).
- IDs: UUID strings (UUIDv7 for new entities). Instants: ISO-8601 UTC (`2026-10-22T10:15:00Z`).
  Dates: `YYYY-MM-DD`. Money (later): integer minor units + ISO 4217 code.
- Unknown JSON fields are ignored by both sides (`ignoreUnknownKeys = true`); nulls are omitted (`explicitNulls = false`).
- Writes to syncable entities happen either through sync mutations or through the admin endpoints below; both paths
  allocate a household `seq` ([sync.md](sync.md)).
- Permission checks happen in server services using the shared evaluator from `contract/` (ADR-0014).

## Errors

Non-2xx responses carry:

```json
{ "error": { "code": "VALIDATION_FAILED", "message": "Name must not be empty", "fields": { "name": "REQUIRED" } } }
```

`code` is a stable `ErrorCode` enum in `contract/`; the client maps it to localized text. `message` is English,
for logs only. `fields` (optional) maps field names to field error codes (`REQUIRED`, `TOO_LONG`, `TOO_SHORT`,
`INVALID_FORMAT`, `OUT_OF_RANGE`, `TAKEN`). Nested fields use dotted names (`device.name`).

| Code | HTTP | When |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Invalid input |
| `PROTOCOL_UNSUPPORTED` | 426 | Client protocol outside the server range |
| `UNAUTHENTICATED` | 401 | No or unknown token |
| `SESSION_EXPIRED` | 401 | Access token expired — refresh |
| `SESSION_REVOKED` | 401 | Session revoked or member removed — wipe and log out |
| `INVALID_CREDENTIALS` | 401 | Wrong login/password |
| `FORBIDDEN` | 403 | Missing permission |
| `NOT_FOUND` | 404 | Resource not found (or not visible) |
| `LOGIN_TAKEN` | 409 | Login already used on this instance |
| `CONFLICT` | 409 | Generic conflict |
| `LAST_ADMIN` | 409 | Operation would leave no administrator |
| `BUILTIN_GROUP` | 409 | Attempt to delete/rename "Administrators" or change its permissions |
| `SETUP_ALREADY_DONE` | 409 | Setup called twice |
| `SETUP_CODE_INVALID` | 403 | Wrong setup code |
| `INVITE_INVALID` | 404 | Invite unknown, used or expired |
| `LOGIN_CODE_INVALID` | 404 | Login code unknown, used or expired |
| `RATE_LIMITED` | 429 | Too many attempts |
| `INTERNAL` | 500 | Unexpected error |

Sync mutation rejections use the same codes inside the push response, plus `ENTITY_NOT_FOUND`, `ENTITY_DELETED`,
`MUTATION_EXPIRED`, `TAG_NAME_TAKEN`, `PERSON_IS_MEMBER`.

Enum values unknown to a client (newer server) decode to a fallback (`ErrorCode.UNKNOWN`) or are dropped from sets.

## Endpoints (M0)

Public:

| Method | Path | Purpose | Spec |
| --- | --- | --- | --- |
| GET | `/health/live`, `/health/ready` | Liveness; readiness checks DB | FND-070 |
| GET | `/api/v1/instance` | `{initialized, serverVersion, protocol: {min, max}}` | FND-001 |
| POST | `/api/v1/setup` | First-run setup → tokens | FND-001 |
| POST | `/api/v1/auth/login` | Login + password → tokens | FND-010 |
| POST | `/api/v1/auth/login-code` | QR login code → tokens | FND-014 |
| POST | `/api/v1/auth/refresh` | Rotate tokens | FND-011 |
| POST | `/api/v1/invites/preview` | Invite preview; code in body | FND-024 |
| POST | `/api/v1/invites/accept` | Join household → tokens; code in body | FND-024 |

Authenticated, any member:

| Method | Path | Purpose | Spec |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/logout` | Revoke current session | FND-011 |
| GET | `/api/v1/me` | User, member, household summary, effective permissions | FND-020 |
| POST | `/api/v1/me/password` | Change own password | FND-010 |
| DELETE | `/api/v1/me` | Delete own account; body `{password}` optional for users without a password | FND-013 |
| GET | `/api/v1/me/sessions` | List own sessions (JSON array of `SessionInfo`) | FND-011 |
| DELETE | `/api/v1/me/sessions/{id}` | Revoke a session | FND-011 |
| POST | `/api/v1/login-codes` | Create a login code (self, or any member with `household.manage`) | FND-014 |
| PUT | `/api/v1/me/push` | Register push endpoint for this session | FND-051 |
| DELETE | `/api/v1/me/push` | Unregister push | FND-051 |
| POST | `/api/v1/me/push/test` | Send a test notification to this session | FND-051 |
| GET | `/api/v1/me/notification-settings` | Current muted notification types and birthday lead time | FND-052, CAL-016 |
| PUT | `/api/v1/me/notification-settings` | Set muted notification types and birthday lead time | FND-052, CAL-016 |
| GET | `/api/v1/sync/changes` | Pull | FND-041 |
| POST | `/api/v1/sync/mutations` | Push | FND-041 |
| GET (WS) | `/api/v1/sync/events` | Realtime change events | FND-042 |

Authenticated, requires `household.manage`:

| Method | Path | Purpose | Spec |
| --- | --- | --- | --- |
| PATCH | `/api/v1/household` | Name, time zone, currency, default language, location history days | FND-003, LOC-005 |
| POST | `/api/v1/members` | Create member directly (no password → QR login) | FND-022 |
| DELETE | `/api/v1/members/{id}` | Remove member | FND-025 |
| POST | `/api/v1/members/{id}/password` | Reset member password | FND-012 |
| PUT | `/api/v1/members/{id}/groups` | Set member's groups | FND-022 |
| POST | `/api/v1/groups` | Create group | FND-022 |
| PATCH | `/api/v1/groups/{id}` | Rename, set permissions, set members | FND-022 |
| DELETE | `/api/v1/groups/{id}` | Delete group | FND-022 |
| POST | `/api/v1/invites` | Create invite | FND-024 |
| GET | `/api/v1/invites` | List active invites | FND-024 |
| DELETE | `/api/v1/invites/{id}` | Revoke invite | FND-024 |
| GET | `/api/v1/location/rules` | Location visibility rules | LOC-006 |
| PUT | `/api/v1/location/rules` | Replace all location visibility rules | LOC-006 |

Persons and tags have no REST endpoints: they are written through sync mutations only.

### Administration endpoint details (M0-04)

DTOs live in `contract/…/api/admin/AdminDtos.kt`; QR/link payloads are built and parsed by `DeepLinks` in `contract/`.

- Status codes: creating endpoints (`POST /members`, `/groups`, `/invites`, `/login-codes`) answer **201** with the created
  ids; updates and deletions answer **204**; `GET /invites` answers a JSON array of `InviteInfo`; invite accept and
  login-code exchange answer **200** with `AuthResult`, like login.
- `PATCH` requests change only the fields that are present; `permissions` and `memberIds` of a group replace the whole set.
  Writing the current values again changes nothing (no new seq).
- Returned `code` values are normalized (no dash); clients display them with `Codes.format`.
- Malformed ids (not a UUID) answer `NOT_FOUND`, like ids of other households.
- `personId` (create member, create invite): unknown or another household's person → `NOT_FOUND`; deleted person →
  `VALIDATION_FAILED` with `personId: INVALID_FORMAT`; person already linked to a member → `personId: TAKEN`.
- Taken logins answer `LOGIN_TAKEN` with `fields.login = TAKEN`.
- `POST /login-codes` with another member's id checks `household.manage` first (403 without it, even for unknown ids),
  then the id (404).

### Location endpoint details (M4)

DTOs live in `contract/…/api/location/LocationDtos.kt` (architecture/location.md, ADR-0025).

- `PATCH /household` accepts `locationHistoryDays` (LOC-005, M4-02): one of 7, 14, 30, 60, 90 (default 30), else
  `VALIDATION_FAILED` with `locationHistoryDays: OUT_OF_RANGE`. The value is part of the synced `household` row.
- `GET /location/rules` → **200** `LocationRules {rules: [LocationRule {id, subject, target, level}]}` in the saved
  order. A party is `{kind: "everyone"|"group"|"member", id?}`; levels are `current` and `history`.
- `PUT /location/rules` body `PutLocationRulesRequest {rules: [LocationRuleInput]}` replaces all rules (at most 100) and
  answers **204**; every PUT gives the rules new ids. Shape errors (`Validators.locationRules`) and group or member ids
  that are not live in the household answer `VALIDATION_FAILED` with `rules.N.subject` / `rules.N.target` (and
  `rules.N.level` for `none`) `INVALID_FORMAT`; nothing is stored then. Rule changes record no sync change.
- New households get the default rules (Adults see everybody with history, Kids see Adults now). Deleting a group or
  removing a member deletes the rules naming it.

Positions, sharing state and the live channel (M4-03, ADR-0024; behaviour in
[location.md § Positions, state, live channel](location.md#positions-state-live-channel-m4-03)). All need a session.

- `PUT /location/me/sharing` body `SetSharingRequest {status, pausedUntil?, reason?}` → **204**. `sharing` makes the
  calling session the sharing one (another phone of the member learns it on its next upload); `paused` needs
  `location.pause_own` (`FORBIDDEN`), `pausedUntil` in the future and at most 7 days ahead; `off_on_phone` needs a
  `reason` and changes something only when sent by the sharing session. `never`, a misplaced `pausedUntil` or
  `reason` → `VALIDATION_FAILED` (`Validators.setSharing`).
- `POST /location/positions` body `UploadPositionsRequest {positions: [PositionFix]}` (1…200, any order, else
  `VALIDATION_FAILED` `positions: OUT_OF_RANGE`) → **200** `UploadPositionsResponse {accepted, sharingHere, paused,
  pausedUntil?, refreshRequested}`. `accepted` counts fixes stored by this request; invalid fixes, fixes older than
  24 h or more than 5 min in the future and fixes already stored (same member and `recordedAt`) are skipped silently.
  `sharingHere = false`: this session does not share (stop). `paused`: nothing was stored. `refreshRequested`:
  somebody pressed «Обновить» and this request stored no fix.
- `GET /location/now` (`location.view`) → **200** `LocationNow {members: [MemberLocation], serverTime}`: every live member
  the caller sees (level `current` or `history`), the caller included; members who never shared have status `never`.
- `GET /location/history?memberId=…&date=YYYY-MM-DD` (`location.view`) → **200** `LocationHistory {memberId, date,
  points: [TrackPoint]}`: that household-local day, oldest first, accuracy ≤ 200 m, at most 3 000 points (first and
  last kept), empty outside the retention period. Member not visible, unknown or of another household → `NOT_FOUND`;
  visible only now → `FORBIDDEN`; bad date → `VALIDATION_FAILED`.
- `POST /location/members/{id}/refresh` (`location.view`) → **202**, no body. Not visible → `NOT_FOUND`; not sharing
  (paused, off, never) → `CONFLICT`. Sends a data-only push `{"kind":"location_refresh"}` to the sharing session at most
  once per 60 s; the request also rides on that phone's next upload response.
- `GET /location/me/watchers` → **200** `LocationWatchers {viewers: [Watcher {memberId, level}], alerts: [AlertWatcher
  {memberId, placeIds}]}`: who sees the caller (the caller left out) and whose arrive / leave alerts about the caller
  name live places, only for subscribers who still see the caller.
- `GET /location/config` → **200** `LocationConfig {mapStyleUrl, mapDarkStyleUrl, addresses, historyDays}`
  (`addresses` is false until M4-04).
- WebSocket `GET /location/live` (bearer token on the upgrade request; `location.view`, else the socket closes with code
  4403). Server → client JSON `LiveMessage`: `{"type":"snapshot","now":LocationNow}` on connect and after visibility or
  place changes; `{"type":"member","member":MemberLocation}` when a visible member's position or state changes;
  `{"type":"gone","memberId":…}` when a member is no longer visible (a snapshot follows). Clients send nothing; ping
  every 30 s. Code 4401 closes it when the session ends or the member is removed.

### Notification endpoint details (M0-09)

DTOs live in `contract/…/api/notifications/NotificationDtos.kt` (FND-051, FND-052; plan M0-09).

- `PUT /me/push` body `PushRegistrationRequest {endpoint, p256dh, auth, language}`: the UnifiedPush endpoint of the
  calling **session** (one per session; a new PUT replaces it) → **204**. `p256dh` (65-byte uncompressed P-256 key) and
  `auth` (16 bytes) are base64url; encryption is mandatory. `language` is the device UI language (`en`/`ru`, other
  values fall back to `en`); the server renders notification texts in it. `VALIDATION_FAILED` with field errors for a
  bad key, an endpoint longer than 2048 characters, or an endpoint that is not http(s) or whose host resolves to a
  non-global address (loopback, RFC 1918, RFC 4193, link-local, CGNAT) unless the host is listed in
  `LARES_PUSH_ALLOWED_HOSTS` (comma-separated). The same check runs again before every send.
- `DELETE /me/push` → **204**. Logout and every session revocation also delete the session's registration.
- `POST /me/push/test` → **200** `PushTestResponse {result}`: `sent`, `muted` (the member muted `system.test`),
  `not_registered`, `expired` (the push service answered 404/410; the registration was deleted), `failed`.
- `GET /me/notification-settings` → `NotificationSettings {muted: [type…], birthdayDaysBefore}`; `PUT` replaces the muted
  set, stores `birthdayDaysBefore` and answers the stored settings. Types are `NotificationType` wire names
  (`system.test`, `task.assigned`, `task.overdue`, `task.completed`, …); unknown names are dropped.
  `birthdayDaysBefore` (CAL-016, M3-02): one of 0, 1, 3, 7, 14, default 3; another value → `VALIDATION_FAILED` with
  `birthdayDaysBefore: OUT_OF_RANGE` and nothing stored.
- Sending (server): Web Push (RFC 8030) with `aes128gcm` (RFC 8291) and VAPID (RFC 8292). The VAPID key pair is generated
  on first use and kept in the `instance_secret` table. `TTL` 86400 s and `Urgency: high` for notifications, `TTL` 600 s
  for sync tickles. 404/410 deletes the registration; other failures are logged and dropped (no retry queue).
- Payload (cleartext ≤ 2 KB): `PushPayload {kind: "sync" | "notification", seq?, type?, title?, body?, entityType?, entityId?}`.
  Muted notification types are not sent at all; sync tickles cannot be muted.
- Task notifications (M1-03), `entityType = "task"`, `entityId` = the task id; triggers and texts in
  [tasks design § Notifications](tasks.md#notifications):
  `task.assigned` — a task create/update adds assignees (to the added members, not the writer);
  `task.completed` — the first non-skipped completion of an occurrence by someone else, if the creator opted in;
  `task.overdue` — the server's overdue job (to the creator). Recipients need `tasks.view`. `task.reminder` is local
  only (never pushed).
