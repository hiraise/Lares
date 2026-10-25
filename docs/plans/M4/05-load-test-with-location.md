---
id: M4-05
status: ready
depends_on: [M4-04]
requirements: [FND-073]
---
# M4-05: Load simulation with location uploads

## Goal

The ADR-0013 targets are re-measured with location (ADR-0015 §2): the simulated family of M0-11 plus every sharing
phone uploading a position every 30 s, viewers holding the live map channel, places with arrivals and departures. The
results are recorded next to the M0 baseline.

## Read first

- [ADR-0013](../../adr/0013-server-resource-budget.md), [ADR-0015](../../adr/0015-load-measurement-amendment.md)
- [M0 baseline](../../architecture/perf/M0-baseline.md) (method, settings, notes for M4)
- [Location design](../../architecture/location.md) § Server: Positions, state, live channel
- Code: `tools/loadsim/src/main/kotlin/app/lares/tools/loadsim/` (`Main.kt` `setUp`, `AdminDevice`, `Config.kt`
  `parse` and `USAGE`, `Device.kt` loops, `Api.kt` `DeviceApi.call/get/send/exchange` endpoint labels, `Report.kt`),
  `tools/loadsim/src/test/…/LoadsimTest.kt`, `deploy/scripts/loadtest.sh`

## Scope

- `Config`: `--sharing-devices N` (default `members`: the **first** device of each member shares — loadsim signs in two
  devices per member, `members = (devices + 1) / 2`, and two sharing phones of one member would fight, LOC-019),
  `--position-interval` (default 30 s), `--map-viewers N` (default 3, taken from the second devices), `--places`
  (default 2).
- `LocationDevice` (new file `Location.kt`): for a sharing device — `PUT /location/me/sharing` once, then every interval
  `POST /location/positions` with 1 fix moving along a loop route (a 3 km circle through the two places, 30 s steps at
  walking/driving speed alternating), with the documented endpoint labels; it honours `refreshRequested` by sending one
  extra fix.
- Map viewers: keep `WS /location/live` open (count messages), `GET /location/now` every 5 min, `GET /location/history`
  for a random visible member every 10 min, `POST /location/members/{id}/refresh` every 10 min.
- `setUp()`: the admin creates the places (sync push); each viewer device pushes its **own** `place_alert`
  (`Ids.placeAlertRowId(sharingMember, viewerMember)`) for a sharing member — the writer must be the subscriber.
- Report: per-endpoint latency rows as today (`POST /location/positions`, `GET /location/now`, …) and the count of live
  messages; `summary.json` gets `location` totals.
- `loadtest.sh`: passes `LARES_GEOCODER_URL=` (addresses off: the public service must never be load-tested).
- Run the full 30-minute scenario with `--devices 20` (10 members, 10 sharing phones: ADR-0013 §1), once with
  `--devices 10` for comparison with the M0 baseline, and a 5-minute run with `cpus: 1` (the M0 note); record results in
  `docs/architecture/perf/M4-location.md` (same sections as the baseline) and a summary in STATUS.md.

Out of scope: JVM tuning unless a target is missed (then: report first, see below).

## Decisions (do not revisit)

- Simulated positions are synthetic (Moscow coordinates in code), accuracy 10–40 m, battery decreasing 1 % per 10 min.
- One fix per upload request (the worst case for request count; real phones batch while moving).
- Targets unchanged: RSS ≤ 256 MiB steady, p95 ≤ 100 ms for API and sync requests, cold start ≤ 10 s; the run fails the
  error-rate gate as today.
- Upload p95 is reported separately; it counts against the 100 ms target.

## Steps

1. Config and parsing with tests in `LoadsimTest` (flags, route geometry stays within 3 km, place alerts set up).
2. Location device and viewers; report additions.
3. `loadtest.sh` change; full run; `cpus: 1` short run.
4. Write `M4-location.md` and compare with the M0 baseline.

## Acceptance

- `tools/dev/verify.sh :tools:loadsim:test` green.
- `./gradlew :server:installDist :tools:loadsim:installDist && deploy/scripts/loadtest.sh` completes; results under
  `build/loadtest/<time>/` and in the perf doc.

## Docs to update

- `docs/architecture/perf/M4-location.md` (new), a link from `M0-baseline.md`; FND-073 progress note; STATUS.md
  (Risks: JVM memory row updated with the new numbers); plan status.

## Stop and report if

- Any target is missed: record the numbers, list the top suspects (heap histogram, slow queries), and stop for a
  decision before tuning (tuning may need an ADR, ADR-0013 §4).

## Handoff

Russian summary: проверили сервер под нагрузкой — 20 телефонов, из них 10 передают геопозицию каждые 30 секунд — память,
скорость ответа и запуск в пределах целей (или: где не уложились и что предлагаю). Next M4-06.
