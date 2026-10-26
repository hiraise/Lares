---
status: accepted
updated: 2026-10-26
---
# M4 performance with location

Re-measurement of the [ADR-0013](../../adr/0013-server-resource-budget.md) targets with location uploads, as
[ADR-0015](../../adr/0015-load-measurement-amendment.md) §2 requires. Plan: [M4-05](../../plans/M4/05-load-test-with-location.md).
Method, environment and JVM settings are those of the [M0 baseline](M0-baseline.md); nothing was tuned.

**Result: all targets met, memory with little margin.** With the full household of ADR-0013 §1 (10 members, 20 devices,
10 phones uploading a position every 30 s, 3 open maps) the server RSS levels off at about 252 MiB (max 254, target
≤ 256), p95 latency is 95 ms (target ≤ 100), position uploads alone 37 ms, cold start 2–3.4 s (target ≤ 10). With the
M0 family size (10 devices) the numbers are close to M0: RSS max 247 (M0: 237), p95 46 ms (M0: 52).

## How to reproduce

```sh
./gradlew :server:installDist :tools:loadsim:installDist
deploy/scripts/loadtest.sh --devices 20                                       # full household, 30 min
deploy/scripts/loadtest.sh                                                    # 10 devices, as M0
LOADTEST_SERVER_CPUS=1 deploy/scripts/loadtest.sh --devices 20 --duration 5m  # server limited to 1 CPU
```

`loadtest.sh` now turns addresses off (`LARES_GEOCODER_URL=`: the public geocoder is never load-tested) and accepts
`LOADTEST_SERVER_CPUS` (a `cpus:` limit for the server container). `summary.json` has a `location` block.

## Environment

As in the M0 baseline: Apple M1, Docker Desktop 28.0.4 with 8 CPUs and 12.7 GiB, the production stack with ntfy as
`https://localhost`, loadsim on the host through Caddy (latency includes TLS and the proxy). The host was not idle
(load average 4–6 on 8 cores from other work), as in most M0 runs.

## Scenario "family with location"

The M0 scenario (sync pushes every 60 s, pulls on events, token refresh, admin calls, 2 min warm-up, 30 min measured,
burst minute 5–6) plus, by default:

- **Sharing phones**: the first device of every member (`--sharing-devices`, one per member: LOC-019). Each sets
  sharing on once (`PUT /location/me/sharing`) and uploads **one fix per request** every 30 s (`--position-interval`;
  the worst case for request count, real phones batch). The route is a circle of 3 km diameter in Moscow through the
  family places; phones walk (1.4 m/s) within 500 m of a place and drive (11 m/s) between places, starting spread over
  the circle; accuracy 10–40 m, battery −1 % per 10 min. An answer with `refreshRequested` gets one extra fix.
- **Places**: 2 (`--places`), radius 150 m, created by the administrator with a sync push.
- **Map viewers**: the second devices of the first 3 members (`--map-viewers`). Each keeps `WS /location/live` open,
  calls `GET /location/now` every 5 min and, every 10 min, `GET /location/history` of a visible member and
  `POST /location/members/{id}/refresh` for a sharing one. Each viewer subscribes to arrive / leave alerts of one
  sharing member with its own `place_alert` row, so the server also sends location notifications.

## Results

| Run | Devices | Sharing | Duration | Requests | Failed | Positions sent / accepted | Live messages | Arrivals / departures seen |
| --- | ---: | ---: | --- | ---: | ---: | --- | ---: | --- |
| `m4-20dev-30m` | 20 | 10 | 30 min | 21 757 | 0 | 639 / 639 | 1 920 | 60 / 60 |
| `m4-10dev-30m` | 10 | 5 | 30 min | 7 003 measured | 0 | 320 / 320 | 966 | 30 / 30 |
| `m4-20dev-cpu1-5m` (server `cpus: 1`) | 20 | 10 | 5 min | 5 582 measured | 0 | 140 / 140 | 431 | 18 / 0 |

"Seen" counts place changes in the `member` messages of the 3 viewers (each sees every member), so 60 means 20
arrivals: the geofences ran under load. No mutation was rejected, no socket dropped. "Refreshes answered by an extra
fix" was 0 in every run: the next regular upload clears the request, so the upload answer never asked for one.

### Latency, full household (`m4-20dev-30m`, ms, client-side through Caddy)

