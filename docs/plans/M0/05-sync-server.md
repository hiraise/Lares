---
id: M0-05
status: ready
depends_on: [M0-04]
requirements: [FND-030, FND-031, FND-032, FND-041, FND-042, TAG-001, TAG-006]
---
# M0-05: Sync server — pull, push, events, persons, tags

## Goal

Implement the server side of [sync protocol v1](../../architecture/sync.md): pull with reset rules, push with
idempotency and per-mutation validation/permissions, realtime events over WebSocket, housekeeping jobs.
Entity types: `household`, `member`, `group` (read-only via sync), `person`, `tag` (read/write via sync).

## Read first

- [Sync protocol v1](../../architecture/sync.md) — **binding**; implement it exactly
- [ADR-0008](../../adr/0008-offline-first-sync.md), [ADR-0014](../../adr/0014-group-based-permissions.md)
- [Tags spec](../../product/features/tags.md), [foundation spec § persons](../../product/features/foundation.md)
- Code from M0-02..04: `recordChange`, `HouseholdChangeBus`, permission helpers, entity data classes in `contract/`

## Scope

- Contract: sync DTOs, `TagData`/`TagColor` (in `entities/`), tag/person validators, `EntityTypes` constants,
  shared `WriteRules` (permission checks for client-writable types, used by server and client).
- Migration `V4__sync.sql`: `tag`, `sync_mutation_log`.
- `SyncEntityHandler` registry with handlers for the five types.
- Endpoints `GET /api/v1/sync/changes`, `POST /api/v1/sync/mutations`, WebSocket `/api/v1/sync/events`.
- Jobs: tombstone purge, mutation-log cleanup.

Out of scope: client (M0-06), push notifications (M0-09).

## Decisions (do not revisit)

- Wire format, processing order, reset rules, limits, statuses and error codes exactly as in sync.md.
- **Handler interface** (server, package `app.familyhub.server.sync`):
  ```kotlin
  interface SyncEntityHandler {
      val type: String                                   // wire name, e.g. "tag"
      val clientWritable: Boolean
      fun changesSince(tx: JdbcTransaction, householdId: UUID, after: Long, upTo: Long, limit: Int, includeTombstones: Boolean): List<ChangeRow>
      fun canSee(viewer: Viewer, row: ChangeRow): Boolean = true   // M0: all visible
      fun apply(tx: JdbcTransaction, ctx: MutationContext, mutation: Mutation): Long?  // new seq, null for a no-op; throws ApiException to reject
  }
  ```
  `ChangeRow(type, id, seq, deleted, data: JsonObject?)`. Read-only handlers throw `FORBIDDEN` from `apply`.
- `TagColor` is a fixed palette enum (`RED, ORANGE, YELLOW, GREEN, TEAL, BLUE, PURPLE, PINK, BROWN, GRAY`);
  `TagData(name, color: TagColor, icon: String? = null, archived: Boolean = false)` in `contract/…/entities/`.
  `TagColor` uses the lenient enum serializer with fallback `GRAY`.
- Tag name: trimmed, 1–40 chars, unique among non-deleted tags of the household, case-insensitive
  (`TAG_NAME_TAKEN`). Enforce with a unique index on `(household_id, lower(name)) WHERE deleted = false`
  **and** a check in the handler (for a clean error).
- Permissions live in **shared code** `contract/…/permissions/WriteRules.kt`:
  `fun canMutate(type: String, op: MutationOp, viewer: Viewer, existingPersonOwner: Boolean): Boolean` where
  `Viewer(memberId, personId, permissions: EffectivePermissions)`. Rules: tag create/update/delete → `tags.manage`;
  person create/delete → `persons.manage`; person update → `persons.manage` **or** the person is the viewer's own profile;
  read-only types (`household`, `member`, `group`) → always false. Handlers call it (→ `FORBIDDEN`); the client calls the same
  function before local writes (M0-06). Deleting a person referenced by a non-deleted member → `PERSON_IS_MEMBER`
  (server-side check). Unit tests for `WriteRules` in `contract/`.
