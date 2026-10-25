---
status: accepted  # M0-06: invariants 1–8 and 10 pass end to end in integration-tests/, 9 at server level (M0-05)
updated: 2026-10-23
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

## Entity types

| Type (wire name) | Written by clients via sync? | Written via REST (admin) | Notes |
| --- | --- | --- | --- |
| `household` | no | `PATCH /api/v1/household` | One row per household |
| `member` | no | members endpoints | References a `person` |
| `group` | no | groups endpoints | Embeds `permissions` and `memberIds` |
| `person` | yes | — | Relatives and member profiles |
| `tag` | yes | — | |
| `task` | yes | — | Tasks module (M1-02); entity-level rules `TaskRules`; delete tombstones its items and completions |
| `checklist_item` | yes | — | Child of a `task` |
| `task_completion` | yes | — | Child of a `task`; immutable, undo deletes it |
| `shopping_list` | yes | — | Shopping module (M2-02); delete tombstones its items |
| `shopping_item` | yes | — | Child of a `shopping_list`; kept after it is bought (ADR-0021) |
| `event` | yes | — | Calendar (M3-02); entity-level rules `EventRules`; delete tombstones its occurrence and reminder rows |
| `event_occurrence` | yes (upsert only) | — | Child of an `event`; id derived from event id and date (ADR-0022) |
| `event_reminder` | yes (upsert only) | — | Child of an `event`; id derived from event and member; visible to its member only (ADR-0023) |
| `place` | yes | — | Location (M4-02); `location.manage_places` writes, `location.view` sees (`PlaceRules`) |
| `place_alert` | yes (upsert only) | — | Arrive / leave alerts; id derived from member and subscriber; visible to its subscriber only (ADR-0023 pattern); tombstoned when either member is removed |

Member data includes `login` and `hasPassword`, so password changes and resets also give the member a new seq.
Tag name uniqueness uses PostgreSQL `lower()`, which needs a UTF-8 database locale (ctype) to fold Cyrillic.

Later modules add types. Every type has a JSON data schema defined in `contract/`
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

An incremental pull (`after > 0`) without `accessVersion` or `epoch` is treated like a mismatch and also resets: the client
cannot show that its data is current.

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

Decoding is strict on the server: unknown field names, missing required fields (for a create) and values that the lenient
client decoders would map to a fallback (e.g. an unknown color) are rejected with `VALIDATION_FAILED` and per-field codes.
Text fields are normalized before validation (names are trimmed). An upsert that leaves the stored data unchanged is an
`applied` no-op with `seq: null`. An unknown `type` or a malformed `mutationId` / `id` is `VALIDATION_FAILED`; a malformed
`mutationId` cannot be logged.

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
- In the background, the same signal arrives as a content-free UnifiedPush message `{"kind":"sync","seq":N}` (M0-09):
  after household changes the server sends it to every registered session of the household, the originating one
  included, **at most once per 30 s per household with a trailing send** (changes inside a window are pushed when it
  ends). The phone enqueues an expedited one-time sync work; WorkManager also syncs every 15 min while logged in.

## Client data model (SQLite)

- **Typed tables** per entity type (`household`, `member`, `group_entity`, `person`, `tag`, `task`, `checklist_item`,
  `task_completion`, `shopping_list`, `shopping_item`, `event`, `event_occurrence`, `event_reminder`) — what the UI
  queries. `event_reminder` holds only the member's own rows (ADR-0023), so convergence (invariant 8) compares it with
  the member's own pull.
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

### Client implementation notes (M0-06)

Code: `client/shared/src/commonMain/kotlin/app/lares/client/` (`sync`, `db`, `api`, `auth`, `data`).

- Besides the tables above, `local_session` holds the logged-in member (`Me`), so `WriteRules` and the permission
  evaluator work offline. Until the first sync brings groups, the permissions from login are used.
- A local write merges the change into the **typed row** (what the user sees), not into shadow ⊕ pending: after an
  `applied` push and before the pull, the typed row already contains the applied change while the shadow does not.
- Local writes are coalesced: the sync starts `localWriteDelay` (1 s) after the first write. Failed cycles retry with
  backoff (2 s doubling to 5 min); a new trigger ends the wait early.
- `SyncStatus.state`: `SYNCING` while a cycle runs; `OFFLINE` after a network failure; `ERROR` after a server answer
  (`lastError` holds the code, also for 5xx); otherwise `PENDING` if the outbox is not empty, else `SYNCED`.
- A realtime event triggers a sync only if its `seq` is above the local cursor. `SyncEngine.realtimeConnected` tells
  whether the socket is open. The access token is refreshed before connecting if it expires within a minute, because a
  failed WebSocket upgrade carries no error body.
- Full resync restarts (up to 3 times) if the server answers `reset` while the pages are being loaded.
- Platform triggers (foreground, connectivity regained, push tickle, periodic work) are wired by the app shell
  (M0-07) and notifications (M0-09); the engine exposes `request()`, `setForeground()` and `syncNow()`.
- Multi-device tests: `integration-tests/` (`SyncInvariantsTest`, `SyncScenariosTest`) run the real server and several
  client cores, each with its own in-memory SQLite and simulated network.

## Versioning

- Every request carries `X-Lares-Protocol: 1`. `GET /api/v1/instance` returns the supported range.
  Outside the range the server answers HTTP 426 / `PROTOCOL_UNSUPPORTED`.
- Clients ignore unknown JSON fields, unknown entity types and unknown enum values (lenient serializers in `contract/`:
  unknown values are dropped from sets or mapped to a fallback), so a newer server never breaks an older client's sync.
- The client stores `dataSchemaVersion` (constant in client code). On mismatch after an app update → full resync.

## Housekeeping (server jobs)

- Daily: purge tombstones older than 90 days for rows nothing references (M0: `tag`, `group`, and `person` rows not referenced
  by any member or invite; M1: `checklist_item`, `task_completion`, then `task`;
  M2: `shopping_item`, then `shopping_list`; M3: `event_reminder`, `event_occurrence`, then `event`; M4: `place_alert`, then `place`; `member` and `household` tombstones are kept); set `purge_horizon_seq = max(purge_horizon_seq, max purged seq)`.
  The purge of a household runs under its sync-state lock, like every writer.
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
8. After sync, every client's typed tables equal the server state (convergence); rows visible only to their member
   (`event_reminder`) are compared per member.
9. A pull racing with concurrent commits never skips a change (single snapshot, `seq ≤ S`).
10. After a restore (new epoch), every client resets; a full sync after a purge does not loop.
