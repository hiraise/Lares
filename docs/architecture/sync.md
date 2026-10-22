---
status: draft  # becomes "accepted" together with ADR-0008 when the M0 multi-device tests pass
updated: 2026-10-22
---
# Sync protocol v1

Concrete design for [ADR-0008](../adr/0008-offline-first-sync.md). Covers FND-040…043.
Implemented in M0 plans [M0-05](../plans/M0/05-sync-server.md) (server) and [M0-06](../plans/M0/06-client-data-sync.md) (client).

## Summary

- State-based sync with a per-household change sequence (`seq`).
- Every syncable row stores the `seq` of its last change; deletions are tombstones.
- Clients **pull** rows with `seq > cursor` and **push** field-level mutations from an outbox.
- Field-level last-writer-wins in server arrival order. Server validates and may reject.
- Clients keep a **shadow** copy of the last known server state and rebuild rows as `shadow ⊕ pending mutations`.

## Entity types (M0)

| Type (wire name) | Written by clients via sync? | Written via REST (admin) | Notes |
| --- | --- | --- | --- |
| `household` | no | `PATCH /api/v1/household` | One row per household |
| `member` | no | members endpoints | References a `person` |
| `group` | no | groups endpoints | Embeds `permissions` and `memberIds` |
| `person` | yes | — | Relatives and member profiles |
| `tag` | yes | — | |

Later modules add types (`task`, `shopping_list`, …). Every type has a JSON data schema defined in `contract/`
as a `@Serializable` data class; wire data is that class serialized without `id`, `seq`, `deleted`.

## Server data model

Every syncable table has these columns:

| Column | Type | Meaning |
| --- | --- | --- |
| `id` | uuid PK | Client-generated UUIDv7 (server-generated for REST-created rows) |
| `household_id` | uuid not null | Tenant (ADR-0010) |
| `seq` | bigint not null | Change sequence of the last change |
| `created_at`, `updated_at` | timestamptz | Server time |
| `deleted` | boolean not null default false | Tombstone |

Index: `(household_id, seq)` on every syncable table.

Support tables:

- `household_sync_state(household_id PK, last_seq bigint, purge_horizon_seq bigint, sync_epoch uuid)` — `sync_epoch` changes
  when the database is restored from a backup.
- `sync_mutation_log(household_id, mutation_id, member_id, status, seq null, error_code null, processed_at, PK(household_id, mutation_id))`
  — idempotency, kept 30 days.
- `member.access_version bigint` — bumped whenever what the member may see changes.

### Allocating `seq`

Inside the writing transaction:

```sql
UPDATE household_sync_state SET last_seq = last_seq + 1 WHERE household_id = ? RETURNING last_seq
```

The row lock is held until commit, so writes in one household are serialized and **commit order equals seq order**.
A reader can therefore never see `seq = N+1` committed while `N` is still pending. Every write to a syncable table —
from sync mutations or from REST admin endpoints — goes through one helper (`ChangeRecorder`) that allocates the seq,
stamps the row, and registers an after-commit notification.

## Pull

`GET /api/v1/sync/changes?after={cursor}&limit={n}&accessVersion={v}&epoch={e}`

- `after`: last cursor the client applied (0 for a full sync). `limit`: default 500, max 1000. `epoch`: the sync epoch the
  client's data belongs to (omitted on a full sync).
- The whole pull runs in **one read-only `REPEATABLE READ` transaction** (one snapshot). It first reads `S = last_seq`,
  `purge_horizon_seq` and `sync_epoch` from `household_sync_state`, then queries every registered syncable table for
  `after < seq ≤ S` (each with `LIMIT n`), merges by seq and takes the first `n`. Separate snapshots per table could miss
  a change committed between two table queries.
- Full sync (`after = 0`) skips tombstones.

Response:

