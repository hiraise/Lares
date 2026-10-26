---
status: accepted
updated: 2026-10-23
---
# M0 performance baseline

Measurement of the [ADR-0013](../../adr/0013-server-resource-budget.md) targets for one household, as amended by
[ADR-0015](../../adr/0015-load-measurement-amendment.md) (no location uploads in M0). Plan: [M0-11](../../plans/M0/11-load-simulation.md).
Re-run in M4 with position uploads every 30 s: [M4-location.md](M4-location.md).

**Result: all targets met** after tuning the JVM (smaller heap and code cache). Server RSS levels off at about 235 MiB
(target ≤ 256), p95 latency is 56 ms (target ≤ 100), cold start is under 3 s (target ≤ 10), and the whole container
stack uses about 320 MiB, so it fits a 1 GB VPS.

## How to reproduce

```sh
./gradlew :server:installDist :tools:loadsim:installDist
deploy/scripts/loadtest.sh                   # 30-minute scenario; --duration 5m for a short run
```

The script builds the server image, starts the production stack (`deploy/docker-compose.yml` + the local build override,
with ntfy) as `https://localhost`, measures cold start, runs `tools/loadsim`, samples memory every 10 s, restarts the
server for a second cold start and removes everything. Results go to `build/loadtest/<date-time>/`: `latency.csv`
(every request), `summary.json`, `phases.csv`, `memory.csv` (`docker stats` per container), `server-rss.csv` (RSS and
thread count of the JVM process from `/proc/1/status`), `server.log`, `summary.txt`.
`LOADTEST_JAVA_OPTS` and `LOADTEST_SERVER_ENV` override server settings for tuning runs without editing the deploy files.

## Environment

| Item | Value |
| --- | --- |
| Host | Apple M1, 8 cores, 16 GB RAM, macOS |
| Docker | Docker Desktop, engine 28.0.4; VM with 8 CPUs, 12.7 GiB |
| Stack | postgres:18-alpine, server image (eclipse-temurin:21-jre-noble), caddy:2.11.7-alpine, ntfy v2.32.0 |
| Client | loadsim on the host, through Caddy (HTTPS, HTTP/1.1, WebSocket), so latency includes TLS and the proxy |

The VM has 8 CPUs, while the target VPS has 1 vCPU. CPU was never the limit (the family load is a few requests per
second); the latency numbers on a 1-vCPU VPS will be somewhat higher, and memory is driven by explicit JVM limits.

## Scenario "family"

Defaults of `loadsim` (all configurable, see `loadsim --help`):

- Setup through the public API: first-run setup (admin), 4 more members created without password in the template group
  "Adults" (may manage tags and persons), each of the 5 members signs in 2 devices with login codes → 10 devices.
- Each device keeps the `/api/v1/sync/events` WebSocket open, every 60 s ± 20 % pushes 1–3 mutations (tag or person:
  ~50 % creates, 40 % updates, 10 % deletes of its own rows) and pulls, pulls on every `changed` event
  (single-flight, coalesced like the app), and refreshes its tokens every 10 min ± 20 %.
- The admin session makes one REST call every 5 min, in turn: rename a group, create an invite, revoke it.
- 2 min warm-up (excluded), then 30 min measured; burst from minute 5 to 6 after the warm-up: every device pushes and
  pulls every 2 s.
- The run fails if more than 1 % of requests fail or more than 1 % of mutations are rejected.

## Results (final settings, 30-minute run)

Run `final-30m`, 2026-10-23. 6 863 measured requests, 1 failed (0.01 %, a pull that raced its own token refresh — a
simulator artefact, fixed afterwards: loadsim now retries like the app); 1 261 mutations, none rejected; 10 WebSockets
open the whole time, 5 870 events.

### Latency (ms, client-side through Caddy)

| Endpoint | Phase | Count | p50 | p95 | p99 | Max |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `GET /sync/changes` | steady | 4 763 | 26.4 | 52.2 | 101.7 | 431.8 |
| `POST /sync/mutations` | steady | 294 | 36.7 | 103.5 | 151.8 | 261.5 |
| `POST /auth/refresh` | steady | 32 | 21.1 | 63.7 | 66.3 | 66.3 |
| Admin calls (`PATCH /groups/{id}`, `POST /invites`, `DELETE /invites/{id}`) | steady | 6 | 22–30 | — | — | 64.1 |
| **All** | steady | 5 095 | 26.7 | **55.8** | 118.3 | 431.8 |
| `GET /sync/changes` | burst | 1 464 | 20.7 | 36.7 | 48.2 | 87.6 |
| `POST /sync/mutations` | burst | 304 | 18.2 | 41.4 | 99.6 | 135.1 |
| **All** | burst | 1 768 | 20.3 | **37.4** | 52.0 | 135.1 |
| **All** | measured | 6 863 | 25.3 | **51.7** | 101.4 | 431.8 |

