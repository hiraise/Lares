---
status: proposed  # not scheduled; finalized when trackers are planned
date: 2026-10-22
---
# ADR-0009: GPS trackers via Traccar compatibility

## Context

The customer wants to register GPS trackers (kids' watches, car trackers). The device market uses hundreds of
protocols (GT06, H02, TK103, the "3G/watch" protocol of kids' watches, etc.). Traccar is an Apache-2.0 open source
server that supports 200+ of them. Many kids' watches can be redirected to a custom server by SMS command.

## Decision

1. Our server implements only the **OsmAnd HTTP protocol** (simple HTTP request with device id, coordinates, time,
   speed, battery), used by Traccar Client apps and some trackers.
2. For all other protocols, the admin runs **Traccar** next to our server (optional Compose profile).
   Traccar forwards positions to our server, or our server reads them from Traccar's API; chosen when scheduled.
3. Trackers are first-class devices linked to a member or an object (car, pet); positions go into the same
   location pipeline as phones, so maps, history and geofences work the same.

## Consequences

- No protocol parsing code beyond OsmAnd in our server.
- Exotic devices need an extra container and open ports on the VPS.
- Closed ecosystems (AirTag, SmartTag) remain unsupported.

## Alternatives considered

- **Implement protocols ourselves** — large, device-specific, hard to test without hardware.
- **Embed Traccar as a library** — it is a standalone Java server, not designed for embedding.
