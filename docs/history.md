# Project history

Log of completed work, oldest first. One short entry per plan or work session.
Current state and next steps: [STATUS.md](STATUS.md).

- 2026-10-22 Kickoff with the customer: modules, priorities, license, distribution, docs language.
- 2026-10-22 Kickoff discussion, round 2: stack re-reviewed (Kotlin kept, ADR-0013), group-based permissions (ADR-0014),
  all open questions answered; access model clarified (administrators see all data).
- 2026-10-22 First commit `30e9207` (kickoff documentation).
- 2026-10-22 M0 planning: [sync protocol](architecture/sync.md), [auth design](architecture/auth.md),
  [API](architecture/api.md), verified [versions](plans/M0/versions.md), plans M0-01…M0-11.
- 2026-10-22 Plans reviewed by a fresh agent from the executor's point of view; 17 findings fixed (pull snapshot, sync epoch
  for restores, refresh grace window, lenient enums, shared write rules, safe tombstone purge, cross-household id checks,
  ADR-0015 for the M0 load test scope).
- 2026-10-22 M0-01: dev environment (JDK 21, Android SDK 37, AOSP emulator) and Gradle monorepo skeleton
  (`contract`, `server`, `client:shared`, `client:android`); `./gradlew check :client:android:assembleDebug` green,
  `/health/live` answers, emulator shows the placeholder screen; Spotless/ktlint and CI workflow added.
- 2026-10-22 M0-02: server foundation — env config, HikariCP + Flyway (`V1__foundation`: `household`,
  `household_sync_state`), Exposed DSL, `dbQuery`/`recordChange` with after-commit `HouseholdChangeBus`, JSON errors
  (`ApiException` → `ErrorCode`), protocol header check, request ids in logs (plain/JSON), `/health/live|ready`,
  `/api/v1/instance`; contract: `ErrorCode`/`FieldErrorCode` with lenient decoding, `InstanceInfo`, `LaresJson`.
  Test harness on Testcontainers PostgreSQL; dev database in `deploy/dev/docker-compose.yml`.
- 2026-10-22 M0-03: identity — permission catalogue, evaluator, validators, codes, entity data and auth DTOs in `contract/`;
  migration `V2__identity` (`user_account`, `person`, `member`, `group_entity` + permissions/memberships, `session`);
  Argon2id (PHC, checked against the reference implementation and OpenSSL), opaque tokens with refresh rotation, 60 s grace
  and reuse detection, `RequireSession` plugin, failed-attempt limiter, setup code in the log; endpoints setup, login,
  refresh, logout, me, password, sessions, delete account. 71 server tests; manual curl walkthrough done.
- 2026-10-23 Visual style chosen with the customer (two option rounds, then 10 screen mockups):
  [visual-style.md](design/visual-style.md), [ADR-0016](adr/0016-visual-design-system.md), mockups in
  [design/mockups/](design/mockups/README.md). New requirements FND-034 (personal color), FND-063 (Today screen),
  FND-064 (bottom bar, Q-013 answered), FND-065 (visual style). Not-yet-executed plans amended: M0-05 (`PaletteColor`
  shared by tags and persons, `PersonData.color`), M0-06 (color on create), M0-07 (theme, fonts, icon pipeline, Today,
  More, app icon), M0-08 (personal colors, entry from More). Mockups and bottom bar approved. File storage for photos:
  new milestone M5b after Budget ([files spec](product/features/files.md)).
- 2026-10-23 M0-04: household administration (server) — household settings, groups (built-in and last-admin rules,
  `AccessVersionService`), members created directly (login/password optional, linking a relative), password reset,
  removal (shared with account deletion), invites (create/list/revoke/preview/accept, single use under row lock),
  QR login codes; migration `V3__admin`; contract `api/admin` DTOs and `DeepLinks`. 99 server tests incl. permission
  matrix and cross-household isolation; manual curl walkthrough done.
- 2026-10-23 M0-05: sync server — contract sync DTOs, `TagData`, `PaletteColor`/`PaletteColors` (personal colors, FND-034),
  `PersonData.color`, tag/person validators, shared `WriteRules`; migration `V4__sync` (`tag`, `sync_mutation_log`,
  `person.color`); `SyncEntityHandler` registry (household, member, group read-only; person, tag writable); pull in one
  REPEATABLE READ snapshot with reset rules 1–4, push with idempotency log, strict field-level merge and per-mutation
  transactions, WebSocket events (hello/changed, 500 ms debounce), daily tombstone purge and log cleanup. Also: password
  changes now give the member a new seq (`hasPassword` is synced); persons referenced by invites are not purged.
  121 server tests (invariants 1–7, 9, 10 at server level); manual walkthrough on the real server incl. WebSocket.