| Endpoint | Phase | Count | p50 | p95 | p99 | Max |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `GET /sync/changes` | steady | 16 104 | 48.7 | 96.7 | 134.8 | 771.8 |
| `POST /sync/mutations` | steady | 584 | 27.8 | 65.7 | 91.1 | 132.4 |
| `POST /location/positions` | steady | 580 | 16.8 | 34.7 | 70.2 | 123.2 |
| `GET /location/now` | steady | 18 | 14.4 | 27.7 | — | 27.7 |
| `GET /location/history` | steady | 9 | 17.5 | 73.7 | — | 73.7 |
| `POST /location/members/{id}/refresh` | steady | 9 | 14.3 | 28.1 | — | 28.1 |
| `POST /auth/refresh` | steady | 59 | 15.8 | 29.6 | 76.1 | 76.1 |
| **All** | steady | 17 369 | 47.7 | **95.1** | 133.6 | 771.8 |
| **All** | burst | 3 510 | 44.5 | **94.6** | 135.4 | 321.3 |
| `POST /location/positions` | measured | 600 | 16.7 | **36.5** | 68.2 | 123.2 |
| **All** | measured | 20 879 | 47.2 | **95.1** | 133.8 | 771.8 |

Position uploads are cheap (p95 37 ms). The overall p95 is set by pulls: with 20 devices every push wakes 19 other
devices, and each pulls (18 560 events, three times the 10-device run). That is sync fan-out, which grows with the
square of the device count, not location load; the pulls themselves stay small.

### Latency, 10 devices (`m4-10dev-30m`) next to M0

| | M0 `final-30m` | M4 `m4-10dev-30m` |
| --- | ---: | ---: |
| p95 all, measured | 51.7 | **45.8** |
| p95 steady / burst | 55.8 / 37.4 | 47.1 / 33.8 |
| p95 `POST /sync/mutations` steady | 103.5 | 54.2 |
| p95 `POST /location/positions` | — | 44.3 |

The push latency that was close to the limit in M0 is now about half of it.

### Latency with the server on 1 CPU (`m4-20dev-cpu1-5m`)

p95 all 73.3 ms (steady 94.6, burst 61.1), uploads 27.4 ms. The steady p95 equals the unlimited run's (95 ms), so one
CPU is enough for the full household.

### Memory (MiB, steady and burst phases, sample every 10 s)

| Container | 20 devices min / avg / max | 10 devices min / avg / max | M0 (10 devices) avg / max |
| --- | --- | --- | --- |
| server — JVM process RSS | 235.4 / 249.4 / **254.0** | 224.5 / 239.2 / **247.1** | 232.4 / 237.0 |
| server — `docker stats` | 216.9 / 231.6 / 235.9 | 205.8 / 221.0 / 230.5 | 213.6 / 218.8 |
| postgres | 55.4 / 61.8 / 64.5 | 53.8 / 60.6 / 63.2 | 52.3 / 54.3 |
| caddy | 36.2 / 38.1 / 40.9 | 27.1 / 27.4 / 28.2 | 26.8 / 28.0 |
| ntfy | 22.4 / 22.7 / 22.8 | 21.0 / 21.5 / 21.9 | 21.5 / 22.0 |
| **stack** | 334.3 / 354.3 / **361.3** | 311.3 / 330.6 / 338.3 | 314.2 / 320.4 |

1-CPU run (5 min, 20 devices): RSS max 241.2, stack max 337.8.

RSS of the 20-device run rose for 12 minutes and then stayed at 250–252 MiB to the end (no leak); the JVM ran 55–60
threads (M0: ~50). The extra ~10–15 MiB over M0 is the location code (classes, compiled code) and more connections,
not data: positions live in PostgreSQL.

### Cold start

| Run | Empty database | Restart with data |
| --- | ---: | ---: |
| 20 devices | 3.4 s | 2.0 s |
| 10 devices | 1.9 s | 2.4 s |
| 1 CPU | 3.7 s | 3.0 s |

## Conclusion against ADR-0013

| Target | Measured (full household, 20 devices) | Met |
| --- | --- | --- |
| Server RSS ≤ 256 MB in steady state | avg 249, max 254 MiB | yes, margin ~2 MiB |
| p95 ≤ 100 ms for API and sync requests | 95 ms (uploads 37 ms); 1 CPU: 73 ms | yes, margin ~5 ms |
| Cold start ≤ 10 s | 2.0–3.7 s | yes |
| Whole stack fits 1 vCPU / 1 GB | containers ~360 MiB | yes; 2 GB stays the recommendation |

Notes for later milestones: the margins are small. The budget module (M5) adds code and therefore RSS; the next
levers, in order, are a smaller code cache or metaspace, fewer Netty threads, JRE 25 with compact object headers, and
GraalVM native image (ADR-0013 §4). Latency headroom is limited by sync fan-out with 20 devices, which can be reduced by
coalescing `changed` events on the server if needed.
