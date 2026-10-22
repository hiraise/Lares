---
id: M0-04
status: ready
depends_on: [M0-03]
requirements: [FND-002, FND-003, FND-012, FND-014, FND-021, FND-022, FND-023, FND-024, FND-025, FND-027]
---
# M0-04: Household administration — groups, members, invites, login codes

## Goal

Administrators manage the family: household settings, groups and their permissions, members (create without password,
reset password, change groups, remove), invites, and one-time QR login codes. Household isolation is proven by tests.

## Read first

- [Auth design](../../architecture/auth.md) — invites, login codes, member management
- [API](../../architecture/api.md) — endpoint list "requires `household.manage`" and login codes
- [ADR-0014](../../adr/0014-group-based-permissions.md), [ADR-0010](../../adr/0010-household-tenancy.md)
- Code from M0-03: permissions in `contract/`, services, `RequireSession`, test harness

## Scope

Endpoints:
`PATCH /api/v1/household`; `POST /api/v1/members`; `DELETE /api/v1/members/{id}`; `POST /api/v1/members/{id}/password`;
`PUT /api/v1/members/{id}/groups`; `POST /api/v1/groups`; `PATCH /api/v1/groups/{id}`; `DELETE /api/v1/groups/{id}`;
`POST /api/v1/invites`; `GET /api/v1/invites`; `DELETE /api/v1/invites/{id}`; `POST /api/v1/invites/preview`;
`POST /api/v1/invites/accept`; `POST /api/v1/login-codes`; `POST /api/v1/auth/login-code`.

Out of scope: sync endpoints (M0-05) — but every write here must already allocate seqs via `recordChange`.

## Decisions (do not revisit)

- Permission check helper: `call.requirePermission(Permission.HOUSEHOLD_MANAGE)` using `PermissionService`;
  failure → 403 `FORBIDDEN`.
- `access_version` of a member is incremented whenever their group memberships change or the permissions of any group
  they belong to change (and when the member is removed). Implement in one place: `AccessVersionService.bump(memberIds)`.
- "Administrators" group: cannot be deleted or renamed, its permissions cannot be changed (`BUILTIN_GROUP`);
  its membership can change but must keep at least one non-deleted member (`LAST_ADMIN`).
- Removing a member = same effect as account deletion in M0-03 (reuse the code), performed by an admin.
- A member created directly (`POST /api/v1/members`) may have no login and no password. Request:
  `CreateMemberRequest(displayName, login: String?, password: String?, groupIds: Set<String>, personId: String? = null)`;
  if `password` is given, `login` is required. **Always** creates a `user_account` (its `login` may be null — the column
  becomes nullable in a new migration, unique when not null), a member and memberships. Without `personId` a new person is
  created; with `personId` the existing relative is linked (FND-032): the person must belong to the household (else `NOT_FOUND`), not be
  deleted and not be referenced by a non-deleted member (else `VALIDATION_FAILED` with field `personId`).
- **Every referenced id must belong to the caller's household** (`groupIds`, `memberIds`, `personId`, invite ids, the member of a
  login code). `group_membership` has no `household_id`, so services must check explicitly. Violations → 404 `NOT_FOUND`.
- Invite, setup and login codes are normalized with `Codes.normalize` (contract) before hashing and comparison.
- Invite and login code formats, lifetimes and hashing exactly as in auth.md. Payload URLs use `FAMILYHUB_PUBLIC_URL`.
- Login code for another member requires `household.manage`; for oneself, any member.

## Steps

1. **Migration `V3__admin.sql`**:
   - `ALTER TABLE user_account ALTER COLUMN login DROP NOT NULL;` keep uniqueness for non-null values.
   - `invite(id uuid PK, household_id FK, code_hash bytea UNIQUE, group_ids text NOT NULL /* JSON array */,
     created_by_member_id FK, created_at, expires_at, used_at NULL, used_by_member_id NULL, revoked_at NULL)`
   - `login_code(id uuid PK, household_id FK, member_id FK, code_hash bytea UNIQUE, created_by_member_id FK,
     created_at, expires_at, used_at NULL)`
