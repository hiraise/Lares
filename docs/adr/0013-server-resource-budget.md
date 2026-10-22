---
status: accepted
date: 2026-10-22
---
# ADR-0013: Server resource budget

## Context

The customer raised a concern that a Kotlin/JVM backend is not performant. Review against Go and Rust (2026-10-22):

- Load for one household: about 10 API requests per minute plus at most one position per minute per sharing phone.
  Throughput is not a constraint for any candidate.
- The real difference is memory: JVM server ~150–250 MB RSS, Go ~30–50 MB, Rust ~10–20 MB.
- Kotlin is kept (ADR-0004) because some logic must give identical results offline on the phone and on the server:
  recurrence of routines, permission evaluation (ADR-0014), budget allocation and rounding, validation, birthdays.
  One implementation removes a class of sync bugs; with Go it would be written twice.
- Traccar, needed if GPS trackers are added later (ADR-0009), is itself a JVM application.
- User-perceived speed comes from offline-first reads (ADR-0008), not from server speed.

## Decision

1. **Targets for one household** (≤10 members, ≤10 phones sharing location):
   - Whole stack (OS, Docker, PostgreSQL, server, Caddy, ntfy) runs on 1 vCPU / 1 GB RAM.
     Recommended: 1 vCPU / 2 GB RAM / 20 GB SSD.
   - Server process RSS ≤ 256 MB in steady state.
   - Server-side p95 latency ≤ 100 ms for API and sync requests.
   - Cold start ≤ 10 s.
2. **M0 measures the targets** with a load simulation (10 devices syncing, location every 30 s) and records results in STATUS.md.
3. JVM runs with explicit small-heap, container-aware settings.
4. **If targets are missed:** profile and tune first; then GraalVM native image (supported by Ktor).
   Switching the server language requires a superseding ADR.
5. User manuals recommend a VPS region close to the family (sync latency is dominated by network distance).

## Consequences

- Resource usage is a tested property, not a guess; regressions show up in CI load checks.
- A 512 MB VPS is not a supported target.

## Alternatives considered

- **Go server** — ~200 MB lighter and faster builds, but logic shared with the client is written in two languages,
  kept consistent only by shared test vectors. Declined by the customer after review, 2026-10-22.
- **Rust server** — lightest, but slower development and the same duplication; overkill for the load.
