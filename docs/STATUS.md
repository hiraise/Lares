# Project status

Updated: 2026-10-22

## Phase

**Pre-M0: kickoff done.** Scope, stack, permissions model and documentation system decided. No code yet.

## Active milestone

[M0 Foundation](product/roadmap.md#m0-foundation) — not started.

## Done

- 2026-10-22 Kickoff with the customer: modules, priorities, license, distribution, docs language.
- 2026-10-22 Repository initialized (local only), AGPL-3.0 license, documentation skeleton, roadmap, draft feature specs.
- 2026-10-22 Kickoff discussion, round 2: stack re-reviewed (Kotlin kept, resource budget ADR-0013), group-based
  permissions (ADR-0014), Q-001…Q-011 answered, GPS trackers moved to "Later", web client is M7.
- 2026-10-22 Access model clarified: section access, actions, data visibility, household management;
  administrators see all data (ADR-0014, Q-012).

## Next steps

1. Customer approves the kickoff documentation → first commit to `main`.
2. M0 kickoff: finalize the [foundation spec](product/features/foundation.md) and the sync design (ADR-0008 is `proposed`).
3. M0 scaffolding: Gradle monorepo, CI, server skeleton, Android shell, Docker Compose deploy, load simulation (ADR-0013).

## Waiting for the customer

- Approval of the kickoff documentation for the first commit.
- GitHub repository: the customer will create it later; until then work is local.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Google developer verification for sideloaded apps (rolling out from 2026, global 2027) | APK / F-Droid installs may require a verified developer | Re-check before M6; a developer account likely covers it |
| OEM battery optimizations (Xiaomi, Huawei, Samsung) kill background location | Gaps in location tracking | In-app guide to exclude the app from battery optimization; detect and warn about stale positions |
| JVM server memory above budget | Stack does not fit a 1 GB VPS | Measure in M0 (ADR-0013); tune, then GraalVM native image |
| Compose Multiplatform web target is Beta | Web client (M7) may need workarounds | Re-evaluate at M7; fallback is a separate web UI |
| Sync engine complexity | Data loss or conflicts | Prototype in M0, multi-device sync tests ([testing](process/testing.md)) |
| Flexible permissions make UI and testing harder | Confusing group editor, permission bugs | Templates "Adults"/"Kids", plain wording, permission matrix tests (ADR-0014) |

## Dev environment notes

- Host: macOS, Docker installed. No JDK/Gradle check done yet; M0 sets up the toolchain.
