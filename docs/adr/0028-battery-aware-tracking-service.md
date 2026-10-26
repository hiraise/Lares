---
status: accepted
date: 2026-10-25
---
# ADR-0028: Phones track with one foreground service in moving and still modes

## Context

LOC-001/002/007/008/011 need positions in the background, a visible indicator, frequent updates while moving and little
battery use while staying. ADR-0006 forbids Google Play Services (no fused client, no activity recognition, no
geofencing API). Android 14+ requires a `location` foreground service started while the app is visible, or with
`ACCESS_BACKGROUND_LOCATION` from boot, push or other exempt starts. On Android 16 QPR1 the platform fused provider's
balanced quality no longer uses GNSS. Android 16 job quotas apply to work running beside a foreground service. Aggressive
vendors (Xiaomi, Huawei, Samsung) kill background apps unless battery optimization is off.

## Decision

1. While sharing is on, one `LocationService` runs as a `location` foreground service with a persistent notification
   (pause / resume actions). It is started from the visible app; restarts after boot, update, push or process death
   need `ACCESS_BACKGROUND_LOCATION`, which the setup asks for («Разрешить в любом режиме»).
2. A pure `TrackingPolicy` drives two modes: MOVING (high accuracy, a fix recorded every ≥ 30 s or ≥ 50 m) and STILL
   (one fix every 15 min, plus the passive provider; high accuracy on API 36+). STILL is entered after 3 minutes within
   75 m; MOVING again on the significant-motion trigger sensor or a fix ≥ 150 m away.
3. Fixes go to a local queue (24 h, 5 000 fixes) and are uploaded by the service itself (not WorkManager): every 30 s
   while moving, at once while still and on network return.
4. «Обновить» arrives by a data push (`location_refresh`) or in the upload response, and triggers one high-accuracy fix.
5. The phone reports what it can observe — location turned off, permission revoked, the service stopped — as
   OFF_ON_PHONE with a reason; the server marks SIGNED_OUT when the sharing session ends (LOC-012).
6. The setup asks to turn off battery optimization (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) and shows vendor hints;
   the phone warns when it could not upload for an hour (LOC-008).

## Consequences

- No live socket in the service (a 30 s ping would keep the radio awake); refresh latency is the push latency, or up to
  15 minutes without a distributor.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is acceptable for sideloaded and F-Droid builds; for Google Play the family
  safety use case is in the allowed list (re-check before M6).
- Real-phone checks across vendors belong to the M4 acceptance; the emulator covers the logic with `geo fix`.

## Alternatives considered

- **WorkManager periodic jobs** — 15-minute minimum, quotas, no continuous tracking while moving.
- **Fixed interval tracking** — simple, but either drains the battery or misses arrivals.
- **Activity recognition** — needs Google Play Services.
