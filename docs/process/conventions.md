# Conventions

Preliminary. M0 completes this file with the concrete toolchain (formatter, linter, libraries) and commands.
Toolchain versions: [versions.md](../plans/M0/versions.md) and `gradle/libs.versions.toml` (authoritative).

## General

- Code style: ktlint 1.8.0 via Spotless, `ktlint_official` style, max line length 140, `@Composable` functions may be
  PascalCase (settings in `.editorconfig`). `spotlessCheck` is part of `./gradlew check` and runs in CI.
- Format before checking: `./gradlew spotlessApply`. Never disable ktlint globally; if it cannot parse valid code
  (it embeds the Kotlin 2.2 parser), avoid the newer syntax or report the problem.
- Modules: `:contract` (KMP), `:server` (Ktor), `:client:shared` (KMP + Compose), `:client:android` (app). Packages
  `app.familyhub.contract`, `app.familyhub.server`, `app.familyhub.client`; refer to modules via typesafe accessors
  (`projects.contract`).
- New ids: `Ids.newId()` from `contract` (UUIDv7); do not call the UUID API directly.
- Package / module per feature (ADR-0012); no cross-feature access to internals.
- Terms from the [glossary](../domain/glossary.md); no synonyms in code (`Member`, not `FamilyUser`).
- Comments explain why, not what. Reference requirement IDs where the code implements a specific rule.
- No personal data (positions, amounts, names) in logs.

## Server

- Packages: `app.familyhub.server.<feature>` per feature module (ADR-0012); infrastructure in
  `app.familyhub.server.platform` (`db`, `http`, `sync`, `AppConfig`). A feature defines its own Exposed tables in its package.
- Configuration only from `FAMILYHUB_*` environment variables (`AppConfig.fromEnv`); no HOCON/YAML files.
- No DI framework: dependencies are passed by constructor; `AppDeps` is the hand-wired container,
  `fun Application.module(deps: AppDeps)` installs plugins and routes.
- Database access only through `AppDatabase.dbQuery { … }` (one transaction, Exposed DSL, `Dispatchers.IO`).
  Do not open transactions any other way and do not nest `dbQuery`.
- Every write to a syncable row calls `recordChange(householdId)` inside `dbQuery`: it allocates the household `seq`
  (stamp it on the row) and publishes `HouseholdChanged` on `HouseholdChangeBus` after commit; nothing is published on rollback.
