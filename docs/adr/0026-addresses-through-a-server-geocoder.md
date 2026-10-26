---
status: accepted
date: 2026-10-25
---
# ADR-0026: Addresses come from a geocoder through the server, on demand

## Context

The customer wants street addresses for positions outside places and an address search for creating places (Q-029,
LOC-014, LOC-024). Hard rule 4 lets the app talk only to the family's server and to services the administrator
configures. The public Nominatim of the OpenStreetMap Foundation is free but limits use: at most 1 request per second
in total, an identifying User-Agent, results cached by the client, no search-as-you-type, and "periodic requests from
apps" count as bulk geocoding. Coordinates are personal data.

## Decision

1. Phones never call a geocoder. The server proxies `GET /location/address?lat&lon` and `GET /location/search?q` to a
   Nominatim-compatible service at `LARES_GEOCODER_URL` (default `https://nominatim.openstreetmap.org`; empty = off;
   optional `LARES_GEOCODER_EMAIL`).
2. Reverse lookups happen only when a phone shows a position that is outside every place and not moving (the map list,
   a stop in the history) — never per uploaded fix, never in a background job. Coordinates are rounded to 4 decimals
   (≈ 11 m); results are cached in memory (7 days) and on the phone; the server sends at most one request per second.
3. Search runs only on an explicit «Найти», limited to 5 results; never as you type.
4. Requests carry `User-Agent: Lares/<version> (+https://github.com/hiraise/Lares)` and the configured email.
5. With the geocoder off the app shows the distance to the nearest place and hides the search.
6. The install manual explains what is sent where and how to turn it off or use a self-hosted Nominatim or Photon with a
   Nominatim-compatible API.

## Consequences

- A family's address traffic is a few requests a day, within the public service's rules; if the OSMF asks, admins switch
  the URL (one variable).
- First display of an address waits for the lookup (≤ 5 s, then the row shows the distance instead).
- The address cache is in memory only, so it holds no location data after a restart.

## Alternatives considered

- **Addresses on every fix** — bulk geocoding by the policy's definition, more personal data sent out.
- **Geocoding on the phone (Android `Geocoder`)** — uses Google services on most phones (ADR-0006) and is absent on
  others.
- **No addresses** — the customer chose addresses (Q-029).