```json
{
  "reset": false,
  "accessVersion": 7,
  "epoch": "01928c40-…",
  "changes": [
    { "type": "tag", "id": "01928c4e-…", "seq": 42, "deleted": false, "data": { "name": "Car", "color": "blue", "archived": false } },
    { "type": "person", "id": "01928c4f-…", "seq": 43, "deleted": true }
  ],
  "cursor": 43,
  "hasMore": false
}
```

- `cursor`: if `hasMore`, the seq of the last returned change; otherwise `S`. Cursors therefore move past filtered rows and
  skipped tombstones.
- Rows the member may not see are filtered out but still advance the cursor (M0: everything is visible to all members).

### Reset

The server answers `{"reset": true, "changes": [], "cursor": 0, "hasMore": false, "accessVersion": v, "epoch": e}` when, with `after > 0`:

1. `after < purge_horizon_seq` — the client may have missed a purged tombstone;
2. `accessVersion` differs from the member's current `access_version` — visibility changed;
3. `after > last_seq` — the server was restored from an older backup;
4. `epoch` differs from `sync_epoch` — the database was restored (the restore procedure sets a new epoch; rule 3 alone
   misses restores once other clients have written past the stale cursor).

The client then performs a **full resync** (see client section).

## Push

`POST /api/v1/sync/mutations`

```json
{
  "mutations": [
    { "mutationId": "01928c50-…", "type": "tag", "id": "01928c4e-…", "op": "upsert",
      "fields": { "name": "Car", "color": "blue" }, "createdAt": "2026-10-22T10:15:00Z" },
    { "mutationId": "01928c51-…", "type": "person", "id": "01928c4f-…", "op": "delete",
      "createdAt": "2026-10-22T10:16:00Z" }
  ]
}
```

- At most 100 mutations per request, in outbox order.
- `op`: `upsert` (create if missing — then all required fields must be present — otherwise patch the given fields)
  or `delete` (tombstone). Later modules may add domain ops (e.g. `check_item`) per type.
- `fields`: only changed fields, JSON values in the type's data schema.

Processing, in order, **each mutation in its own transaction**:

1. Lock: `SELECT … FROM household_sync_state WHERE household_id = ? FOR UPDATE` first — serializes mutations of a household
   and removes races on idempotency and uniqueness checks.
2. Idempotency: if `(household_id, mutationId)` is in `sync_mutation_log` → return the original result with `duplicate: true`.
3. Expiry: `createdAt` older than 30 days → `rejected` / `MUTATION_EXPIRED`.
4. Load the row by `id`. Exists in **another household**: `upsert` → `rejected` / `ENTITY_NOT_FOUND`; `delete` → `applied`
   no-op. Does not exist: `delete` → `applied` no-op; `upsert` → create.
5. Tombstoned row: `upsert` → `rejected` / `ENTITY_DELETED`; `delete` → `applied` no-op.
6. Permission check with the shared `WriteRules` from `contract/` (ADR-0014); validation of the merged entity (shared validators).
7. Apply fields, allocate seq, write, log the mutation → `applied` with `seq`.

No-op applies return `seq: null`. Every outcome (applied or rejected) is logged, so a retry returns the same result.

On an unexpected server error the request fails with HTTP 5xx; already applied mutations are committed and logged,
so the client simply retries the whole batch.

Response:

```json
{
  "results": [
    { "mutationId": "01928c50-…", "status": "applied", "seq": 44 },
    { "mutationId": "01928c51-…", "status": "rejected", "error": { "code": "ENTITY_NOT_FOUND", "message": "…" } },
    { "mutationId": "01928c4a-…", "status": "applied", "seq": 40, "duplicate": true }
  ]
}
```

`status` is `applied` or `rejected`; `duplicate: true` marks a result replayed from the log.

## Realtime events

WebSocket `GET /api/v1/sync/events` (bearer token on the upgrade request).

- Server → client: `{"type":"hello","seq":N}` on connect, `{"type":"changed","seq":N}` after each commit in the household
  (debounced to at most one message per 500 ms per connection).
