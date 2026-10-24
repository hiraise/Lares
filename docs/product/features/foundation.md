---
status: approved
milestone: M0
updated: 2026-10-24
---
# Foundation (FND)

## Summary

Instance setup, identity, households, members, groups and permissions, persons, sync, notifications,
localization and deployment — everything feature modules depend on.

## Scenarios

1. Admin rents a VPS, runs `docker compose up`, installs the app, enters the server URL and the setup code from the server log,
   and creates the household and the first admin account ([auth design](../../architecture/auth.md)).
2. Admin invites the spouse by link into the "Adults" group; spouse installs the APK, opens the link, sets a password, and is in.
3. Admin creates an account for the daughter in the "Kids" group; her phone logs in by scanning a QR code on the admin's phone.
4. Admin creates a group "Grandparents" that may only see the calendar and shopping lists.
5. Admin adds grandma as a person (no account) with her birthday.
6. A member edits data on the subway without network; changes sync when the phone is back online.

## Requirements

### Instance and household

- **FND-001** `MVP` `done` — On first start the server offers a one-time setup, protected by a setup code from the server log,
  to create the first admin and household. *Server done (M0-03); setup form in the app done (M0-07).*
- **FND-002** `MVP` `doing` — One instance can host several households; data of different households is fully isolated.
  *Isolation of admin endpoints (M0-04) and sync (M0-05) enforced and tested; creating more households: after M0.*
- **FND-003** `MVP` `done` — Household settings: name, time zone, currency, default language.
  *Server done (M0-04); Family › Family settings (M0-08).*

### Accounts and sessions

- **FND-010** `MVP` `done` — A user logs in with login + password; email is not used. Members created by an admin may have
  no password and log in by QR code only. Passwords are hashed with Argon2id.
  *Server login, password change, Argon2id done (M0-03); members without login/password (M0-04); app login by password and QR code (M0-07).*
- **FND-011** `MVP` `done` — Sessions survive app restarts; a user can see and revoke their sessions (devices).
  *Server sessions, refresh rotation, list and revoke done (M0-03); restore on start and "My devices" screen (M0-07).*
- **FND-012** `MVP` `done` — A member with `household.manage` can reset another member's password.
  *Server done, revokes the member's sessions (M0-04); member detail › Reset password (M0-08).*
- **FND-013** `MVP` `done` — A user can delete their account from inside the app (also a Google Play requirement).
  *Server done (M0-03); Settings › Delete account with password confirmation (M0-07).*
- **FND-014** `MVP` `done` — Any member can log in on a new device by scanning a QR code shown on a logged-in device
  (their own, or one of a member with `household.manage`). *Server login codes done (M0-04); own QR code, scanner and `lares://login` links (M0-07); member detail › Log in by QR code for another member (M0-08).*
- **FND-015** `later` `todo` — Login via external OpenID Connect provider (Authentik, Keycloak).

### Members, groups and permissions ([ADR-0014](../../adr/0014-group-based-permissions.md))

- **FND-020** `MVP` `done` — Permissions are granted through groups; a member can be in several groups;
  effective permissions are the union of the groups' permissions.
- **FND-021** `MVP` `done` — Built-in group "Administrators": cannot be deleted, always has at least one member, has all permissions
  and sees all data regardless of visibility rules. *Created at setup, all permissions, last-admin check (M0-03);
  no rename/delete/permission change, at least one member (server, M0-04); group editor: locked permissions, membership
  editable, last member kept (M0-08).*
- **FND-022** `MVP` `done` — Members with `household.manage` create, rename and delete groups, edit group permissions and membership.
  *Server done, incl. creating members directly (M0-04); Groups tab, group editor, member groups, "Create an account
  without password" (M0-08).*
- **FND-023** `MVP` `done` — Household setup creates editable templates "Adults" and "Kids" with the defaults below.
  *Created at setup (M0-03); editable through the group endpoints (M0-04) and the group editor (M0-08).*
- **FND-024** `MVP` `done` — Invite by link or QR code with chosen groups; invites expire.
  *Server done: create, list, revoke, preview, accept, link payload (M0-04); accepting (M0-07); creating with groups and
  expiry, QR, share link, active invites with revoke (M0-08).*
- **FND-025** `MVP` `done` — Members with `household.manage` can remove members.
  *Server done, same effect as account deletion (M0-04); member detail › Remove from family (M0-08).*
- **FND-026** `MVP` `doing` — Ownership rules: creators edit their own items, assignees complete their tasks,
  everyone manages their own profile, sessions and notifications — independent of groups.
  *Own sessions, password and account need no permission (M0-03); items, profile, notifications follow in their modules.*
