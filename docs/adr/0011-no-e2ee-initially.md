---
status: accepted
date: 2026-10-22
---
# ADR-0011: No end-to-end encryption in initial versions

## Context

Data lives on the family's own server, run by a family member. End-to-end encryption would hide data from the server,
but several features need the server to read data: geofence evaluation (LOC-022), tracker ingestion (TRK),
budget allocation rules (BUD-011), notification content, tag aggregation pages (TAG-004).

## Decision

1. No end-to-end encryption in M0–M8. The server operator is trusted.
2. TLS for all traffic (Caddy, ADR-0005). Passwords hashed with a modern KDF; secrets only in environment/config files.
3. Encryption at rest is the operator's responsibility (disk encryption on the VPS); backup instructions recommend encrypted backups.
4. Data minimization: location history retention (LOC-005), no telemetry.

## Consequences

- Simpler server logic; all features above are possible.
- When one instance hosts several households, the operator can read their data. Documented in the manuals.

## Revisit when

- Hosting households for people who do not trust the operator becomes a real use case;
  candidates for E2EE first: location history, notes.