2. **Contract DTOs** (`contract/…/api/admin/`): `UpdateHouseholdRequest(name?, timeZone?, currency?, defaultLanguage?)`,
   `CreateMemberRequest`, `MemberCreated(memberId, personId)`, `ResetPasswordRequest(newPassword)`,
   `SetGroupsRequest(groupIds)`, `CreateGroupRequest(name, permissions, memberIds)`,
   `UpdateGroupRequest(name?, permissions?, memberIds?)`, `GroupCreated(groupId)`,
   `CreateInviteRequest(groupIds, expiresInHours = 72, personId: String? = null)` (accepting such an invite links that relative), `InviteCreated(id, code, payload, expiresAt)`,
   `InviteInfo(id, groupIds, createdBy, createdAt, expiresAt)`, `InvitePreviewRequest(code)`,
   `InvitePreview(householdName, inviterName)`, `AcceptInviteRequest(code, displayName, login, password, device)`,
   `CreateLoginCodeRequest(memberId: String? = null)`, `LoginCodeCreated(code, payload, expiresAt)`,
   `LoginWithCodeRequest(code, device)`. Payload helpers: `object DeepLinks { fun invite(serverUrl, code); fun login(serverUrl, code); fun parse(uri): DeepLink? }`
   with unit tests (round trip, URL-encoding, unknown scheme → null).
3. **Services**: `HouseholdAdminService` (update settings with validation; bump household seq),
   `MemberAdminService` (create, remove, reset password + revoke sessions, set groups), `GroupAdminService`
   (create/update/delete with builtin and last-admin rules; deleting a group removes memberships and bumps
   `access_version` of its members), `InviteService` (create, list active, revoke, preview, accept with single-use
   and expiry checks inside one transaction using `SELECT … FOR UPDATE`), `LoginCodeService` (create, exchange).
   Every change to `household`, `member`, `group_entity` (including its permissions/memberships) and `person`
   allocates a seq with `recordChange` and updates the row's `seq`.
4. **Routes** with `RequireSession` + permission checks; public routes for invite preview/accept and login-code exchange
   with the failed-attempt limiter (per IP, 10 per 15 min) on wrong codes.
5. **Isolation fixture**: test helper that creates a second household with its own admin directly through
   `SetupService`-like internal code (bypassing the one-household setup restriction) — test-only.
6. **Tests**:
   - Permission matrix: for each admin endpoint, admin → success, member of "Adults" → 403, member of "Kids" → 403.
   - Groups: create with permissions; `withImplied` is not stored but evaluation includes implied sections;
     rename/delete/change-permissions of Administrators → `BUILTIN_GROUP`; removing the last admin from
     Administrators → `LAST_ADMIN`; deleting a group bumps members' `access_version`; changing a group's permissions bumps
     its members' `access_version` and the group's seq.
   - Members: create without password → login code for them → exchange works → `/me` of the new session is that member;
     reset password revokes their sessions; set groups changes `/me` permissions; remove member → their tokens
     return `SESSION_REVOKED`; cannot remove the last admin.
   - Invites: create → preview (household name, inviter) → accept → new member in the invite groups; second accept →
     `INVITE_INVALID`; expired (advance clock) → `INVITE_INVALID`; revoked → `INVITE_INVALID`; non-admin cannot create.
   - Login codes: self-code by an Adult works; Adult creating a code for another member → 403; code expires after 5 min;
     single use.
   - Household settings: invalid time zone/currency → `VALIDATION_FAILED` with field codes.
   - **Cross-household references**: admin of A creating a group with a member id of B, setting groups with a group id of B,
     creating a member/invite with a person id of B, or a login code for a member of B → 404 `NOT_FOUND`, nothing written.
   - **Linking a relative** (FND-032): create member with `personId` of an existing relative → the member's profile is that
     person; the same person cannot be linked twice; invite with `personId` links on accept.
   - **Isolation**: admin of household A cannot read/modify members, groups, invites of household B by id (→ 404
     `NOT_FOUND`, never 403, to avoid revealing existence); login code of household B cannot be created by admin of A.

## Acceptance

- `./gradlew check` passes.
- Manual curl walkthrough: setup → create invite → accept invite → new member logs in → admin moves them to "Kids".

## Docs to update

- Foundation spec: FND-002, FND-003, FND-012, FND-014, FND-021…025, FND-027 → `done` (server side; UI comes in M0-07/08 —
  write "server done" in the requirement line if UI is pending, and keep state `doing`).
- [api.md](../../architecture/api.md) if any DTO differs; [auth.md](../../architecture/auth.md) note that users may have no login.
- STATUS.md; plan status.

## Stop and report if

- A rule in this plan conflicts with ADR-0014 or auth.md.

## Handoff

Russian summary: administrators can now manage groups and rights, invite people, create accounts without passwords
and log them in by QR code; how it was tested (including isolation between families); next plan M0-05.
