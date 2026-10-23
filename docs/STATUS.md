# Project status

Updated: 2026-10-22

## Phase

**M0 Foundation — implementation in progress: M0-01…M0-03 done.**
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

## Next steps

1. Next plan: [M0-04 Household administration](plans/M0/04-household-admin.md), in a fresh session.
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
| Sync engine complexity | Data loss or conflicts | Protocol fixed in sync.md; invariant tests in M0-05/M0-06 |
| Flexible permissions make UI and testing harder | Confusing group editor, permission bugs | Templates "Adults"/"Kids", plain wording, permission matrix tests (ADR-0014) |
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