- Errors: `throw ApiException(ErrorCode.X, "English message", fields)`; HTTP status comes from `ErrorCode.httpStatus`
  ([API errors](../architecture/api.md#errors)). Other exceptions become 500 `INTERNAL` with no details in the body.
- Routes under `/api/v1` that need the protocol header go inside `protocolChecked { … }` (everything except `/instance`).
- Authenticated routes go inside `authenticated(deps.authService) { … }` (plugin `RequireSession`); the caller is
  `call.memberPrincipal` (`userId`, `memberId`, `householdId`, `sessionId`).
- Request validation: `validate { add("field", Validators.x(value)) }` with validators from `contract/`; it throws
  `VALIDATION_FAILED` with all field errors.
- Time: inject `kotlin.time.Clock` (from `AppDeps.clock`; tests get a `TestClock`); `clock.nowForDb()` for timestamp columns.
- Repositories are `object`s with plain functions that run inside `dbQuery`; services own transactions and call `recordChange`.
- Slow work (Argon2 hashing) runs outside `dbQuery` on `Dispatchers.Default`.
- Timestamps: `timestamptz` columns, `java.time.Instant` in tables, `kotlin.time.Instant` in contract DTOs.
  UUID columns: `javaUUID(...)` (`java.util.UUID`).
- Tests: the harness lives in `server/src/testFixtures/kotlin/app/familyhub/server/testing/` (test fixtures, also used by
  `integration-tests/`). Identity helpers in `testing/IdentityFixtures.kt` (`setup()`, `login()`, `bearer()`, `assertError()`,
  `createUserDirectly()`); the test setup code is `TEST_SETUP_CODE`; move time with `deps.testClock.advance(…)`.
  Administration helpers in `testing/AdminFixtures.kt` (`memberSession()`, `createMember()`, `loginCodeFor()`, `send()`,
  `createSecondHousehold()` for isolation tests, `createRelative()`). Sync helpers in `testing/SyncFixtures.kt`
  (`newTag()`, `upsert()`, `delete()`, `fields()`, `push()`/`pushOne()`, `pull()`/`pullAll()`, `assertApplied()`/`assertRejected()`).
- New syncable type: a table with the sync columns and `(household_id, seq)` index, a data class in `contract/…/entities/`,
  a `SyncEntityHandler` (`WritableSyncHandler` for client-writable types, `ReadOnlySyncHandler` otherwise) registered in
  `AppDeps.syncHandlers`, rules in `WriteRules`, and — if its rows can be referenced — a decision on tombstone purging
  in `SyncHousekeeping`.
- Tests: `testApp { deps -> … }` runs the full module on an emptied Testcontainers database; `jsonClient()` sends the
  protocol header; `testDeps()` for tests without HTTP. Test functions must return `Unit` (JUnit silently skips others —
  write `fun x(): Unit = runBlocking { … }` when the block ends with an expression).

## Client

- Packages under `app.familyhub.client`: `db`, `api`, `auth`, `sync`, `data`, `di`, `platform` (see
  [overview](../architecture/overview.md#client)). No Android-only APIs in `commonMain`; platform code lives in
  `androidMain`/`jvmMain` and reaches common code through Koin.
- DI: Koin. `sharedModule(platformModule)` builds a complete client core; modules are created by functions, never
  shared `val`s, because Koin keeps singletons inside the module object (integration tests run several cores in one JVM).
  No global singletons outside Koin.
- Database work goes through `LocalDb.transaction { }` / `read { }` (its dispatcher); UI reads `Flow`s from repositories.
- Synced data is changed only through `LocalWriter` (or the `TagsWriter`/`PersonsWriter` use cases), never by writing
  typed tables directly; admin-only types change through REST endpoints, then `SyncEngine.request()`.
- New syncable type on the client: a `.sq` table, an `EntityAdapter` registered in `defaultEntityAdapters`, a
  repository; raise `DATA_SCHEMA_VERSION` so existing installations do a full resync.
- Tests: unit tests in `jvmTest` (in-memory SQLite, Ktor `MockEngine`); multi-device behavior in `integration-tests/`
  (`familyTest { … }` gives a fresh server and three logged-in clients; `TestNetwork` switches a client offline, loses
  push responses or runs code before a pull; `assertConverged(clients)` checks invariant 8).

## Data and values

- IDs: UUIDv7, generated on the client for syncable entities.
- Money: `Long` minor units + ISO 4217 code. Never `Double`/`Float`.
- Time: UTC instants; event times carry an IANA zone id; birthdays and all-day dates are local dates.
- Every query in feature code is scoped by `household_id` (ADR-0010).

## API and contract

- Contract types live in `contract/`; server and client never define their own copies.
- Breaking contract changes bump the protocol version (ADR-0008).

## Dependencies

- License must be compatible with AGPL-3.0 (ADR-0003).
- No proprietary SDKs, analytics, crash reporters, ad SDKs (ADR-0006).
- Versions in the Gradle version catalog only.

## Git

- Until MVP: commits go to `main` after customer approval ([workflow](workflow.md#git-until-mvp)).
  After MVP: release flow defined at M6.
- Conventional Commits; scope = module (`tasks`, `sync`, `deploy`, `docs`); requirement IDs in the subject or body.
- One logical change per commit; docs updated in the same commit or PR.

## UI

- Look and components follow [visual-style.md](../design/visual-style.md) (ADR-0016): use theme tokens, never literal colors.
- All strings in resources, en and ru.
- Every screen works offline with the data already synced, and shows sync state (FND-043).
- Accessibility: content descriptions, scalable text, sufficient contrast.