- 2026-10-23 M0-06: client core in `client/shared` — SQLDelight `LaresDb` (typed tables, sync tables, `local_session`),
  entity adapters and materializer, `LocalWriter` with shared `WriteRules` and validators, Ktor API client for all M0
  endpoints with single-flight token refresh, `SyncEngine` (push → pull → full resync, backoff, realtime WebSocket,
  `SyncStatus`, rejections), repositories and writers for tags and persons, `SessionManager`, Koin modules; Android
  platform: Keystore-encrypted tokens in DataStore, OkHttp; JVM: in-memory. New module `integration-tests/` (server test
  harness moved to `server/src/testFixtures`): 15 multi-device tests with the real server and three client cores —
  invariants 1–8 and 10, offline work, realtime within 2 s, resets keeping the outbox, revocation, offline permissions,
  randomized convergence. 23 client unit tests. sync.md and ADR-0008 accepted.
- 2026-10-23 M0-07: Android app shell — design assets (`tools/design-assets/build.py`: Manrope 400–800, 61 Material
  Symbols icons at weight 250, outlined + filled, pinned to commits), theme with all visual-style tokens (light/dark,
  extra roles, palette tones, type scale, shapes, spacing), shared components; screens Server, Setup, Login (password,
  QR, invitation code), Invite accept, link handling, QR scanner (CameraX + ZXing), main scaffold with the filtered
  bottom bar, Today (empty state), section placeholders, More, sync indicator / sheet / rejection snackbar, Settings
  (profile name offline, change password, login QR with countdown, devices with revoke, theme, language, about and
  licenses, logout, delete account). Android shell: AppCompat activity with per-app language, deep links
  `lares://invite|login`, session restore on start, foreground toggle, connectivity trigger, debug-only cleartext to
  local hosts, adaptive icon. `SessionManager.deleteAccount`. 23 new client tests (ViewModels on a mock server, Compose UI
  tests incl. bottom-bar filtering, icon resources). Emulator walkthrough of all flows except live camera scanning.
