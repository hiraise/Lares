---
id: M0-02
status: done
depends_on: [M0-01]
requirements: [FND-002, FND-070]
---
# M0-02: Server foundation

## Goal

Turn the `server` skeleton into a production-shaped Ktor application: configuration, PostgreSQL with migrations,
Exposed, error handling, JSON, protocol header check, logging, health checks, the household change sequence
(`ChangeRecorder`), and a test harness with a real PostgreSQL. No business endpoints yet except `GET /api/v1/instance`.

## Read first

- [ADR-0005](../../adr/0005-postgresql-docker-vps.md), [ADR-0010](../../adr/0010-household-tenancy.md),
  [ADR-0012](../../adr/0012-modular-monolith.md), [ADR-0013](../../adr/0013-server-resource-budget.md)
- [API conventions](../../architecture/api.md) — errors, protocol header
- [Sync protocol](../../architecture/sync.md) — sections "Server data model" and "Allocating seq"
- [versions.md](versions.md), [conventions](../../process/conventions.md), [testing](../../process/testing.md)

## Scope

- Config, DB pool, Flyway, Exposed, JSON, StatusPages, CallId + CallLogging, protocol header plugin, health endpoints.
- Migration `V1__foundation.sql`: `household`, `household_sync_state`.
- `ChangeRecorder` + `HouseholdChangeBus`.
- Contract: `ErrorCode`, `ApiError`, `FieldError`, `InstanceInfo`, `Protocol` constants, shared `LaresJson`.
- Test harness: Testcontainers PostgreSQL, `testApp {}` helper, DB cleanup between tests.
- Local dev database via Docker Compose.

Out of scope: authentication (M0-03), any household/member logic, Dockerfile for the server (M0-10).

## Decisions (do not revisit)

- Ktor with the **Netty** engine, started via `embeddedServer` in `main()` with a typed config object
  (not HOCON/YAML files) — config comes from environment variables.
- **Exposed DSL** (no DAO entities). **Flyway** SQL migrations in `server/src/main/resources/db/migration`.
- **HikariCP** pool: `maximumPoolSize = 5`, `minimumIdle = 1` (resource budget).
- Timestamps in DB: `timestamptz`, mapped to `java.time.Instant` (exposed-java-time). Convert to `kotlin.time.Instant`
  at the API boundary.
