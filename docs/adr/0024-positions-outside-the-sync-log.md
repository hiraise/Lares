---
status: accepted
date: 2026-10-25
---
# ADR-0024: Positions and sharing state travel outside the sync log

## Context

Phones upload a position about every 30 s while moving (LOC-007; ADR-0013 budgets ≈ 0.3 writes/s for a family). Who
may see a position depends on visibility rules (LOC-006, ADR-0025) that administrators change at any time. The sync log
(ADR-0008, sync.md) gives every write a household `seq`, wakes every phone of the household with a push tickle at most
every 30 s, keeps rows on every phone and assumes that a row's visibility only changes with an access-version bump.
Positions would wake all phones all the time, fill every phone with others' history and need resync logic whenever a
rule changes. FND-040 already exempts live location from offline work.

## Decision

1. Positions are written by `POST /api/v1/location/positions` (batches, idempotent on member + time) into an
   append-only `position` table, deleted after the household's retention period (LOC-005). They never get a `seq`.
2. The member's sharing state (sharing, paused until, off on the phone with a reason) and the newest position live in
   `location_state`, written by the upload and `PUT /location/me/sharing`.
3. Viewers read `GET /location/now` and `GET /location/history`, and get changes over a separate WebSocket,
   `/api/v1/location/live`, opened only while a screen with locations is visible. The server computes each viewer's
   visibility and sends a member's data only to connections allowed to see it (`snapshot`, `member`, `gone`).
4. One phone per member shares: the session that last set SHARING. Uploads from other sessions are ignored and told to
   stop (LOC-019).
5. «Обновить» marks a request on the member's state and sends a data-only push to the sharing session; the next upload
   response also carries the request, so it arrives even without a push distributor.
6. Places (`place`) and arrive/leave subscriptions (`place_alert`, owner-only like ADR-0023) are ordinary sync types:
   small, rarely written, needed offline.
7. Phones of viewers keep only the last snapshot (for «Нет связи»), never histories.

## Consequences

- No tickles, no resync and no access-version bumps for position traffic or rule changes.
- A second realtime endpoint and an in-process `LocationHub` on the server; the sync WebSocket stays unchanged.
- The map needs a connection; offline it shows the last snapshot (LOC-009).
- Load test re-run in M4 measures the upload path (ADR-0015).

## Alternatives considered

- **Positions as a sync type** — wakes every phone every 30 s, stores histories on phones that may not see them, and
  visibility changes would need resets.
- **Polling only** — simpler, but the map would lag by the poll interval and cost more requests than a socket.
- **Positions over the existing sync WebSocket** — mixes per-viewer filtered data into a household-wide signal channel
  that older clients decode strictly.