- 2026-10-23 M0-08: Family management screens — More › Family / Tags open the Family screen with tabs Members,
  Relatives, Groups, Tags and Family settings (admins). Members: list with group chips and the Administrators badge,
  active invites with revoke, member detail (change groups, reset password, login QR for that member, remove), "Invite"
  → send an invitation (groups, 1/3/7 days, QR + share link) or create an account without password (→ login QR); both
  can link an existing relative (FND-032). Groups: editor with plain-language permissions by section (action ⇒ "See …",
  unchecking "See …" clears the section), members checklist, Administrators locked with last-member check, delete with
  confirmation. Relatives: list with "member" badge, person form (birthday pickers day/month/optional year, relation
  suggestions, color, contacts, notes), delete for non-members. Tags: list, archive section, create/edit sheet with
  palette, inline duplicate check, delete. Personal color in the own profile. Admin actions are online-only ("Needs
  internet connection") and request a sync afterwards; relatives and tags work offline. `LoginQrViewModel` takes an
  optional member id. 13 new client tests (ViewModels on a synced mock household, Compose UI for the group editor and
  members list) and 1 multi-device test (invite created by the admin, accepted on a new client, both see each other).
  Emulator walkthrough RU/EN, light/dark; fixed on the way: FAB shadow artifact, contact field focus, tag sheet behind
  the keyboard and a layout loop in it.
- 2026-10-23 Agent tooling to cut token use (analysis of 14 sessions: 55–80% of the cost is re-reading the growing
  context; the emulator walkthrough at the end of M0-07/M0-08 made up 56–72% of those sessions). Added `tools/dev/`
  (`verify.sh` short-output build/test, `server.sh` local server with its own database, `seed.py` test family and API
  calls, `emu.sh` emulator lifecycle, `ui.sh` tap-by-text and downscaled screenshots), project skills `plan-run`,
  `plan-finish`, `build-check`, `emulator` and the `emulator-tester` agent (walkthroughs in their own context).
  STATUS.md shortened; the log of past work moved here.
- 2026-10-23 M0-09 Notifications. Contract: `NotificationType` (stable wire names, channel per type), `PushPayload`,
  push registration, test result and notification settings DTOs. Server: migration V5 (`push_registration` per session,
  `instance_secret` with the VAPID keys, `notification_setting`), `WebPushSender` on `com.interaso:webpush` (own HTTP
  call so any 2xx counts; 404/410 deletes the registration), SSRF guard with `LARES_PUSH_ALLOWED_HOSTS`,
  `NotificationService` (mutes respected, revoked sessions lose their registration), sync tickles throttled to one per
  30 s per household with a trailing send, endpoints `/me/push`, `/me/push/test`, `/me/notification-settings`.
  Client: local `reminder`, `notification_mute`, `push_endpoint` tables (SQLDelight migration 1 → 2), `Reminders`,
  `PushMessageHandler`, `PushEndpoints` (re-upload when offline or the UI language changed), `NotificationsCoordinator`
  (login/logout lifecycle); Android: channels, `AlarmManager` reminders with boot/update re-registration, `SyncWorker`
  (periodic 15 min + expedited on tickles), UnifiedPush `PushService` and distributor picker, post-login permission
  dialog, Settings › Notifications. Tests: server +11 (fake push service decrypts RFC 8291 messages), client +5,
  contract +5. Emulator (API 37): permission flow, offline test reminder, WorkManager job and its cancellation on
  logout, exact-alarm settings, en/ru; four small UI fixes after it. End-to-end UnifiedPush needs a phone with ntfy.
- 2026-10-23 **M0-10 Deploy and operations.** `server/Dockerfile` (temurin 21 JRE noble, copies `installDist`, non-root
  uid 10001, small-heap `JAVA_OPTS`, curl healthcheck); `deploy/docker-compose.yml` (postgres 18, server 384 MiB,
  Caddy 64 MiB with `Caddyfile`/`Caddyfile.push`, ntfy behind profile `push`, deny-all + anonymous `up*`), documented
  `.env.example`, `docker-compose.build.yml` override for local images; `backup.sh` (pg_dump -Fc, keeps 14),
  `restore.sh` (validates dump, recreates DB, new sync epoch), `smoke.sh` (HTTPS on localhost via Caddy's local CA,
  setup, backup, tag via sync, ntfy access check, restore, tag gone + epoch changed). Env-based Android release
  signing; `release.yml` (multi-arch GHCR image, APK on a GitHub Release, tag/version check) and CI job
  `deploy-smoke`; actionlint and shellcheck clean. Smoke passes locally; server 160 MiB of 384 MiB after start.
  User manual `install-server.md` (en/ru).
- 2026-10-23 **M0-11 Load simulation.** New module `tools/loadsim` (Kotlin CLI, Ktor CIO client, contract DTOs):
  sets up a household through the public API, 10 devices (5 members × 2, login codes), WebSocket events, pushes every
  60 s ± 20 %, pulls on events, token refresh, admin REST calls, burst phase; `latency.csv`, `summary.json`, fails above
  1 % errors. `deploy/scripts/loadtest.sh` runs the production stack, cold start, memory sampling (`docker stats`,
  `/proc/1/status`), summary. 30-minute baseline with M0-10 defaults: RSS max 260 MiB (over 256). Native memory
  tracking showed an oversized heap and code cache; final `-Xmx128m -XX:ReservedCodeCacheSize=64m`,
  `MALLOC_ARENA_MAX=2`: RSS avg 232 / max 237 MiB, p95 52 ms, cold start 1.9–2.7 s, stack ~320 MiB; server
  `mem_limit` 384m → 300m. Results: `docs/architecture/perf/M0-baseline.md`. loadsim tests: 5.
- 2026-10-23 **Rename to Lares** (Q-001, customer's choice). Working name FamilyHub replaced everywhere: Kotlin packages
  `app.familyhub` → `app.lares` (also Android application id), classes `FamilyHub*` → `Lares*`, env vars
  `FAMILYHUB_*` → `LARES_*`, database, volume and image names `familyhub*` → `lares*`, protocol header
  `Lares-Protocol`, UI strings. Image default `ghcr.io/hiraise/lares-server`. Remote: `github.com/hiraise/lares`.