- **FND-027** `MVP` `doing` — Permissions are enforced offline on the client and authoritatively on the server.
  *Server checks on admin endpoints with a permission matrix test (M0-04) and on sync mutations via shared `WriteRules` (M0-05).
  Client core (M0-06): local writes checked with the same `WriteRules`, permissions evaluated offline from synced groups
  (`PermissionsRepository`); Family screens hide or disable every edit control per permission (M0-08); modules follow.*
- **FND-028** `MVP` `doing` — Sections a member has no access to are hidden from navigation, search and tag pages.
  *Bottom bar and More tiles filtered by `*.view` (M0-07); search and tag pages come with their milestones.*

### Persons

- **FND-030** `MVP` `done` — A person is anyone relevant to the family: members and relatives without accounts.
  *Relatives tab lists both; members' profiles carry a "member" badge (M0-08).*
- **FND-031** `MVP` `done` — Person fields: name, optional birth date (year optional), relation label, notes, contacts.
  *Server: persons through sync with shared validators and `WriteRules` (M0-05); client core `PersonsWriter`/`PersonsRepository` (M0-06); person form with birthday pickers, relation
  suggestions, contacts, notes (M0-08).*
- **FND-033** `later` `todo` — Person photo, in milestone M5b ([files](files.md)). Until then avatars show initials on
  the personal color.
