---
id: M0-06
status: ready
depends_on: [M0-05]
requirements: [FND-040, FND-041, FND-042, FND-043, FND-027]
---
# M0-06: Client data layer and sync engine

## Goal

Build the client core in `client/shared` (Kotlin Multiplatform, no UI yet): local SQLite database, typed API client
with token refresh, secure token storage, the sync engine from [sync.md](../../architecture/sync.md), repositories
exposing `Flow`s, and permission evaluation. Prove convergence with multi-device integration tests against the real server.

## Read first

- [Sync protocol v1](../../architecture/sync.md) — **binding**, especially the client sections
- [Auth design](../../architecture/auth.md) — token handling, error codes
- [API](../../architecture/api.md), [ADR-0006](../../adr/0006-no-proprietary-dependencies.md), [ADR-0008](../../adr/0008-offline-first-sync.md)
- [versions.md](versions.md) — SQLDelight, Ktor client, Koin, DataStore
- Contract DTOs and entity data classes from M0-02..05

## Scope

- `client/shared` packages under `app.familyhub.client`: `db` (SQLDelight), `api`, `auth`, `sync`, `data` (repositories),
  `di` (Koin modules), `platform` (expect/actual).
- New JVM module `integration-tests/` with multi-device sync tests (server + several client instances in one JVM).

Out of scope: UI (M0-07/08), push (M0-09), WorkManager scheduling (M0-09).

## Decisions (do not revisit)

- **SQLDelight** database `FamilyHubDb`, schema files in `client/shared/src/commonMain/sqldelight/app/familyhub/client/db/`.
  Drivers: Android `AndroidSqliteDriver`, JVM `JdbcSqliteDriver` (in-memory for tests, file for desktop later).
- Typed tables: `household`, `member`, `group_entity`, `person`, `tag`; lists (`permissions`, `memberIds`, `contacts`)
  stored as JSON text columns. Sync tables exactly as in sync.md (`sync_shadow`, `sync_outbox`, `sync_state`, `sync_rejection`).
- Generic materialization via JSON: entity data = `shadow.data` (JsonObject) merged with pending `fields` in outbox order,
  decoded with the contract data class serializer. One `EntityAdapter<T>` per type:
  `type`, `serializer: KSerializer<T>`, `upsertTyped(db, id, data: T)`, `deleteTyped(db, id)`, `validate(data: T): Map<String, FieldErrorCode>`.
- **API client**: Ktor client with `FamilyHubJson`, header `X-FamilyHub-Protocol`, bearer access token. On
  `SESSION_EXPIRED` or `UNAUTHENTICATED` (for a request that carried a token) → single-flight refresh (Mutex) → retry once.
  On `SESSION_REVOKED`, or if the refresh fails with any 401 → emit `AuthEvent.LoggedOut`, clear tokens and local database.
  Persist the new token pair **before** using it (refresh grace window, auth.md). Non-2xx → `ApiException(ErrorCode, fields)` decoded from the body.
  Engines: OkHttp on Android, CIO on JVM.
- **Token storage**: `expect interface`-style abstraction `TokenStore { suspend fun load(): Tokens?; suspend fun save(t: Tokens); suspend fun clear() }`.
  Android: DataStore Preferences with values encrypted by an AES-GCM key from Android Keystore (alias `familyhub_tokens`).
  JVM: in-memory implementation. (No `EncryptedSharedPreferences` — deprecated.)
- `ServerStore` (DataStore on Android, in-memory on JVM): server base URL, device id (UUIDv7 generated once).
- **Sync engine**: one instance per logged-in session; single-flight `syncNow()` guarded by a Mutex; triggers sent to a
  conflated `Channel`; backoff 2 s doubling to 5 min; realtime via Ktor client WebSockets (connect while
  `foreground = true`, reconnect with backoff). Exposes `status: StateFlow<SyncStatus>`:
  `SyncStatus(state = SYNCED | PENDING | SYNCING | OFFLINE | ERROR, pendingCount, lastSuccessAt, lastError: ErrorCode?)`.
- `dataSchemaVersion = 1` constant; mismatch with stored value → full resync on next sync.
- `sync_state` also stores `epoch`; it is sent on every incremental pull and replaced after a reset/full sync.
- Push results: `status = applied` (also with `duplicate = true`) → delete the outbox entry; `status = rejected`
  (also duplicate) → delete the entry, rematerialize, store a rejection.
- **DI**: Koin modules `sharedModule(platformModule)`; no global singletons outside Koin.
- Coroutines: all DB work on `Dispatchers.IO` (`Dispatchers.Default` where IO is unavailable); repositories return
  `Flow` via SQLDelight coroutines extensions.

## Steps

1. **Schema** (`.sq` files): typed tables with columns mirroring the data classes + `id TEXT PRIMARY KEY`;
   sync tables; queries: select all / by id (as Flow), upsert, delete, outbox enqueue/peek(100)/delete/byEntity,
   shadow upsert/get, state get/put, rejection insert/list unseen/mark seen; `deleteAll` for each table.
2. **Entity adapters** for `household`, `member`, `group`, `person`, `tag` (+ registry by type). `member`, `group`,
   `household` adapters are read-only for local writes (throw if a local write is attempted).
