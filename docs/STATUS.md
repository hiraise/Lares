# Project status

Updated: 2026-10-23

## Phase

**M0 Foundation — implementation in progress: M0-01…M0-07 done.**
Detailed plans for implementation sessions are in [docs/plans/](plans/README.md).

## Active milestone

[M0 Foundation](product/roadmap.md#m0-foundation). Specs `foundation` and `tags` approved.

## Done

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
  `/api/v1/instance`; contract: `ErrorCode`/`FieldErrorCode` with lenient decoding, `InstanceInfo`, `FamilyHubJson`.
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
- 2026-10-23 M0-06: client core in `client/shared` — SQLDelight `FamilyHubDb` (typed tables, sync tables, `local_session`),
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
  `familyhub://invite|login`, session restore on start, foreground toggle, connectivity trigger, debug-only cleartext to
  local hosts, adaptive icon. `SessionManager.deleteAccount`. 23 new client tests (ViewModels on a mock server, Compose UI
  tests incl. bottom-bar filtering, icon resources). Emulator walkthrough of all flows except live camera scanning.

## Next steps

1. Next plan: [M0-08 Family management screens](plans/M0/08-family-management-ui.md), in a fresh session
   (M0-10 Deploy can also start now; it depends only on M0-05).
2. After M0-11: M0 acceptance with the customer (APK, walkthrough, Russian changelog).

## Plan progress

See the table in [plans README](plans/README.md#m0-plans).

## Waiting for the customer

- GitHub repository: the customer will create it later; until then work is local.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Google developer verification for sideloaded apps (rolling out from 2026, global 2027) | APK / F-Droid installs may require a verified developer | Re-check before M6; a developer account likely covers it |
| OEM battery optimizations (Xiaomi, Huawei, Samsung) kill background location | Gaps in location tracking | In-app guide to exclude the app from battery optimization; detect and warn about stale positions |
| JVM server memory above budget | Stack does not fit a 1 GB VPS | Measured in M0-11 (ADR-0013); tune, then GraalVM native image |
| Compose Multiplatform web target is Beta | Web client (M7) may need workarounds | Re-evaluate at M7; fallback is a separate web UI |
| Sync engine complexity | Data loss or conflicts | Protocol accepted (sync.md, ADR-0008); server invariants (M0-05) and multi-device convergence tests (M0-06) run in `check` |
| Flexible permissions make UI and testing harder | Confusing group editor, permission bugs | Templates "Adults"/"Kids", plain wording, permission matrix tests (ADR-0014) |
| Emulator cannot reach the host's ports on this Mac (`10.0.2.2` times out) | Manual checks against the dev server | `adb reverse tcp:8080 tcp:8080` and `http://localhost:8080` (dev-setup) |
| Pre-release UI libraries (CMP Material3 alpha, navigation RC) | Possible API changes or bugs | Versions paired by JetBrains with CMP 1.12.2; upgrade when stable |
| ktlint 1.8 parses only Kotlin ≤ 2.2 syntax | Lint failures on newest syntax | Avoid newest syntax; report instead of disabling lint |
| Medium-effort sessions deviate from plans | Inconsistent code | Plans fix all decisions; "stop and report" rules; customer approves each commit |

## Dev environment notes

- Host: macOS (Apple Silicon), Homebrew, Docker. JDK 21 (Homebrew), Android SDK from Android Studio
  (`~/Library/Android/sdk`) and the `familyhub` AOSP emulator;
  see [dev-setup](process/dev-setup.md).
- The shell's default `java` may be older than 21; Gradle uses JDK 21 from `~/.gradle/gradle.properties`. To run
  `server/build/install/server/bin/server` directly, set `JAVA_HOME` to that JDK.
- Host port 5432 may be taken by another local PostgreSQL; the dev compose file accepts `FAMILYHUB_DEV_DB_PORT`.
