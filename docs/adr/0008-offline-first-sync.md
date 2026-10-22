---
status: proposed  # direction chosen; protocol details finalized by an M0 prototype
date: 2026-10-22
---
# ADR-0008: Offline-first client with server-authoritative sync

## Context

FND-040…043: tasks, shopping lists, calendar and budget must work offline and sync without data loss;
other members' changes must appear without manual refresh. Several phones edit the same household data.

## Decision (direction)

1. **Local-first reads:** the client stores household data in a local SQLite database; the UI reads only from it.
2. **Client-generated IDs:** UUIDv7, so records can be created offline.
3. **Outbox:** local writes are applied to the local DB and appended to an outbox of mutations.
4. **Server is authoritative:** it validates each mutation (permissions, invariants), applies it, and assigns
   a per-household monotonically increasing change sequence number. Rejected mutations return a reason;
   the client reverts the local effect and informs the user.
5. **Pull by cursor:** the client asks for all changes after its last seen sequence number (paged).
   Deletions are tombstones so they propagate.
6. **Conflicts:** field-level last-writer-wins in server arrival order. Where concurrent edits are common
   (checklist items, assignees, shopping items), mutations are domain operations ("check item", "add assignee")
   rather than whole-record overwrites. Financial transactions are append-oriented.
7. **Realtime:** the server notifies connected clients that new changes exist; clients then pull.
8. **Out of scope of this mechanism:** live location (append-only upload of positions, server-side queries).

## To finalize in M0

- Wire format and paging; batching of outbox pushes.
- App/server version skew: protocol version negotiation, schema migrations on the client.
- Permission changes: purging local data a member may no longer see.
- Prototype with multi-device conflict tests before building feature modules on top.

## Consequences

- Every syncable table carries: `id`, `household_id`, `seq`, `updated_at`, `deleted` (tombstone), plus
  optional per-field metadata if needed for LWW.
- Feature modules define their mutations as explicit operations in the contract module.

## Alternatives considered

- **Online-only with cache** — fails the "shopping in a store without network" scenario.
- **CRDTs (Automerge, Yjs)** — strong merge guarantees, but heavy for relational data with server-side rules
  (budget allocation, permissions) and immature in Kotlin.
- **Third-party sync engines (PowerSync, ElectricSQL)** — extra infrastructure for self-hosters and less control
  over permissions; may be re-evaluated in the M0 prototype.
