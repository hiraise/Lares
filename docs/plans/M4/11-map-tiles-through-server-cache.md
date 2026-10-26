---
id: M4-11
status: done
depends_on: [M4-10]
requirements: [LOC-009]
---
# M4-11: Map tiles through a caching proxy on the family server (ADR-0029)

## Goal

The map works on phones that cannot reach public tile hosts (customer's Huawei and Xiaomi in Russia: black map, bare
streets, pins without initials). Phones load style, tiles, glyphs and sprites only from `https://<domain>/map/…`; an
nginx cache on the server fetches them on demand from one upstream (VersaTiles by default), keeps them, and drops what
nobody used for 7 days. Pin initials and place labels use the style's own font.

## Read first

- [ADR-0029](../../adr/0029-map-tiles-through-a-server-cache.md) (whole), ADR-0006 §1, ADR-0027 §4
- [Location design](../../architecture/location.md) § Configuration (`AppConfig`), § Map rendering
- Code: `server/…/platform/AppConfig.kt` (`LocationConfigValues`), `LocationService.config`, contract `LocationConfig`,
  `client/shared/src/androidMain/…/ui/location/FamilyMap.android.kt` (`LABEL_FONT`), `ui/location/FamilyMap.kt`
  (`MapViewState`), `deploy/docker-compose.yml`, `deploy/caddy/Caddyfile*`, `deploy/.env.example`,
  `deploy/scripts/smoke.sh`, `docs/user/*/install-server.md` § Map

## Decisions (do not revisit)

- **Contract:** `LocationConfig.labelFont: String? = null` (appended, optional). Client fallback `Noto Sans Bold`.
- **Server config** (`LocationConfigValues`): `mapUpstream` (`LARES_MAP_UPSTREAM`, default `https://tiles.versatiles.org`,
  https, no trailing slash), `mapProxy` (`LARES_MAP_PROXY`, `true`/`false`, default `false`), `labelFont`
  (`LARES_MAP_LABEL_FONT`, default `noto_sans_bold`, blank = null), defaults
  `DEFAULT_MAP_STYLE_URL = https://tiles.versatiles.org/assets/styles/colorful/style.json`,
  `DEFAULT_MAP_DARK_STYLE_URL = …/colorful-dark/style.json`. Pure `LocationConfigValues.clientUrl(url, publicUrl)`:
  with `mapProxy` and `url` starting with `"$mapUpstream/"` → `"$publicUrl/map/" + rest`; else `url`.
  `LocationService.config` returns both style URLs through `clientUrl` and `labelFont`.
- **nginx** (`deploy/mapcache/nginx.conf.template`, image `nginx:1.29-alpine` pinned like the others, rendered by the
  image's envsubst templates with `LARES_MAP_UPSTREAM`, `LARES_MAP_UPSTREAM_HOST`, `LARES_PUBLIC_URL`,
  `LARES_MAP_CACHE_SIZE`; `NGINX_ENVSUBST_FILTER=LARES_`):
  - `proxy_cache_path /var/cache/lares-map levels=1:2 keys_zone=map:16m max_size=${LARES_MAP_CACHE_SIZE} inactive=7d
    use_temp_path=off;` `limit_req_zone $binary_remote_addr zone=mapreq:1m rate=50r/s;` `resolver 127.0.0.11 valid=300s
    ipv6=off;` (Docker DNS; the upstream is resolved per request through a variable, so a DNS failure at start does
    not stop nginx).
  - `listen 8080`; `location /map/` only (everything else 404); `limit_req burst=400 nodelay`; strip `/map` and
    `proxy_pass $upstream$path$is_args$args` with `proxy_ssl_server_name on`, `Host` = upstream host, `User-Agent:
    Lares-mapcache (+https://github.com/hiraise/Lares)`; `proxy_cache map`, `proxy_cache_key $path$is_args$args`,
    `proxy_cache_valid 200 6h`, `proxy_cache_valid 404 5m`, `proxy_cache_revalidate on`, `proxy_cache_lock on`,
    `proxy_cache_use_stale error timeout updating http_429 http_500 http_502 http_503 http_504`,
    `proxy_ignore_headers Set-Cookie`, `add_header X-Lares-Map $upstream_cache_status always`.
  - Paths ending in `.json` (styles, TileJSON, sprite index): `Accept-Encoding ""` to the upstream, `sub_filter
    '${LARES_MAP_UPSTREAM}' '${LARES_PUBLIC_URL}/map'`, `sub_filter_once off`, `sub_filter_types application/json`.
    Other paths: `Accept-Encoding gzip` (stored compressed, passed on as is).
- **Compose:** service `mapcache` (`mem_limit: 32m`, volume `map_cache`, env from `.env` with defaults
  `LARES_MAP_UPSTREAM=https://tiles.versatiles.org`, `LARES_MAP_UPSTREAM_HOST=tiles.versatiles.org`,
  `LARES_MAP_CACHE_SIZE=2g`); the server gets `LARES_MAP_PROXY: "true"`, `LARES_MAP_UPSTREAM`, `LARES_MAP_LABEL_FONT`.
  Both Caddyfiles: `handle /map/* { reverse_proxy mapcache:8080 }` before the server; `encode` stays.
- **Client:** `MapViewState.labelFont: String?` from `config.labelFont`; `FamilyMap.android.kt` uses it for both
  symbol layers. No other client change: the style URLs come from the server as before.
- **Dev server** (`tools/dev/server.sh`): unchanged (`LARES_MAP_PROXY` unset → direct VersaTiles URLs).
- **Smoke test:** the stack starts with `mapcache`; check `GET https://localhost/map/assets/styles/colorful/style.json`
  answers with the header `X-Lares-Map` (any status: CI's network may fail the upstream) and that a path outside
  `/map/` still reaches the Lares server.

## Steps

1. Contract `labelFont`; server config, `clientUrl`, `LocationService.config`; tests.
2. `deploy/mapcache/`, Compose, Caddyfiles, `.env.example`, smoke test.
3. Client `labelFont`.
4. Docs: install manual § Map (en, ru: on-demand cache, 7 days, size, what the upstream learns, switching the
   source, e.g. back to OpenFreeMap), location design (configuration table, implementation notes M4-11), ADR index,
   CHANGELOG, user manual "Map" § Addresses and privacy (tiles through the family server).
5. Tests: `AppConfigTest` (defaults, `LARES_MAP_PROXY`, invalid upstream), `clientUrl` (proxied, other origin
   unchanged, proxy off), `LocationRoutesTest`/config test (proxied URLs and `labelFont` in `/location/config`),
   `LocationConfig` decoding without `labelFont`.
6. Check: `deploy/scripts/smoke.sh` locally; a manual check with the real upstream from this Mac is not possible
   (`tiles.versatiles.org` works here, so run the stack with `LARES_DOMAIN=localhost` and fetch a style and a tile
   through `/map/`, then the same tile again → `X-Lares-Map: HIT`).

## Acceptance

- `tools/dev/verify.sh` full gate and `smoke.sh` green; style and tile through the local stack, second fetch a HIT,
  URLs inside the style point at `https://localhost/map/`.

## Stop and report if

- The upstream needs a key or forbids caching; maplibre on Android rejects the rewritten style.
