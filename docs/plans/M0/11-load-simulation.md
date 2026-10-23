---
id: M0-11
status: done
depends_on: [M0-10]
requirements: [FND-073]
---
# M0-11: Load simulation and resource budget

## Goal

Measure the stack against [ADR-0013](../../adr/0013-server-resource-budget.md) with a realistic family load, tune the
JVM if needed, and record the results. Re-run later when location (M4) adds position uploads.

## Read first

- [ADR-0013](../../adr/0013-server-resource-budget.md) — targets: server RSS ≤ 256 MB steady state, p95 ≤ 100 ms,
  cold start ≤ 10 s, whole stack fits 1 GB
- [Sync protocol](../../architecture/sync.md), [API](../../architecture/api.md)
- Deploy setup from M0-10 (compose, smoke script)

## Scope

- New JVM module `tools/loadsim` (Kotlin, CLI via `application` plugin), using `contract/` DTOs and Ktor client.
- `deploy/scripts/loadtest.sh` orchestrating: stack up → loadsim → metrics → stack down.
- Results document `docs/architecture/perf/M0-baseline.md`.

Out of scope: location load (M4 re-run), web load.

## Decisions (do not revisit)

- **Scenario "family"** (defaults; all configurable by CLI flags): 10 devices (5 members × 2 devices) on one household,
  duration 30 min, plus a 2-minute warm-up excluded from statistics. Each device:
  - keeps a WebSocket to `/api/v1/sync/events` open;
  - every 60 s ± 20 % jitter pushes 1–3 mutations (tag or person upserts, sometimes deletes) and pulls;
  - pulls on every received `changed` event;
  - every 10 min refreshes its tokens.
  Additionally one "admin" device performs an admin REST call every 5 min (rename a group, create/revoke an invite).
- **Burst sub-scenario** (5 min after warm-up, 60 s long): all devices push and pull every 2 s — checks latency under spikes.
- **Measurements**: client-side latency per endpoint (p50/p95/p99, errors) by loadsim; server container memory via
  `docker stats --no-stream --format '{{.MemUsage}}'` every 10 s into CSV; JVM heap via `jcmd` is optional;
  cold start = time from `docker compose up server` to `/health/ready` = 200.
- Setup of the scenario uses the public API only (setup code, admin creates members without password, login codes).
  Simulated members are put into the "Adults" group so their tag/person mutations are allowed; loadsim counts rejections
  and fails the run if more than 1 % of mutations are rejected (otherwise the numbers would measure error paths).
- Location uploads are **not** simulated in M0 (no location API yet) — see [ADR-0015](../../adr/0015-load-measurement-amendment.md);
  the M4 plan re-runs this simulation with position uploads every 30 s.

## Steps

1. `tools/loadsim` (add `include(":tools:loadsim")` to settings): CLI flags `--server`, `--setup-code`, `--devices`, `--duration`, `--warmup`, `--burst`, `--out`;
   creates the household (or reuses with `--admin-login/--admin-password`), members, sessions; runs the scenario with
   coroutines; writes `latency.csv` and a summary JSON; exits non-zero on > 1 % errors.
2. `loadtest.sh`: starts the stack (reusing the smoke override that builds the local image), records cold start, runs loadsim,
   samples memory, stops the stack, prints a summary table.
3. Run it. If server RSS steady state > 256 MB or p95 > 100 ms, tune in this order, re-running each time and recording
   every attempt: (a) heap/metaspace sizes, thread stack size, Netty worker threads (`FAMILYHUB_NETTY_WORKERS`, default 2)
   and Hikari pool; (b) Ktor CIO engine instead of Netty (config switch `FAMILYHUB_ENGINE=netty|cio`);
   (c) runtime JRE 25 with `-XX:+UseCompactObjectHeaders` (bytecode stays 21). Stop after targets are met.
   GraalVM native image is **not** attempted in this plan — report if needed.
4. Write `docs/architecture/perf/M0-baseline.md`: environment (host CPU/RAM, Docker version), scenario, results table
   (RSS min/avg/max steady state, p50/p95/p99 per endpoint, cold start, whole-stack memory from `docker stats` for all
   containers), tuning attempts, final settings, conclusion vs ADR-0013 targets.
5. Apply final JVM settings to `server/Dockerfile` defaults and `deploy/docker-compose.yml` `mem_limit` (keep ~25 % headroom
   over measured max RSS).

## Acceptance

- `deploy/scripts/loadtest.sh` runs end to end (a 5-minute run with `--duration 5m` is enough for the acceptance check;
  the 30-minute run results go into the baseline doc).
- Baseline document exists with all numbers; targets met or a clear report of the gap.

## Docs to update

- STATUS.md: results summary in "Done", risk "JVM server memory above budget" updated or removed.
- Foundation spec: FND-073 → `done`.
- AGENTS.md "Commands": how to run the load test.
- Plan status.

## Stop and report if

- Targets are missed after all tuning steps — report numbers and options (native image, Go is **not** an option without
  a new ADR); do not change architecture.

## Handoff

Russian summary for the customer in plain words: how much memory the server uses with a simulated family, how fast it
answers, whether a VPS with 1–2 GB is enough; what was tuned.