- Pull transaction: run all pull queries in **one read-only transaction with `REPEATABLE READ`** isolation (Exposed
  transaction parameters, or `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY` as the first statement); read
  `S = last_seq` first and pass `upTo = S` to every handler. `cursor = S` when `hasMore = false`.
- Push transaction: every mutation transaction starts with `lockHousehold(householdId)` (M0-02), then the idempotency
  lookup by `(household_id, mutation_id)`.
- `duplicate` is a flag, not a status: `MutationResult(mutationId, status: APPLIED | REJECTED, seq: Long? = null,
  error: ApiError? = null, duplicate: Boolean = false)`.
- Upsert semantics: if the row does not exist, the merged data must decode into the full data class (required fields
  present) → otherwise `VALIDATION_FAILED`. If it exists: merge `fields` into the current data `JsonObject`, decode,
  validate, write all columns. Unknown field names → `VALIDATION_FAILED`.
- WebSocket: Ktor WebSockets plugin, ping 30 s, timeout 60 s. Auth: bearer token on the upgrade request
  (reuse session validation). Per-connection debounce 500 ms. Subscribes to `HouseholdChangeBus` filtered by household.
- Jobs: a coroutine started in `Application.module` with `while(isActive) { runOnce(); delay(24h) }` and an initial
  delay of 5 min; the job functions are plain functions so tests call them directly.

## Steps

1. **Contract** (`contract/…/sync/`): `object EntityTypes { HOUSEHOLD="household"; MEMBER="member"; GROUP="group"; PERSON="person"; TAG="tag" }`;
   `Change(type, id, seq, deleted, data: JsonObject? = null)`; `PullResponse(reset, accessVersion, epoch, changes, cursor, hasMore)`;
   `@Serializable enum class MutationOp { @SerialName("upsert") UPSERT, @SerialName("delete") DELETE }`;
   `Mutation(mutationId, type, id, op, fields: JsonObject? = null, createdAt: Instant)`; `PushRequest(mutations)`;
   `MutationStatus { APPLIED, REJECTED }` (serial names lowercase); `MutationResult(mutationId, status, seq: Long? = null, error: ApiError? = null, duplicate: Boolean = false)`;
   `PushResponse(results)`; `SyncEvent(type: String, seq: Long)`. Also `entities/TagData`, `TagColor`, `Validators.tagName`,
   person validators, `permissions/WriteRules` + `Viewer`.
   Round-trip serialization tests matching the JSON examples in sync.md.
2. **Migration `V4__sync.sql`**: `tag(id, household_id, name, color, icon NULL, archived bool, seq, created_at, updated_at, deleted)`
   + `(household_id, seq)` index + the unique partial index on lower(name); `sync_mutation_log(household_id uuid,
   mutation_id uuid, member_id, status text, seq bigint NULL, error_code text NULL, processed_at timestamptz,
   PRIMARY KEY (household_id, mutation_id))` + index on `processed_at`.
3. **Handlers**: `HouseholdSyncHandler`, `MemberSyncHandler`, `GroupSyncHandler` (data includes permissions and memberIds
   from the join tables), `PersonSyncHandler`, `TagSyncHandler`. Each maps rows ↔ data classes from `contract/`.
4. **Pull service**: validate params (`after ≥ 0`, `1 ≤ limit ≤ 1000`, default 500, optional `epoch`); in one snapshot
   transaction read `S`, `purge_horizon_seq`, `sync_epoch`; check reset rules 1–4 of sync.md in order; query all handlers with
   `after < seq ≤ S`, merge by seq, cut at `limit`; `hasMore` if any handler had more rows; `cursor` = last returned seq if
   `hasMore`, else `S`; apply `canSee`. Return `accessVersion` from the member row and `epoch`.
