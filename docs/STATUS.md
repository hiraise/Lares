# Project status

Updated: 2026-10-23

## Phase

**M0 Foundation — all plans M0-01…M0-11 done; M0 acceptance with the customer next.**
Detailed plans for implementation sessions are in [docs/plans/](plans/README.md).

## Active milestone

[M0 Foundation](product/roadmap.md#m0-foundation). Specs `foundation` and `tags` approved.

## Last completed

- 2026-10-23 Release signing and flow (ADR-0019): permanent Lares key (GitHub secrets; original in
  `~/.android/lares-release/`), release workflow fails without it and checks the certificate fingerprint, notes from
  CHANGELOG, `tools/release/release.sh`, versionCode derived from the version, [release process](process/release.md).
- 2026-10-23 Project renamed FamilyHub → **Lares** (Q-001): package and application id `app.lares`, env vars
  `LARES_*`, image `ghcr.io/hiraise/lares-server`, repository `github.com/hiraise/lares`.
- 2026-10-23 M0-11 Load simulation: `tools/loadsim` + `deploy/scripts/loadtest.sh`. Simulated family (10 devices,
  30 min): server RSS avg 232 / max 237 MiB (target ≤ 256), p95 52 ms (≤ 100), cold start 1.9–2.7 s (≤ 10), whole
  stack ~320 MiB — ADR-0013 targets met after JVM tuning ([baseline](architecture/perf/M0-baseline.md)).
- 2026-10-23 M0-10 Deploy and operations: Docker Compose stack (HTTPS, optional ntfy), backup/restore, smoke test,
  release workflow (multi-arch image, APK), install manual en/ru.
- 2026-10-23 M0-09 Notifications: local reminders, UnifiedPush (ntfy), background sync, Settings › Notifications.
- 2026-10-23 M0-08 Family management screens.
- 2026-10-23 Agent tooling: skills `plan-run`, `plan-finish`, `build-check`, `emulator`, agent `emulator-tester`,
  scripts in `tools/dev/`. Full log: [history.md](history.md).

## Next steps

1. M0 acceptance with the customer (APK, walkthrough, Russian changelog).
2. First signed release `0.2.0` after M1 is accepted (customer, 2026-10-23: no `0.1.2`; [release process](process/release.md));
   make the GHCR package `lares-server` public or `docker login ghcr.io` on the server
   ([deploy/README](../deploy/README.md#releases)).
3. Once a VPS and domain exist: install by the manual and check instant notifications end to end on a real phone (FND-051).
4. M4 plan: re-run the load test with location uploads every 30 s (ADR-0015).
5. M1 Tasks: [tasks spec](product/features/tasks.md) approved (2026-10-23); execute plans [M1-01…M1-07](plans/README.md#m1-plans) (design: [architecture/tasks.md](architecture/tasks.md), ADR-0017, ADR-0018).

## Plan progress

See the table in [plans README](plans/README.md#m0-plans).

## Waiting for the customer

- For a real installation: a VPS (1 vCPU / 2 GB / 20 GB) and a domain with DNS access.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Google developer verification for sideloaded apps (rolling out from 2026, global 2027) | APK / F-Droid installs may require a verified developer | Re-check before M6; a developer account likely covers it |
| Loss or leak of the release signing key | No updates for installed apps / fake updates | Lares-only key, backup archive kept off the machine by the customer, fingerprint checked in the release workflow (ADR-0019) |
| OEM battery optimizations (Xiaomi, Huawei, Samsung) kill background location | Gaps in location tracking | In-app guide to exclude the app from battery optimization; detect and warn about stale positions |
| JVM server memory above budget | Stack does not fit a 1 GB VPS | M0-11: RSS max 237 of 256 MiB after tuning, stack ~320 MiB; re-measure in M4 with location; next lever GraalVM native image |
| Compose Multiplatform web target is Beta | Web client (M7) may need workarounds | Re-evaluate at M7; fallback is a separate web UI |
| Sync engine complexity | Data loss or conflicts | Protocol accepted (sync.md, ADR-0008); server invariants (M0-05) and multi-device convergence tests (M0-06) run in `check` |
| Flexible permissions make UI and testing harder | Confusing group editor, permission bugs | Templates "Adults"/"Kids", plain wording, permission matrix tests (ADR-0014) |
| Emulator cannot reach the host's ports on this Mac (`10.0.2.2` times out) | Manual checks against the dev server | `adb reverse tcp:8080 tcp:8080` and `http://localhost:8080` (dev-setup) |
| Emulator is slow on this Mac (software GPU, cold start ~9 s, ANR dialogs under load) | Walkthroughs are slow; real ANRs are hard to tell from emulator stalls | Check main-thread CPU (`/proc/<pid>/task/<pid>/stat`) and ANR traces before blaming the app; real phone for M0 acceptance |
| Pre-release UI libraries (CMP Material3 alpha, navigation RC) | Possible API changes or bugs | Versions paired by JetBrains with CMP 1.12.2; upgrade when stable |
| ktlint 1.8 parses only Kotlin ≤ 2.2 syntax | Lint failures on newest syntax | Avoid newest syntax; report instead of disabling lint |
| Medium-effort sessions deviate from plans | Inconsistent code | Plans fix all decisions; "stop and report" rules; customer approves each commit |

## Dev environment notes

- Host: macOS (Apple Silicon), Homebrew, Docker. JDK 21 (Homebrew), Android SDK from Android Studio
  (`~/Library/Android/sdk`) and the `lares` AOSP emulator;
  see [dev-setup](process/dev-setup.md).
- The shell's default `java` may be older than 21; Gradle uses JDK 21 from `~/.gradle/gradle.properties`. To run
  `server/build/install/server/bin/server` directly, set `JAVA_HOME` to that JDK.
- Host port 5432 may be taken by another local PostgreSQL; the dev compose file accepts `LARES_DEV_DB_PORT`.