3. **Materializer**: `rematerialize(type, id)` reads shadow + pending mutations for the entity, merges JsonObjects
   (later fields override earlier), applies delete rules, decodes, and writes/deletes the typed row.
   Unit tests: shadow only; shadow + 2 patches; pending delete; tombstoned shadow; new entity with no shadow.
4. **LocalWriter**: `suspend fun <T> upsert(type, id, fields: JsonObject)` and `delete(type, id)`; one transaction:
   check `WriteRules.canMutate` from `contract/` with the current member's effective permissions (forbidden → throw
   `LocalForbiddenException`), build merged data, validate (shared validators via adapter; invalid → throw `LocalValidationException(fields)`),
   write typed row, enqueue outbox (mutationId = UUIDv7, createdAt = now); then `syncEngine.request()`.
   Convenience use cases: `TagsWriter.create(name, color)`, `rename`, `recolor`, `archive`, `delete`;
   `PersonsWriter.create(PersonData)`, `update(id, changes)`, `delete(id)`. IDs are UUIDv7 from `contract/`.
5. **API client** (`api/FamilyHubApi.kt`): functions for every endpoint in [api.md](../../architecture/api.md) used by the app
   (instance, setup, login, login-code, refresh, logout, me, password, sessions, delete me, household admin, members,
   groups, invites, login codes, sync changes/mutations) + `events()` returning a `Flow<SyncEvent>` over WebSocket.
   Mock-engine unit tests: protocol header present; refresh-on-expired retries once; revoked → LoggedOut; error decoding.
6. **SyncEngine**: push → pull → reset logic exactly as sync.md (including "applied: keep typed row, delete outbox entry"
   and "rejected: rematerialize + store rejection"); save cursor/accessVersion per page in the same transaction as the
   page; full resync loads all pages into memory then replaces typed tables and shadows in one transaction, keeps the
   outbox and rematerializes pending entities. Public API: `start(scope)`, `stop()`, `request()`, `setForeground(Boolean)`,
   `status`, `rejections: Flow<List<Rejection>>`.
7. **Repositories** (`data/`): `HouseholdRepository.observe()`, `MembersRepository.observeAll()` (joined with persons for
   display names), `GroupsRepository.observeAll()`, `PersonsRepository.observeAll()/observe(id)`,
   `TagsRepository.observeAll(includeArchived)`, `PermissionsRepository.observeMine(): Flow<EffectivePermissions>`
   (evaluates synced groups for the current member with `PermissionEvaluator` — works offline).
8. **Session layer** (`auth/SessionManager.kt`): `connect(serverUrl)` → instance info; `setup(...)`, `login(...)`,
   `loginWithCode(...)`, `acceptInvite(...)`, `logout()`; on success stores tokens and starts the sync engine;
   exposes `state: StateFlow<SessionState>` (`NoServer`, `ServerKnown(info)`, `LoggedIn(me)`).
   On logout/revocation: stop engine, clear tokens, `deleteAll` local tables.
9. **Koin wiring** for all of the above; Android and JVM platform modules provide drivers, stores, HTTP engines.
10. **Integration tests module** `integration-tests/` (add `include(":integration-tests")` to settings; JVM, depends on `:server` test fixtures — expose the M0-02 test harness
    via `java-test-fixtures` in `server` — and `:client:shared` JVM target). Start the server on a random port
    (embedded Netty against the Testcontainers DB), create a household via setup, add members via admin API,
    then create 2–3 independent client stacks (each with its own in-memory SQLite and token store). Tests:
    - Invariants 1–7 of sync.md end to end, plus **invariant 8: convergence** — after all clients sync, their typed tables
      equal each other and the server pull from 0.
    - Offline simulation: a client whose HTTP calls fail (toggle a flag in a test engine wrapper) writes locally, UI-level
      flows show the change immediately and `SyncStatus.pendingCount > 0`; after "network returns" it converges.
    - Realtime: client B observes client A's tag within 2 s while B's WebSocket is connected (without manual `syncNow`).
    - Reset: server-side purge job + horizon → client performs full resync and keeps a pending outbox mutation.
    - Revocation: admin removes member C → C's next call yields LoggedOut and C's local tables are empty.
    - Permissions offline: groups synced; `PermissionsRepository` reflects a group change after sync.

## Acceptance

- `./gradlew check` passes, including `:integration-tests:test` (Docker running).
- No Android-only APIs in `commonMain`.

## Docs to update

- [sync.md](../../architecture/sync.md): set `status: accepted` if all invariants pass; set
  [ADR-0008](../../adr/0008-offline-first-sync.md) to `accepted` and update the [ADR index](../../adr/README.md).
- [architecture overview](../../architecture/overview.md): add the `integration-tests/` module to the layout.
- AGENTS.md "Commands": how to run integration tests.
- Foundation spec: FND-040…043 and FND-027 → `done` (client side) or keep `doing` with a note if UI is required.
- STATUS.md; plan status.

## Stop and report if

- SQLDelight or Ktor client cannot be configured for both Android and JVM targets with the catalog versions.
- An invariant cannot be satisfied with the design in sync.md — report with a failing test; do not change the protocol.

## Handoff

Russian summary: the app core can now work without network and synchronize between several devices; this was proven
by automated tests with several simulated phones; deviations; next plan M0-07.
