---
status: accepted
updated: 2026-10-22
---
# Authentication and onboarding

Design for FND-001, FND-010…015, FND-024. Endpoints are listed in [api.md](api.md).

## Instance setup (FND-001)

Problem: a freshly started server on a public VPS could be claimed by anyone who finds it first.

1. On start, if no household exists, the server needs a **setup code**: taken from env `FAMILYHUB_SETUP_CODE`,
   otherwise generated (10 characters, Crockford base32) and printed to the log at WARN level on every start
   until setup is done. It is never stored in plain text after setup.
2. The admin installs the app, enters the server URL; the app calls `GET /api/v1/instance` and sees `initialized: false`.
3. The app shows the setup wizard: setup code, household name, time zone (device default), currency (locale default),
   default language, admin name, login, password.
4. `POST /api/v1/setup` creates in one transaction: household, `household_sync_state`, groups "Administrators"
   (built-in), "Adults" and "Kids" (templates from the permission catalogue), the admin user, person, member
   (in Administrators), and a session. Returns tokens.
5. After setup the endpoint answers `SETUP_ALREADY_DONE`. Creating additional households on one instance is out of
   scope for M0 (the data model supports it).

Rate limit: 5 attempts per IP per 15 minutes on `/setup`.

## Credentials

- **Login**: unique per instance, 3–32 chars `[a-z0-9._-]`, case-insensitive (stored lowercase).
- **Password**: optional for members created by an admin (they log in by QR code); required for setup and invite
  acceptance. Minimum 8 characters, maximum 128. No composition rules.
- Hashing: **Argon2id** (Bouncy Castle), memory 19 MiB, iterations 2, parallelism 1, 16-byte salt, 32-byte hash,
  stored as a PHC string `$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`. Parameters are read from the stored string
  so they can be raised later.
- Login rate limit: 10 failed attempts per login and per IP per 15 minutes → `RATE_LIMITED` (HTTP 429).
  Failed logins answer `INVALID_CREDENTIALS` without revealing whether the login exists.

## Sessions and tokens

- A session = one logged-in app installation (device). Table `session(id, user_id, member_id, household_id,
  device_name, platform, created_at, last_seen_at, access_hash, access_expires_at, refresh_hash, refresh_expires_at,
  previous_refresh_hash, rotated_at, access_used_since_rotation, revoked_at)`. Session rows are never deleted: ending a
  session (logout, revocation, account deletion, member removal) always sets `revoked_at`.
- Tokens are **opaque random strings** (32 bytes, base64url). Only SHA-256 hashes are stored.
  - Access token: 1 hour. Sent as `Authorization: Bearer <token>`.
  - Refresh token: 90 days, **rotated** on every refresh (`previous_refresh_hash` keeps the replaced one, `rotated_at` the time,
    `access_used_since_rotation = false`). If the previous refresh token is presented again:
    - within 60 s of the rotation **and** the new access token has not been used yet → the response was probably lost on a
      flaky network: rotate again and return a fresh pair;
    - otherwise → token theft suspected: revoke the session.
- Every authenticated request looks the session up by access hash (indexed) and checks `revoked_at` and expiry,
  so revocation and member removal take effect immediately. `last_seen_at` is updated at most once per minute.
- Errors: `UNAUTHENTICATED` (no/unknown token), `SESSION_EXPIRED` (access token expired → client refreshes),
  `SESSION_REVOKED` (client wipes local data and returns to login). A client that gets `UNAUTHENTICATED` for a token it holds
  (e.g. after a server restore) tries one refresh; if that also fails with 401, it treats the session as logged out.

## Invites (FND-024)

- A member with `household.manage` creates an invite: chosen groups, expiry (default 72 h, max 30 days).
- Invite code: 12 characters Crockford base32, single use, stored hashed (SHA-256); invites are listed and revoked by id.
- Shared as a link/QR payload: `familyhub://invite?server=<urlencoded public URL>&code=<code>`.
- `POST /api/v1/invites/preview` (public, code in body) returns household name and inviter name, or `INVITE_INVALID`.
- Accepting creates user + person + member in the invite's groups + session, returns tokens. The invite row is locked
  (`SELECT … FOR UPDATE`) during acceptance, so it is used at most once. Groups deleted since the invite was created are
  skipped; if the invite's relative was deleted or linked meanwhile, the invite is `INVITE_INVALID`.
- Wrong codes on preview and accept count per IP (10 per 15 minutes → `RATE_LIMITED`), like failed logins.

## QR login on a new device (FND-014)

- A logged-in member creates a **login code** for themselves; a member with `household.manage` can create one for
  any member (e.g. a child without a password).
- Code: 8 characters Crockford base32, valid 5 minutes, single use, stored hashed.
- Payload: `familyhub://login?server=<urlencoded public URL>&code=<code>`.
- `POST /api/v1/auth/login-code` exchanges it for tokens and a new session. Rate limited like login.

## Account and member management

- Change own password: requires current password (if one is set). Other sessions stay valid.
- Admin password reset (FND-012): sets a new password for a member and revokes all their sessions.
- Delete own account (FND-013): requires password if set; refused with `LAST_ADMIN` if the user is the only
  member of "Administrators". Marks the user deleted and revokes all their sessions; the member becomes a tombstone; the person stays as a
  relative-type person (history keeps a name).
- Remove member (FND-025): same effect, performed by an admin; cannot remove the last administrator.
- Create member (admin, FND-022): always creates a `user_account` — `login` and password optional (no login → QR login only).
  `user_account.login` is nullable (unique when set, migration `V3__admin`); a password requires a login. An administrator
  can set the password later with a reset.
  Optional `personId` links an existing relative to the new account (FND-032); invites accept the same optional `personId`.
  The person must belong to the household, not be deleted and not be linked to another member. The linked person takes the
  `displayName` of the request as its name (the client pre-fills it with the relative's name).

## Codes

Setup, invite and login codes use Crockford base32 (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`), displayed with a dash in the middle.
Input is normalized before comparison: uppercase, remove spaces and `-`, map `O→0`, `I→1`, `L→1`.
`Codes.normalize(input)` and the alphabet live in `contract/` (shared); codes are generated only on the server with `SecureRandom`.

## Public URL

`FAMILYHUB_PUBLIC_URL` (e.g. `https://family.example.com`) is required in production; it is embedded in invite and
login QR payloads. The app stores the server URL it connected with.
