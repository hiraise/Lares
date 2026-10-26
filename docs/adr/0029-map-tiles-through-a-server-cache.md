---
status: accepted
date: 2026-10-26
---
# ADR-0029: Map tiles come through a caching proxy on the family server; VersaTiles by default

## Context

ADR-0006 chose MapLibre with OpenStreetMap vector tiles, OpenFreeMap by default; ADR-0027 has the phones load the
style URLs the server returns. On the customer's phones in Russia (mobile network, Huawei and Xiaomi) the map was black
or showed bare streets and the pins had no initials: `tiles.openfreemap.org` (behind Cloudflare) is intercepted — an
"Ideco NGFW" certificate on the development network, nothing usable on the mobile network — while `openfreemap.org`
itself opens. Style, tiles, glyphs and sprites all come from that host, so everything drawn with text fails too,
including the pin initials (`LABEL_FONT` is a font name of the OpenFreeMap glyph server).

`tiles.versatiles.org` (VersaTiles, OSM Shortbread tiles, its own servers in Germany) is reachable from the same
networks. It asks heavy users to put a cache in front of it, sends `Cache-Control: max-age=21600` and ETags, has light
and dark styles (`colorful`, `colorful-dark`) with local `name` labels, and needs no key.

The customer asked (2026-10-26) that map data be fetched on demand, kept on the server, and dropped after about a week
without use. Where the family server will run (abroad or in Russia) is not decided yet, so the source must be
replaceable.

## Decision

1. **Phones load the map only from the family server.** A small nginx container `mapcache` in the Compose stack proxies
   `https://<domain>/map/…` to one upstream origin (`LARES_MAP_UPSTREAM`, default `https://tiles.versatiles.org`).
   Caddy routes `/map/*` to it; everything else goes to the Lares server as before.
2. **Cache on demand:** nginx `proxy_cache` on a volume — a tile, glyph, sprite or style is fetched the first time a
   phone asks, kept, revalidated with the upstream's ETag after its `max-age`, and **deleted when nobody asked for it for
   7 days** (`inactive=7d`); the cache is capped (`LARES_MAP_CACHE_SIZE`, default `2g`, least recently used first). When
   the upstream fails, cached data is served stale. Nothing is downloaded in advance.
3. **URL rewriting:** JSON answers (styles, TileJSON, sprite index) are fetched uncompressed and the upstream origin in
   them is replaced with `<public URL>/map`, so tiles, glyphs and sprites also come through the cache. Only paths under
   the one upstream origin are proxied (not an open proxy); requests are rate-limited per IP.
4. **The server returns proxied style URLs:** with `LARES_MAP_PROXY=true` (set by the Compose stack) a configured style
   URL on the upstream origin is returned to phones as `<public URL>/map/<path>`; other URLs are returned unchanged
   (an administrator's own tile server keeps working directly). Default styles become VersaTiles `colorful` and
   `colorful-dark`.
5. **Label font from the server:** `LocationConfig.labelFont` (`LARES_MAP_LABEL_FONT`, default `noto_sans_bold`, the
   VersaTiles name) is the font the app uses for pin initials and place labels, since a style's glyph server knows only
   its own font names. Absent (older servers) = the previous `Noto Sans Bold`.
6. The upstream learns the server's address and which map areas are viewed, not who views them or where anybody is;
   the install manual says so and explains how to switch the source.

## Consequences

- The map works wherever the family server can reach the upstream; blocking between phones and tile hosts no longer
  matters. A server in Russia that cannot reach VersaTiles needs another `LARES_MAP_UPSTREAM` (or a self-hosted tile
  server); that stays configuration, not code.
- One more container (nginx, ~10 MB RAM, `mem_limit: 32m`), within ADR-0013; disk up to the cache cap.
- The first view of a new area is as slow as before (one upstream fetch); later views come from the family server.
- The Lares server itself does not proxy bytes, so its memory budget (M4-05: 254 of 256 MiB) is untouched.
- The dev server (`tools/dev/server.sh`, no Compose) keeps direct URLs (`LARES_MAP_PROXY` unset).
- Supersedes the default source in ADR-0006 §1 (OpenFreeMap → VersaTiles through the cache) and amends ADR-0027 §4
  (the style URLs point at the family server; `labelFont` is added).

## Alternatives considered

- **Yandex Maps** — proprietary SDK with a key and its own data collection, and its tiles may not be used without it
  (ADR-0006, AGENTS.md rules 3–4). Rejected; the customer asked about it.
- **A regional tile file on the server (PMTiles), downloaded in advance** — works without any upstream, but the
  customer prefers on-demand loading, and the region is not known yet. Can be added later as another upstream.
- **Caching proxy inside the Ktor server** — no extra container, but streams bytes through a JVM already at its memory
  budget, and re-implements what nginx `proxy_cache` does (revalidation, stale on error, LRU size cap, inactivity
  expiry).
- **Caddy cache plugin** — needs a custom Caddy build; the stack uses the official image.
- **Keep OpenFreeMap through the cache** — fine for a server abroad, but its host is the one being blocked; VersaTiles
  works in both cases and invites caching. OpenFreeMap stays one setting away.
- **An access key in the map URLs** — would stop strangers from using the cache, but adds a required secret to
  `.env`; the data is public OSM data, only the configured upstream is reachable, and requests are rate-limited.