- Ping every 30 s. The client keeps the socket open only while the app is in the foreground.
- In the background, the same "changed" signal arrives as a content-free push (M0-09).

## Client data model (SQLite)

- **Typed tables** per entity type (`household`, `member`, `group_entity`, `person`, `tag`) — what the UI queries.
- `sync_shadow(type, id, seq, deleted, data, PK(type, id))` — last known server state as JSON.
- `sync_outbox(local_seq INTEGER PK AUTOINCREMENT, mutation_id UNIQUE, type, entity_id, op, fields, created_at, attempts, last_error)`.
- `sync_state(key PK, value)` — `cursor`, `accessVersion`, `epoch`, `dataSchemaVersion`, `lastSuccessAt`, `lastError`.
- `sync_rejection(id, mutation_id, type, entity_id, code, message, at, seen)` — shown to the user.

**Materialization rule:** typed row = decode(`shadow.data` ⊕ every pending outbox `fields` for that entity, in order).
If the shadow is a tombstone or any pending op is `delete`, the typed row is absent.

### Local write

One SQLite transaction: build the merged entity, check `WriteRules` and validate with the shared validator (forbidden or
invalid → error to the UI, nothing stored), write the typed row, append the mutation to the outbox. Then request a sync (debounced 1 s).

### Sync cycle (single-flight, coalesced)

1. **Push** outbox in batches of 100 until empty:
   - `applied` (also with `duplicate: true`) → delete the outbox entry (keep the typed row; the pull brings authoritative state);
   - `rejected` → delete the outbox entry, rematerialize the entity from shadow + remaining pending, store a `sync_rejection`;
   - network error / 5xx → stop, keep the outbox, back off.
2. **Pull** pages until `hasMore = false`. Each page in one transaction: upsert shadows, rematerialize each touched entity,
   save `cursor` and `accessVersion`.
3. `reset = true` → **full resync**: pull all pages from `after = 0` into memory, then in one transaction replace all
   typed tables and shadows, rematerialize entities with pending outbox entries, save cursor. The outbox is kept.

Triggers: app start, local write, WebSocket `changed`, connectivity regained, push tickle, periodic background work.
Backoff after failures: 2 s doubling to 5 min; reset on success.

## Versioning

- Every request carries `X-FamilyHub-Protocol: 1`. `GET /api/v1/instance` returns the supported range.
  Outside the range the server answers HTTP 426 / `PROTOCOL_UNSUPPORTED`.
- Clients ignore unknown JSON fields, unknown entity types and unknown enum values (lenient serializers in `contract/`:
  unknown values are dropped from sets or mapped to a fallback), so a newer server never breaks an older client's sync.
- The client stores `dataSchemaVersion` (constant in client code). On mismatch after an app update → full resync.

## Housekeeping (server jobs)

- Daily: purge tombstones older than 90 days for rows nothing references (M0: `tag`, `group`, and `person` rows not referenced
  by any member; `member` and `household` tombstones are kept); set `purge_horizon_seq = max(purge_horizon_seq, max purged seq)`.
- Restore procedure (`deploy/scripts/restore.sh`): after `pg_restore`, set a new `sync_epoch` for every household.
- Daily: delete `sync_mutation_log` rows older than 30 days.

## Invariants to test

1. Two clients edit different fields of one entity offline → both changes survive.
2. Two clients edit the same field → the later arrival wins on both clients after sync.
3. Rejected mutation → the client reverts to server state and shows the rejection.
4. Retried batch → no duplicates (idempotency).
5. Delete vs edit race → entity ends deleted on all clients.
6. Reset conditions 1–3 → client performs full resync and keeps its outbox.
7. Mutation with an id from another household → rejected, no data leak.
8. After sync, every client's typed tables equal the server state (convergence).
9. A pull racing with concurrent commits never skips a change (single snapshot, `seq ≤ S`).
10. After a restore (new epoch), every client resets; a full sync after a purge does not loop.
