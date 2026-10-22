---
status: accepted
date: 2026-10-22
---
# ADR-0015: Load measurement in M0 without location uploads (amends ADR-0013 §2)

## Context

[ADR-0013](0013-server-resource-budget.md) §2 says M0 measures the resource targets with a simulation of "10 devices syncing,
location every 30 s". M0 has no location API — it arrives in M4. Simulating it through a test-only endpoint would add
production code that exists only for a benchmark.

## Decision

1. M0 ([plan M0-11](../plans/M0/11-load-simulation.md)) measures sync, realtime (WebSocket) and authentication load
   from 10 devices, including a burst phase. Targets of ADR-0013 §1 apply unchanged.
2. The M4 plan re-runs the same simulation with position uploads every 30 s per sharing device and records the results.
3. Results go to `docs/architecture/perf/` and a summary to STATUS.md.

All other parts of ADR-0013 stay in force.

## Consequences

- The M0 baseline covers the JVM, database and sync overhead — the dominant memory cost; location write load
  (≈ 0.3 writes/s for a family) is measured in M4.