- UUID columns use PostgreSQL `uuid`.
- Package root: `app.lares.server`; infrastructure under `app.lares.server.platform`.
- Errors: throw `ApiException(code: ErrorCode, message: String, fields: Map<String, FieldErrorCode> = emptyMap())`;
  StatusPages maps it to HTTP status per [API errors table](../../architecture/api.md#errors) and the JSON body.
  Unknown exceptions → 500 `INTERNAL`, logged with stack trace, body without details.
- Logging: SLF4J + Logback + kotlin-logging. Two config files: `logback.xml` (plain pattern, default) and
  `logback-json.xml` (Logback's built-in `ch.qos.logback.classic.encoder.JsonEncoder` — **no logstash encoder**).
  `main()` sets `System.setProperty("logback.configurationFile", "logback-json.xml")` when `LARES_LOG_FORMAT=json`,
  **before** the first logger is created. Logback 1.6 has no Janino conditionals — do not use `<if>` in configs.
  Never log request bodies, tokens, passwords, coordinates or amounts.

## Steps

1. **Contract additions** (`contract/src/commonMain/kotlin/app/lares/contract/`):
   - `api/Protocol.kt`: `object Protocol { const val VERSION = 1; const val MIN_SUPPORTED = 1; const val HEADER = "X-Lares-Protocol" }`.
   - `api/ErrorCode.kt`: `@Serializable enum class ErrorCode` with every code from the API errors table plus the sync
     codes (`ENTITY_NOT_FOUND`, `ENTITY_DELETED`, `MUTATION_EXPIRED`, `TAG_NAME_TAKEN`, `PERSON_IS_MEMBER`).
     `@Serializable enum class FieldErrorCode { REQUIRED, TOO_LONG, TOO_SHORT, INVALID_FORMAT, OUT_OF_RANGE, TAKEN }`.
   - `serialization/Lenient.kt`: `LenientEnumSerializer<E>(serialName, entries, fallback)` (unknown string → fallback; uses each
     entry's `@SerialName`) and `LenientEnumSetSerializer<E>` (decodes a list of strings, drops unknown values). Newer servers may
     send enum values an older client does not know; decoding must never fail because of that. `ErrorCode` gets an extra entry
     `UNKNOWN` and uses the lenient serializer with that fallback. Tests: `"SOMETHING_NEW"` → `UNKNOWN`; a set with an unknown
     value drops it.
   - `api/ApiError.kt`: `@Serializable data class ApiErrorBody(val error: ApiError)`,
     `@Serializable data class ApiError(val code: ErrorCode, val message: String, val fields: Map<String, FieldErrorCode> = emptyMap())`.
   - `api/InstanceInfo.kt`: `@Serializable data class InstanceInfo(val initialized: Boolean, val serverVersion: String, val protocol: ProtocolRange)`,
     `@Serializable data class ProtocolRange(val min: Int, val max: Int)`.
   - `api/Json.kt`: `val LaresJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }`.
   - Unit test: `ApiErrorBody` round-trips through `LaresJson`.
2. **Config** (`platform/AppConfig.kt`): data class loaded from env with defaults for local dev:
   `LARES_PORT` (8080), `LARES_DB_URL` (`jdbc:postgresql://localhost:5432/lares`), `LARES_DB_USER`
   (`lares`), `LARES_DB_PASSWORD` (`lares`), `LARES_PUBLIC_URL` (`http://localhost:8080`),
   `LARES_LOG_FORMAT` (`plain`), `LARES_SETUP_CODE` (optional, used in M0-03).
   `fun AppConfig.Companion.fromEnv(env: Map<String, String> = System.getenv())`. Validate: port range, URL formats.
3. **Database** (`platform/db/`):
   - `Database.kt`: create `HikariDataSource`, run Flyway `migrate()` (needs both `flyway-core` and
     `flyway-database-postgresql`), `Database.connect(dataSource)`; expose
     `suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
     withContext(Dispatchers.IO) { suspendTransaction(db = database) { block() } }`.
     Exposed 1.x packages: `org.jetbrains.exposed.v1.core.*`, `org.jetbrains.exposed.v1.jdbc.*`,
     `org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction`, `org.jetbrains.exposed.v1.javatime.*`
     (see [versions.md § server notes](versions.md#server-notes)). Do not use the deprecated `newSuspendedTransaction`.
   - `V1__foundation.sql`:
     ```sql
     CREATE TABLE household (
       id uuid PRIMARY KEY,
       name text NOT NULL,
       time_zone text NOT NULL,
       currency char(3) NOT NULL,
       default_language text NOT NULL,
       seq bigint NOT NULL,
       created_at timestamptz NOT NULL,
       updated_at timestamptz NOT NULL,
       deleted boolean NOT NULL DEFAULT false
     );
     CREATE TABLE household_sync_state (
       household_id uuid PRIMARY KEY REFERENCES household(id),
       last_seq bigint NOT NULL DEFAULT 0,
       purge_horizon_seq bigint NOT NULL DEFAULT 0,
       sync_epoch uuid NOT NULL
     );
     ```
     (`household.seq` is the household's own row seq; household rows are syncable like every other entity.)
   - Exposed table objects in `platform/db/Tables.kt` for these two tables (feature modules will define their own tables
     in their packages).
4. **ChangeRecorder** (`platform/sync/ChangeRecorder.kt`):
   - `fun JdbcTransaction.lockHousehold(householdId: UUID)` — `SELECT 1 FROM household_sync_state WHERE household_id = ? FOR UPDATE`
     (used first in every sync mutation transaction, M0-05).
   - `fun JdbcTransaction.nextSeq(householdId: UUID): Long` — executes
     `UPDATE household_sync_state SET last_seq = last_seq + 1 WHERE household_id = ? RETURNING last_seq`;
     throws if no row.
   - `HouseholdChangeBus`: a `MutableSharedFlow<HouseholdChanged(householdId, seq)>` (extraBufferCapacity 64,
     DROP_OLDEST). `ChangeRecorder` registers the emitted event to be published **after commit**
     (collect pending events in a transaction-scoped holder; publish after `dbQuery` returns successfully).
     Design it so the API is: inside `dbQuery { val seq = recordChange(householdId) … }` and events are emitted
     automatically after a successful commit, dropped on rollback.
   - Unit/integration tests: concurrent `nextSeq` calls from 20 coroutines in one household produce 1..20 without gaps
     or duplicates; rollback emits nothing; commit emits once.
5. **HTTP plumbing** (`platform/http/`):
   - ContentNegotiation with `LaresJson`.
   - StatusPages: `ApiException` → status + `ApiErrorBody`; `BadRequestException`/`ContentTransformationException`
     → 400 `VALIDATION_FAILED`; `NotFoundException` → 404 `NOT_FOUND`; others → 500 `INTERNAL`.
   - CallId (generate UUID, header `X-Request-Id`) + CallLogging including the call id; exclude `/health/*`.
   - `ProtocolCheck` route-scoped plugin applied to `/api/v1/*` **except** `GET /api/v1/instance`:
     missing header → 400 `VALIDATION_FAILED`; value outside `[Protocol.MIN_SUPPORTED, Protocol.VERSION]` → 426 `PROTOCOL_UNSUPPORTED`.
   - Routes: `GET /health/live` → 200 `{"status":"ok"}`; `GET /health/ready` → 200 if `SELECT 1` succeeds, else 503;
     `GET /api/v1/instance` → `InstanceInfo(initialized = <any household exists>, serverVersion = <from build>, protocol = …)`.
     Server version: generate from Gradle `project.version` into a resource or `BuildConfig`-like object.
6. **Application wiring** (`Application.kt`): `main()` → `AppConfig.fromEnv()` → `Database` → `embeddedServer(Netty, port)
   { module(deps) }`. Keep a plain constructor-based dependency container (`AppDeps`) — no DI framework on the server.
   `fun Application.module(deps: AppDeps)` installs plugins and routes so tests can call it.
7. **Logging**: `logback.xml` (plain pattern) and `logback-json.xml` (`JsonEncoder`) selected in `main()` as decided;
   level INFO; Exposed/Hikari at WARN.
8. **Dev database**: `deploy/dev/docker-compose.yml` with `postgres:18-alpine`, user/password/db
   `lares`, port `5432:5432`, named volume mounted at **`/var/lib/postgresql`** (PostgreSQL 18 images changed
   the data path; mounting `/var/lib/postgresql/data` would lose data). Document in AGENTS.md "Commands":
   `docker compose -f deploy/dev/docker-compose.yml up -d` and `./gradlew :server:run`.
9. **Test harness** (`server/src/test/kotlin/app/lares/server/testing/`):
   - `TestDatabase`: one PostgreSQL Testcontainer per test JVM (lazy singleton; Testcontainers 2.x class
     `org.testcontainers.postgresql.PostgreSQLContainer`, image `postgres:18-alpine`), Flyway migrate once.
   - `fun testApp(block: suspend ApplicationTestBuilder.() -> Unit)`: builds `AppDeps` against the container,
     truncates all tables except `flyway_schema_history` before each test (`TRUNCATE … RESTART IDENTITY CASCADE`),
     installs `module`.
   - A JSON client helper that adds the protocol header by default.
10. **Tests** (JUnit 5 via kotlin-test):
    - health live/ready; instance returns `initialized=false` on empty DB;
    - protocol header: missing → 400, `99` → 426, `1` → passes (use instance-protected dummy route or a test-only route);
    - `ApiException` mapping produces the documented body; unknown exception → 500 `INTERNAL` without stack trace in body;
    - `ChangeRecorder` tests from step 4.

## Acceptance

- `./gradlew :server:check :contract:check` passes (Docker must be running for Testcontainers).
- `docker compose -f deploy/dev/docker-compose.yml up -d && ./gradlew :server:run` starts; `curl localhost:8080/health/ready`
  → 200; `curl localhost:8080/api/v1/instance` → `{"initialized":false,…}`.
- Logs contain the request id; no request bodies.

## Docs to update

- AGENTS.md "Commands" (dev DB, run server, test server).
- [conventions](../../process/conventions.md): add a short "Server" section: package layout, `dbQuery`, `recordChange`,
  `ApiException`, no DI framework.
- STATUS.md, this plan's status in [plans README](../README.md).

## Stop and report if

- Testcontainers cannot start containers (Docker not running / permissions) — report, do not mock the database.
- The Exposed version in the catalog lacks an API this plan relies on and no straightforward equivalent exists.

## Handoff

Russian summary for the customer: what now exists (server starts, database, health checks), how it was verified
(tests, commands), anything that deviated from the plan, next plan (M0-03).