- **FND-034** `MVP` `done` — Every person has a personal color from the shared palette, assigned automatically
  (least-used in the household) and changeable; it marks the person's avatar, events, map pin and assignments
  ([visual style](../../design/visual-style.md#people)).
  *Server: `PersonData.color`, least-used color on setup, member creation and invite acceptance (M0-05); client core picks the least-used color for new relatives (`PersonsWriter`, M0-06); avatars in personal colors and
  the "Color" row in the person form and own profile (M0-08). Events, pins and assignments use it in their modules.*
- **FND-032** `MVP` `done` — Every member has a person profile; a relative can later be linked to a new account.
  *Server: linking on member creation and invite acceptance (M0-04); persons through sync (M0-05); "This is an existing
  relative" in both add-member flows (M0-08).*

### Offline and sync

- **FND-040** `MVP` `doing` — All module data except live location is readable and editable offline.
  *Client core (M0-06): local SQLite, outbox, repositories as `Flow`s — persons and tags work offline; each module
  reuses it as it is built.*
- **FND-041** `MVP` `doing` — Offline changes sync automatically when connectivity returns; no data is lost on conflict
  (design in [ADR-0008](../../adr/0008-offline-first-sync.md)).
  *Server: pull, push with idempotency and field-level merge, housekeeping (M0-05). Client sync engine with retry and
  backoff, proven by multi-device tests (M0-06). Remaining: the Android "connectivity regained" trigger (M0-07).*
- **FND-042** `MVP` `doing` — Changes by other members appear without manual refresh while the app is open.
  *Server: WebSocket `/api/v1/sync/events` (M0-05). Client: realtime connection while in the foreground, tested within
  2 s (M0-06); foreground toggling from the app lifecycle: M0-07.*
- **FND-043** `MVP` `done` — The UI shows sync state (synced, pending changes, offline, error).
  *`SyncEngine.status` and unseen rejections (M0-06); header indicator, sync sheet and rejection snackbar (M0-07). The in-store offline banner on the shopping list page (M2-04).*

### Notifications

- **FND-050** `MVP` `done` — Time-based reminders are scheduled on the device and fire without network.
  *`ReminderScheduler` with a local `reminder` table and `AlarmManager` (exact when the user allows exact alarms),
  re-registered after reboot and app update; Settings › Notifications › Diagnostics has a test reminder (M0-09).*
- **FND-051** `MVP` `doing` — Event-based notifications (e.g. "task assigned to you", "task overdue") reach the phone
  without Google services ([ADR-0006](../../adr/0006-no-proprietary-dependencies.md)).
  *Infrastructure done (M0-09): UnifiedPush registration, encrypted Web Push from the server (tested against a fake push
  service), sync tickles, test notification. Open: end-to-end check on a real phone with ntfy (needs the deployed
  ntfy from M0-10). Task notifications (assigned, completed, overdue) are sent through it from M1-03; a tap opens the
  task (M1-07).*
- **FND-052** `MVP` `done` — Each user can mute notification types.
  *Stored per member on the server (muted types are not sent), mirrored on the phone; switches in Settings ›
  Notifications. M0 shows only test notifications (M0-09); task assigned, completed, overdue and task reminders have
  switches (M1-07).*

### Client basics

- **FND-060** `MVP` `done` — On first launch the app asks for the server URL (or reads it from the invite link/QR).
  *Server screen, QR scanner and `lares://invite|login` links (M0-07).*
- **FND-061** `MVP` `done` — UI languages: English and Russian; follows the system language, overridable in settings.
  *Per-app language via AppCompat, also on the server screen (M0-07).*
- **FND-062** `MVP` `done` — Light and dark theme.
  *System, light or dark in Settings › Appearance (M0-07).*
- **FND-063** `MVP` `doing` — Home screen "Today" summarizes the sections the member can see: overdue and today's tasks,
  upcoming events and birthdays, the active shopping list, family on the map, budget of the month. Each module adds
  its block in its milestone; in M0 it is an empty state. *Header and empty state done (M0-07). Tasks block and the
  «Добавить» button (M1-07). Active shopping list card above the tasks (M2-06). The empty state remains for members who
  see no block.*
- **FND-064** `MVP` `done` — Bottom navigation: Today · Tasks · Shopping · Calendar · More; Map, Budget, Family and Tags
  are reached from "More" and from Today blocks (Q-013). *Bar and More done (M0-07); Family and Tags tiles open the Family screen (M0-08).*
- **FND-065** `MVP` `doing` — The UI follows the [visual style](../../design/visual-style.md) (ADR-0016).
  *Theme, fonts, icons, components (M0-07); applies to every later screen.*

### Operations

- **FND-070** `MVP` `done` — Deploy with one Docker Compose file on a VPS; HTTPS certificates obtained automatically.
- **FND-071** `MVP` `done` — Documented backup and restore of all data.
- **FND-072** `MVP` `done` — Server images for x86_64 and arm64.
- **FND-073** `MVP` `done` — Resource targets of [ADR-0013](../../adr/0013-server-resource-budget.md) are measured by a load simulation
  ([M0 baseline](../../architecture/perf/M0-baseline.md); re-run with location in M4).
- **FND-074** `later` `todo` — Household data export (JSON) from the app.

## Access model

Summary of [ADR-0014](../../adr/0014-group-based-permissions.md):

1. **Section access** — whether a member sees a section at all (Tasks, Shopping, Calendar, Map, Budget).
2. **Actions** — what a member may do inside a section.
3. **Data visibility** — only for sensitive data: who sees whose location; who sees which money account.
   Everything else in a section is visible to all members with access to it.
4. **Household management** — people, groups, permissions, settings: group "Administrators".

Administrators hold every permission and see all data. Ownership rules (FND-026) apply to everyone.
Persons (relatives) and tags are visible to all members.

Example:

| Member | Groups | Result |
| --- | --- | --- |
| Dad, Mom | Administrators | Everything, including household management |
| Son, 14 | Kids | Tasks, lists, calendar; sees parents on the map; sees his "Pocket money" account if granted `budget.view` and access to it |
| Grandma | "Grandparents" (custom) | Calendar and shopping lists only |

## Permission catalogue

Draft; each module spec extends it. Template defaults are chosen by agents and editable in the app.
"Administrators" has every permission. An action permission implies its section access.

| Permission | Kind | Meaning | Adults | Kids |
| --- | --- | --- | --- | --- |
| `household.manage` | management | Settings, invites, members, groups, permissions, location visibility rules | — | — |
| `persons.manage` | action | Add and edit relatives | ✓ | — |
| `tags.manage` | action | Create, rename, archive tags | ✓ | — |
| `tasks.view` | section | Open Tasks | ✓ | ✓ |
| `tasks.create` | action | Create tasks | ✓ | ✓ |
| `tasks.edit_any` | action | Edit, reassign, delete tasks created by others | ✓ | — |
| `shopping.view` | section | Open Shopping lists | ✓ | ✓ |
| `shopping.edit` | action | Add, edit, check off items | ✓ | ✓ |
| `shopping.manage_lists` | action | Create, rename, delete lists | ✓ | — |
| `calendar.view` | section | Open Calendar | ✓ | ✓ |
| `calendar.create` | action | Create events | ✓ | ✓ |
| `calendar.edit_any` | action | Edit, delete events created by others | ✓ | — |
| `location.view` | section | Open the Map (who is visible follows visibility rules) | ✓ | ✓ |
| `location.pause_own` | action | Pause own location sharing | ✓ | — |
| `location.manage_places` | action | Create and edit places (Home, School) | ✓ | — |
| `budget.view` | section | Open Budget (which accounts follows account access) | ✓ | — |
| `budget.manage` | action | Categories, allocation rules, debts, account access | ✓ | — |

Data visibility rules created with the templates:

| Rule | Default |
| --- | --- |
| Location | Adults see everyone, with history. Kids see Adults, current position only. Administrators always see everyone. |
| Money accounts | New accounts: view and edit for Adults. Kids: none unless an account is shared with them. Administrators always see all accounts. |

## Data

Household, User, Member, Group, GroupMembership, GroupPermission, Person, Invite, Session, Device.
See [domain model](../../domain/model.md).

## Out of scope

- A user belonging to several households at once (model allows it later; UI shows one household).
- Public registration. Members join only by invite.
