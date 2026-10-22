# Testing strategy

Agents write and validate all code, so tests are the main evidence that something works.
Never claim a change is done without running the relevant tests.

## Levels

| Level | Scope | Tooling (finalized in M0) |
| --- | --- | --- |
| Unit | Domain logic: recurrence, allocation rules, deficits, geofence math, permissions | kotlin.test, runs on JVM |
| Server integration | HTTP API + real PostgreSQL, permissions, household isolation | Ktor test host, Testcontainers |
| Sync | Several simulated clients editing offline and syncing: convergence, no data loss, rejections | Shared client code against a test server |
| Client | View models, repositories, local DB | KMP common tests |
| Android | Location service, notifications, permissions flow | Instrumented tests, kept minimal |
| UI | Screen rendering regressions | Screenshot tests, added when UI stabilizes |
| Deploy smoke | `docker compose up` from release images, health check, login, backup/restore | CI job |

## Rules

- Test names or annotations reference requirement IDs: `TSK-011 next occurrence counts from completion date`.
- Every feature module has household-isolation tests (ADR-0010): data of household A is never visible to household B.
- Every permission row in a spec's permission table has a test.
- Time-dependent logic uses an injected clock; no sleeps in tests.
- Bugs get a regression test before the fix.
- Flaky tests are fixed or quarantined with an issue, never ignored silently.

## CI

Every push: build, lint, unit, integration, sync tests. Release tags: full suite + deploy smoke + artifacts (server image, APK).
