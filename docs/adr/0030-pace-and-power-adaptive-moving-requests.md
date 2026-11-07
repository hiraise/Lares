---
status: accepted
date: 2026-11-07
---
# ADR-0030: While moving, the platform request follows the pace and the phone's power

## Context

ADR-0028 §2 asks the platform for a high-accuracy fix every 10 s while MOVING, but the policy records one every ≥ 30 s
or ≥ 50 m: on foot two of three GNSS fixes are thrown away, and an interval at or below 10 s keeps the GNSS chip running
continuously on AOSP instead of starting it per fix. The customer asked for less battery use with location sharing on
(2026-10-27) and agreed that a phone low on battery may send positions less often. On 2026-11-07 the customer asked for
every minute on foot and every 30 s in a car, and reported a tangle of track lines at home: indoor fixes scatter by
50–150 m, a single jump of 150 m left STILL, and the 75 m still radius then kept the phone in MOVING. ADR-0006 rules out activity
recognition, so the pace comes from the fixes themselves.

## Decision

Amends ADR-0028 §2 and §3; the thresholds for recording and the STILL request stay.

1. MOVING asks for a fix every minute (min interval 30 s). After a fix at ≥ 5 m/s (18 km/h; the reported speed, or the
   distance from the previous fix when none is reported) it asks every 30 s (min 15 s) until a minute passes without
   such a fix. Every mode change starts at the walking pace.
2. Low power — the last fix reports battery ≤ 20 % and not charging, or the phone's battery saver is on — doubles both
   intervals (2 min on foot, 1 min fast) and uploads when the oldest queued fix is ≥ 2 min old (or 20 are queued).
   MOVING → STILL then looks at the last 5 minutes instead of 3, so three fixes fit.
3. The policy tells the service when the request changes within a mode; the service re-requests updates.
4. MOVING → STILL counts a fix within 75 m of the mean plus its accuracy, up to 50 m more (at most 125 m, below the
   150 m that leaves STILL).
5. STILL → MOVING by position needs two accurate fixes in a row ≥ 150 m away; the first is not recorded. A real
   departure is caught earlier by the significant-motion sensor; without one, the confirming fix comes within 5 min.

## Consequences

- Against ADR-0028 the GNSS chip works about a sixth as often on foot and a third as often in a vehicle; a car track
  cuts corners (about 500 m between points at 60 km/h).
- Indoor noise leaves fewer stray points and short MOVING bursts at home; a slow walker with poor fixes and no reported
  speed may count as STILL until the motion sensor fires.
- On low power a moving member's pin can lag up to about 4 minutes; still members are unaffected (heartbeat every 15
  min as before). «Обновить» still triggers an immediate fix.
- The gain is not yet measured on a real phone; the M4 acceptance battery check covers it.

## Alternatives considered

- **Balanced quality while moving** — `fused` balanced no longer uses GNSS on Android 16 QPR1 (ADR-0028), and on phones
  without a network location provider (no Google services) it yields no fixes at all; walking tracks would also jump
  past the 75 m still radius.
- **A minimum distance filter on the request** — the provider still runs GNSS to measure the distance; no gain.
- **Longer upload intervals for everyone, or live tracking only while someone looks at the map** — change what the
  family sees; product questions for the customer.
