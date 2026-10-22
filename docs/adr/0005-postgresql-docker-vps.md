---
status: accepted
date: 2026-10-22
---
# ADR-0005: PostgreSQL, Docker Compose, VPS as primary target

## Context

The customer will run the server on a rented VPS. Phones must reach the server from anywhere (location tracking),
so a public HTTPS endpoint is needed. Self-hosters expect a Docker Compose setup.
GPS trackers (if added later) need open TCP/UDP ports, which a VPS provides easily.

## Decision

1. **Database:** PostgreSQL (current major version), the only supported server database.
2. **Deployment:** one `docker-compose.yml` with services `server`, `postgres`, `caddy`; optional `ntfy` (push, ADR-0006)
   and, later, `traccar` (trackers, ADR-0009) behind Compose profiles.
3. **HTTPS:** Caddy with automatic Let's Encrypt certificates; requires a domain pointing at the VPS.
   Running behind an existing reverse proxy (nginx, Traefik) is supported by disabling the `caddy` service.
4. **Primary target:** Linux VPS, x86_64; sizing in ADR-0013 (1 GB minimum, 2 GB recommended). Images are also built for arm64.
5. **Backups:** `pg_dump`-based procedure documented in user manuals; restore tested in CI.

## Consequences

- Home servers and VPN setups work too, but are documented after the VPS path.
- Integration tests run against real PostgreSQL (Testcontainers), see [testing](../process/testing.md).

## Alternatives considered

- **SQLite** — single container and trivial backups, but weaker for concurrent realtime and location writes
  and for several households on one instance.
- **Native packages / bare JAR** — harder to support across distributions; may be added later.