Pushes are slower than pulls (one transaction per mutation with the household lock, plus the push fan-out). The
burst is faster than the steady phase: with requests every 2 s the JIT-compiled code, connection pools and CPU stay
warm; in the steady phase a device is idle for a minute between pushes.

### Memory (MiB, steady and burst phases, sample every 10 s)

| Container | Min | Avg | Max |
| --- | ---: | ---: | ---: |
| server — JVM process RSS | 213.7 | 232.4 | **237.0** |
| server — `docker stats` | 194.8 | 213.6 | 218.8 |
| postgres | 44.6 | 52.3 | 54.3 |
| caddy | 23.4 | 26.8 | 28.0 |
| ntfy | 20.7 | 21.5 | 22.0 |
| **stack** (sum of `docker stats`) | 288.7 | 314.2 | **320.4** |

`docker stats` reports cgroup memory without file cache, which is lower than the RSS of the JVM process (RSS counts
mapped shared libraries and the JDK's class data archive). RSS rose for the first ~15 minutes as code was compiled and
then stayed at 235–237 MiB. The JVM ran about 50 threads.

### Cold start

Time from `docker compose up server` (or `start`) to `/health/ready` = 200 through Caddy: **1.9 s** with an empty
database (migrations included), **2.7 s** on restart with data.

## Tuning attempts

| Run | Server settings | Duration | Server RSS avg / max | p95 all | Verdict |
| --- | --- | --- | --- | ---: | --- |
| `run1-default` | M0-10 defaults: `-XX:+UseSerialGC -Xmx160m -Xss512k -XX:MaxMetaspaceSize=128m` | 30 min | 247.7 / **259.8** | 59.0 | RSS above target, still rising |
| `diag-nmt` | defaults + native memory tracking, GC log | 10 min | 236.8 / 248.7 | — | diagnosis, see below |
| `tuneA` | defaults + `MALLOC_ARENA_MAX=2` | 10 min | 236.3 / 245.4 | 64.0 | −3 MiB, not enough alone |
| `tuneB` | A + `-Xmx128m -XX:ReservedCodeCacheSize=64m` | 10 min | 220.3 / 226.1 | 50.3 | −20 MiB, latency unchanged |
| `final-30m` | B as image defaults | 30 min | 232.4 / **237.0** | 51.7 | **targets met** |

Diagnosis (native memory tracking after 10 min with the old defaults): the JVM accounted for ~207 MB committed — heap
76 MB (live data only ~41 MB after a young GC, so the heap had grown far beyond need), compiled code 41 MB (code cache
reserved 240 MB by default), metaspace ~42 MB, symbols 17 MB, thread stacks 4 MB. The remaining ~40 MB of RSS was
native allocations outside the JVM's accounting (glibc malloc arenas, libraries).

Not needed and therefore not tried: Netty worker / Hikari pool changes (thread stacks are ~4 MB in total; the pool of 5
is already small), the CIO engine, JRE 25 with compact object headers, GraalVM native image.

A last 5-minute acceptance run with the final settings and `mem_limit: 300m` (`accept-final`) passed with no errors
and RSS max 234 MiB, but showed p95 157 ms and 3.5 s cold start: the Mac was busy with other work at the time (load
average 24–29 on 8 cores). Latency comparisons need an otherwise idle host.

## Final settings

- `server/Dockerfile`: `JAVA_OPTS="-XX:+UseSerialGC -Xmx128m -Xss512k -XX:MaxMetaspaceSize=128m
  -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError"`, `MALLOC_ARENA_MAX=2`.
- `deploy/docker-compose.yml`: server `mem_limit: 300m` (max RSS 237 MiB + ~25 % headroom; was 384m).

## Conclusion against ADR-0013

| Target | Measured | Met |
| --- | --- | --- |
| Server RSS ≤ 256 MB in steady state | avg 232, max 237 MiB | yes, with ~20 MiB margin |
| p95 ≤ 100 ms for API and sync requests | 52 ms (steady 56, burst 37); push alone 104 ms in steady phase | yes overall. Numbers are client-side (TLS, Caddy, Docker Desktop networking included), so server-side is lower; pushes are close to the limit, watch in M4 |
| Cold start ≤ 10 s | 1.9 s (empty DB), 2.7 s (restart) | yes |
| Whole stack fits 1 vCPU / 1 GB | containers ~320 MiB; OS and Docker need ~250–350 MB more | yes, with room; 2 GB stays the recommendation |

Notes for the M4 re-run: location uploads add writes but little memory; re-check RSS (heap is now capped at 128 MB with
~41 MB live data), push latency, and a 1-vCPU limit for the server container (`cpus: 1` in a tuning override) to get
numbers closer to a real VPS.