5. **Push service**: ≤ 100 mutations else `VALIDATION_FAILED`; process sequentially, one transaction each, steps 1–7 of
   sync.md; log every outcome in `sync_mutation_log` (also rejections, so retries return the same result with
   `duplicate = true` — store `error_code`; message can be regenerated). Unknown `type` → rejected `VALIDATION_FAILED`.
   `delete` of a missing row or of another household's row → `applied`, `seq = null`.
6. **Events**: WebSocket route; send `hello` with the household's current `last_seq` on connect; forward bus events.
7. **Jobs**: `purgeTombstones(now)` deletes tombstones older than 90 days only where nothing references them — M0: `tag`,
   `group_entity` (child rows cascade) and `person` rows not referenced by any `member` row; `member` and `household`
   tombstones are kept (sessions, invites and login codes reference members). Sets
   `purge_horizon_seq = max(purge_horizon_seq, max purged seq)` per household; `cleanupMutationLog(now)` deletes rows older than 30 days.
8. **Tests** (integration, HTTP level, using two sessions of one household and one session of another household):
   - Pull after setup returns household, member, groups, person with increasing seqs; `cursor`/`hasMore` paging with `limit=2`.
   - Push tag create → applied with seq; pull shows it; second identical push → `applied`, same seq, `duplicate = true`;
     a rejected mutation retried → `rejected` with the same code and `duplicate = true`.
   - Two concurrent pushes of the same batch → no 5xx; one applies, the other gets duplicates.
   - Field-level LWW: session A sets `color`, session B sets `name` on the same tag → both survive; both set `name` → last wins.
   - Delete vs edit: delete then upsert → `ENTITY_DELETED`; pull returns tombstone.
   - Tag name uniqueness (case-insensitive) → `TAG_NAME_TAKEN`; archived tags still count; deleted tags do not.
   - Upsert of a new entity with missing required fields → `VALIDATION_FAILED`.
   - Permissions: "Kids" member pushing a tag → rejected `FORBIDDEN`; member editing own person → applied; editing another
     person without `persons.manage` → `FORBIDDEN`; deleting a member's person → `PERSON_IS_MEMBER`.
   - Read-only types: push to `member` → rejected `FORBIDDEN`.
   - Isolation: household B pushes an upsert with the id of a household A tag → `ENTITY_NOT_FOUND`; A's tag unchanged;
     pulls never contain the other household's rows.
   - Reset: (a) after purge job with `purge_horizon_seq > after` → `reset=true`; (b) wrong `accessVersion` → reset;
     (c) `after > last_seq` → reset; (d) `epoch` differs from `sync_epoch` → reset; `after=0` never resets and skips tombstones;
     after a purge, a full sync followed by an incremental pull does **not** reset again (no loop).
   - Snapshot: with a test hook that commits a person change between the tag and person table queries, the pull either
     includes it or ends with `cursor` below its seq — the change is never skipped.
   - Purge with a tombstoned member present and sessions referencing it → no FK error; member tombstone kept.
   - Mutation older than 30 days → `MUTATION_EXPIRED`.
   - WebSocket: connect, receive `hello`; push a mutation from another session → receive `changed` with the new seq;
     5 quick mutations → at most a few messages (debounce) with the final seq.
   - Writes through admin REST (e.g. rename household) appear in pull and trigger a WebSocket event.

## Acceptance

- `./gradlew check` passes.
- Invariants 1–7, 9 and 10 of [sync.md § invariants](../../architecture/sync.md#invariants-to-test) have server-level tests
  (invariant 8 — convergence — is proven in M0-06).

## Docs to update

- [sync.md](../../architecture/sync.md) if any detail had to change (explain why in the handoff).
- Foundation spec: FND-030…032 server side done; tags spec TAG-001, TAG-006 server side done (keep `doing` until UI).
- STATUS.md; plan status.

## Stop and report if

- The merge-by-seq pull across tables cannot be made correct with the handler design (do not switch to a different
  sync design without the planning session).

## Handoff

Russian summary: the server can now synchronize data between devices (relatives and tags as first types),
including conflict handling and protection between families; how it was tested; next plan M0-06.
