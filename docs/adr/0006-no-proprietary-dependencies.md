---
status: accepted
date: 2026-10-22
---
# ADR-0006: No proprietary dependencies: maps, location, push

## Context

- The app must be publishable on F-Droid (no proprietary libraries) and work on phones without Google services
  (Huawei, LineageOS, GrapheneOS).
- Self-hosted servers cannot use Firebase Cloud Messaging without the project running a central relay holding
  Firebase credentials, which contradicts self-hosting.
- The customer asked whether location and notifications work without Google Play: they must.

## Decision

1. **Maps:** MapLibre (open source) with OpenStreetMap-based vector tiles. Default tile source: OpenFreeMap
   (free, no API key); the server admin can configure another source, including self-hosted tiles.
   The client gets the tile URL from the server.
2. **Location:** Android platform `LocationManager` (its fused provider on Android 12+, GPS/network providers otherwise).
   No Google Play Services location or geofencing APIs. Geofences are evaluated on the server (also serves GPS trackers).
3. **Time-based reminders:** scheduled locally on the device; no network needed.
4. **Event notifications:**
   - App in foreground: live connection to the server (WebSocket or SSE).
   - Background: UnifiedPush. The server sends to the endpoint of the member's distributor app (e.g. ntfy);
     an `ntfy` service is offered in Docker Compose so the family's own server delivers pushes.
   - When location sharing runs, its foreground service may keep the live connection instead.
   - Without a distributor: delivery on the next periodic background sync.
5. **No Firebase, Google Maps, Play Services, analytics or third-party crash reporting** in any build,
   including the Google Play build. Crash reporting, if added, goes only to the family's own server or is shared manually.

## Consequences

- One build for all channels; installing from GitHub, F-Droid or Google Play gives identical features.
- Works on phones without Google services.
- Instant background notifications need a UnifiedPush distributor app on the phone; the app must explain this in setup.
- Indoor network-based positioning on de-Googled phones may be less precise.
- Changing any point requires a superseding ADR, backed by field data (e.g. measured battery or accuracy problems).

## Alternatives considered

- **Google Play Services fused location + FCM in a separate "play" flavor** — better battery/accuracy tuning out of the box,
  but two builds to test, FCM needs a central relay, and it ties the product to Google.
- **Google Maps SDK** — proprietary, needs API keys and billing.
