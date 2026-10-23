---
id: M0-03
status: done
depends_on: [M0-02]
requirements: [FND-001, FND-010, FND-011, FND-013, FND-020, FND-021, FND-023, FND-026]
---
# M0-03: Identity — setup, login, sessions, permissions catalogue

## Goal

A fresh server can be claimed with the setup code; the admin gets a household with groups "Administrators",
"Adults", "Kids"; users log in, refresh and revoke sessions, change their password and delete their account.
The permission catalogue and evaluator live in `contract/` and are used to compute effective permissions.

## Read first

- [Auth design](../../architecture/auth.md) — **binding** for this plan
- [API](../../architecture/api.md), [ADR-0014](../../adr/0014-group-based-permissions.md),
  [foundation spec § permission catalogue](../../product/features/foundation.md#permission-catalogue)
- [Sync protocol § server data model](../../architecture/sync.md#server-data-model) — new tables are syncable
- Code from M0-02: `dbQuery`, `recordChange`, `ApiException`, test harness

## Scope

- Contract: permissions (enum, catalogue, evaluator), validators, auth DTOs, entity data classes for `member`, `group`, `person`, `household`.
- Migration `V2__identity.sql`; Exposed tables; repositories; services; routes listed below.
- Argon2id hashing, opaque tokens, session authentication plugin, failed-attempt limiter, setup code.

Endpoints: `GET /api/v1/instance` (now real), `POST /api/v1/setup`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`,
`POST /api/v1/auth/logout`, `GET /api/v1/me`, `POST /api/v1/me/password`, `GET /api/v1/me/sessions`,
`DELETE /api/v1/me/sessions/{id}`, `DELETE /api/v1/me`.

Out of scope: invites, login codes, member/group administration (M0-04); sync endpoints (M0-05).

## Decisions (do not revisit)

- Opaque tokens, SHA-256 hashes in DB, access 1 h, refresh 90 days with rotation and reuse detection (auth.md).
- Argon2id via Bouncy Castle with parameters from auth.md, PHC string format.
- No DI framework; services are constructed in `AppDeps`.
- Authentication is a **custom route-scoped Ktor plugin** (`RequireSession`) that reads `Authorization: Bearer`,
  validates the session and stores a `MemberPrincipal(userId, memberId, householdId, sessionId)` in call attributes.
  It throws `ApiException` with `UNAUTHENTICATED` / `SESSION_EXPIRED` / `SESSION_REVOKED`.
- Inject `kotlin.time.Clock` everywhere time matters (tests use a controllable clock).
- Client IP: install Ktor `XForwardedHeaders` (the server always runs behind Caddy); use `call.request.origin.remoteHost`.
- Group names for templates are stored in the household default language at setup:
  `en`: "Administrators", "Adults", "Kids"; `ru`: "Администраторы", "Взрослые", "Дети".
  Clients display the built-in group by its `builtIn` flag, not by the stored name.
- Person birth date is stored as separate nullable `birth_year`, `birth_month`, `birth_day` (year may be unknown).
  `contacts` is a JSON array stored in a `text` column.
- Ending a session never deletes the row: logout, revocation, account deletion and member removal set `revoked_at`.
- Refresh grace window per auth.md: previous refresh token within 60 s of rotation and before the new access token was used →
  rotate again; otherwise revoke. Mark `access_used_since_rotation = true` on the first authenticated request after a rotation.
- Deleted users: `user_account.deleted_at` set, `login` replaced by `deleted-<user id>` to free the login,
  `password_hash` nulled.

## Steps

1. **Contract — permissions** (`contract/…/permissions/`):
   - `enum class Section { TASKS, SHOPPING, CALENDAR, MAP, BUDGET }`, `enum class PermissionKind { SECTION, ACTION, MANAGEMENT }`.
   - `@Serializable enum class Permission(val kind: PermissionKind, val section: Section?)` with `@SerialName` equal
     to the catalogue key, entries exactly as in the foundation spec catalogue (17 entries).
   - `enum class BuiltInGroup { ADMINISTRATORS }`.
   - `object PermissionCatalogue { val adultsTemplate: Set<Permission>; val kidsTemplate: Set<Permission>;
     fun sectionPermission(section: Section): Permission; fun withImplied(perms: Set<Permission>): Set<Permission> }`
   - Every `Set<Permission>` in DTOs and entity data is serialized with `LenientEnumSetSerializer` (M0-02), so permissions added
     by a newer server are ignored by older clients.
     — `withImplied` adds the section `*.view` for every action permission of that section.
   - `data class EffectivePermissions(val isAdmin: Boolean, val permissions: Set<Permission>) { fun has(p: Permission): Boolean }`
     — admin has everything.
   - `object PermissionEvaluator { fun evaluate(memberId: String, groups: Collection<GroupView>): EffectivePermissions }`
     where `GroupView(id, builtIn: BuiltInGroup?, permissions: Set<Permission>, memberIds: Set<String>, deleted: Boolean)`.
     Union of non-deleted groups containing the member, then `withImplied`; `isAdmin` if a non-deleted ADMINISTRATORS group contains the member.
   - Tests: templates match the spec table; implied sections; admin; deleted groups ignored; member in no group → empty.
2. **Contract — entity data classes** (`contract/…/entities/`), all `@Serializable`, used later by sync:
   - `HouseholdData(name, timeZone, currency, defaultLanguage)`
   - `PersonData(name, birthYear: Int? , birthMonth: Int?, birthDay: Int?, relation: String?, notes: String?, contacts: List<ContactData> = emptyList())`,
     `ContactData(kind: ContactKind, value: String)`, `enum class ContactKind { PHONE, EMAIL, ADDRESS, OTHER }` (lenient, fallback `OTHER`)
   - `MemberData(personId, userId: String?, login: String?, hasPassword: Boolean)`
   - `GroupData(name, builtIn: BuiltInGroup?, permissions: Set<Permission>, memberIds: Set<String>)`
3. **Contract — validation** (`contract/…/validation/Validators.kt`): pure functions returning `FieldErrorCode?`:
   `login` (3–32, `[a-z0-9._-]` after lowercasing), `password` (8–128), `personName` (1–100 after trim),
   `householdName` (1–60), `groupName` (1–40), `timeZone` (valid IANA id via kotlinx-datetime `TimeZone.of`),
   `currency` (`^[A-Z]{3}$`), `language` (`en` | `ru`), birth date (month 1–12, day valid for month; Feb 29 allowed
   when year is null or leap; year 1900..current). Plus a small `ValidationErrors` builder that collects
   `Map<String, FieldErrorCode>` and throws/returns. Unit tests for every validator, including Feb 29 cases.
4. **Contract — auth DTOs** (`contract/…/api/auth/`): `DeviceInfo(name, platform)`, `SetupRequest`, `LoginRequest`,
   `RefreshRequest`, `AuthResult(accessToken, accessExpiresAt: Instant, refreshToken, refreshExpiresAt: Instant, sessionId, me: Me)`,
   `Me(userId, memberId, householdId, householdName, personId, displayName, login: String?, hasPassword, isAdmin,
   permissions: Set<Permission>)`, `SessionInfo(id, deviceName, platform, createdAt, lastSeenAt, current)`,
   `ChangePasswordRequest(currentPassword: String?, newPassword)`, `DeleteAccountRequest(password: String?)`.
   Field lists of request bodies follow auth.md.
5. **Migration `V2__identity.sql`** — tables (all syncable ones get `seq`, `created_at`, `updated_at`, `deleted`
   and an index `(household_id, seq)`):
   - `user_account(id uuid PK, login text NOT NULL UNIQUE, password_hash text NULL, created_at, deleted_at NULL)`
   - `person(id, household_id FK, name, birth_year int NULL, birth_month int NULL, birth_day int NULL, relation text NULL,
     notes text NULL, contacts text NOT NULL DEFAULT '[]', seq, created_at, updated_at, deleted)`
   - `member(id, household_id FK, user_id FK NULL, person_id FK NOT NULL, access_version bigint NOT NULL DEFAULT 1, seq, …)`
     + unique partial index `(user_id, household_id) WHERE deleted = false`
   - `group_entity(id, household_id FK, name, built_in text NULL, seq, …)`
   - `group_permission(group_id FK ON DELETE CASCADE, permission text, PK(group_id, permission))`
   - `group_membership(group_id FK ON DELETE CASCADE, member_id FK, PK(group_id, member_id))`
   - `session(id uuid PK, user_id FK, member_id FK, household_id FK, device_name text, platform text, created_at,
     last_seen_at, access_hash bytea UNIQUE, access_expires_at, refresh_hash bytea UNIQUE, refresh_expires_at,
     previous_refresh_hash bytea NULL, rotated_at timestamptz NULL, access_used_since_rotation boolean NOT NULL DEFAULT false,
     revoked_at NULL)` + index on `previous_refresh_hash`. Session rows are never deleted (revocation sets `revoked_at`).
   Changing `group_permission`/`group_membership` must bump the owning group's `seq` (done in services via `recordChange`).
6. **Crypto utils** (`identity/crypto/`): `PasswordHasher` (Argon2id, `hash(password): String`, `verify(password, phc): Boolean`,
   constant-time compare via `MessageDigest.isEqual`). Bouncy Castle does **not** produce PHC strings: encode/parse
   `$argon2id$v=19$m=<kb>,t=<iter>,p=<par>$<salt b64 no padding>$<hash b64 no padding>` yourself.
   Use `Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)` explicitly (the no-arg builder defaults to Argon2i),
   `.withVersion(ARGON2_VERSION_13).withIterations(2).withMemoryAsKB(19456).withParallelism(1).withSalt(salt)`,
   then `Argon2BytesGenerator().init(params); generateBytes(password.toCharArray(), out32)`. `TokenGenerator` (32 random bytes → base64url, `sha256(token): ByteArray`),
   Unit tests (hash/verify, wrong password, PHC parsing).
   Codes: `contract/…/Codes.kt` holds the Crockford alphabet and `normalize(input)` per
   [auth.md § codes](../../architecture/auth.md#codes) (used by client and server); generation lives on the server in
   `identity/crypto/CodeGenerator` using `java.security.SecureRandom`. Unit tests: normalization (`o`→`0`, `l`→`1`, dashes,
   spaces, lowercase), generated codes use only the alphabet.
7. **Limiter** (`identity/FailedAttemptLimiter.kt`): in-memory, keys with sliding window (10 per 15 min for login,
   5 per 15 min for setup), `ensureAllowed(key)`, `recordFailure(key)`, `reset(key)`. Unit tests with the test clock.
8. **Setup code** (`identity/SetupCodeProvider.kt`): on startup, if no household exists, use `LARES_SETUP_CODE` or
   generate one; log at WARN: `Setup code: XXXXX-XXXXX (enter it in the app to create your family)`.
   After successful setup, forget it.
9. **Repositories** (`identity/…`, `household/…` packages per ADR-0012): Exposed table objects and functions for
   user, person, member, group (+permissions, memberships), session, household. Every function takes `householdId`
   where the table has one.
10. **Services**:
    - `SetupService.setup(req, ip)`: limiter → code check → validation → one transaction creating household,
      `household_sync_state`, three groups (Administrators with built_in, Adults and Kids with template permissions),
      user, person, member, membership in Administrators, session; every syncable row gets a seq via `recordChange`.
    - `AuthService.login`, `refresh`, `logout`, `authenticate(accessToken)` per auth.md.
    - `AccountService.me`, `changePassword`, `sessions`, `revokeSession`, `deleteAccount` (with `LAST_ADMIN` check;
      member tombstoned with a new seq; memberships removed with group seq bumps and `access_version` bumps for
      affected members — for M0 bump only the removed member's).
    - `PermissionService.effective(householdId, memberId)` loading groups and calling `PermissionEvaluator`.
11. **Routes** for all endpoints in Scope; `RequireSession` on authenticated ones; request validation via contract validators
    → `VALIDATION_FAILED` with `fields`.
12. **Tests** (integration, via `testApp`):
    - setup: wrong code → `SETUP_CODE_INVALID`; success → tokens, `/me` shows `isAdmin=true`, three groups exist with
      template permissions; second setup → `SETUP_ALREADY_DONE`; 6th wrong attempt → `RATE_LIMITED`; instance becomes `initialized=true`.
    - login: ok; wrong password and unknown login give the same `INVALID_CREDENTIALS`; 11th failure → `RATE_LIMITED`;
      login is case-insensitive.
    - refresh grace: old refresh token presented again within 60 s and before the new access token is used → a fresh pair is
      returned and works; old refresh token presented after the new access token was used → `SESSION_REVOKED`.
    - tokens: access works; expired access (advance clock 61 min) → `SESSION_EXPIRED`; refresh rotates; old refresh reused →
      `SESSION_REVOKED` and the session is revoked; logout revokes.
    - sessions: list shows `current=true` for the caller; revoking another session makes its token fail with `SESSION_REVOKED`.
    - change password: wrong current → `INVALID_CREDENTIALS`; success → new password works.
    - delete account: only admin → `LAST_ADMIN`; (create a second user directly in DB as admin) then delete works,
      login freed, tokens fail, member row tombstoned with a higher seq.
    - every syncable write increments `household_sync_state.last_seq` and stamps rows with that seq.

## Acceptance

- `./gradlew check` passes.
- Manual: run the server, read the setup code in the log, call `POST /api/v1/setup` with curl, then `GET /api/v1/me`.

## Docs to update

- [api.md](../../architecture/api.md) only if a DTO detail had to differ (explain in handoff).
- [glossary](../../domain/glossary.md) if new terms appeared.
- Foundation spec: set FND-001, FND-010, FND-011, FND-013, FND-020, FND-021, FND-023, FND-026 to `done` where fully covered
  (FND-021/023 are completed by M0-04 for editing groups — leave them `doing`).
- STATUS.md; plan status.

## Stop and report if

- Bouncy Castle Argon2 API differs from the expected one and no equivalent exists in the catalog version.
- A requirement in auth.md looks insecure or contradictory — report instead of improvising.

## Handoff

Russian summary: the server can now be set up with a code, people can log in and manage their sessions; how it was
tested; deviations; next plan M0-04.
