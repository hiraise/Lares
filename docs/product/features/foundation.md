---
status: draft
milestone: M0
updated: 2026-10-22
---
# Foundation (FND)

## Summary

Instance setup, identity, households, members, groups and permissions, persons, sync, notifications,
localization and deployment — everything feature modules depend on.

## Scenarios

1. Admin rents a VPS, runs `docker compose up`, opens the server URL, creates the first admin account and a household.
2. Admin invites the spouse by link into the "Adults" group; spouse installs the APK, opens the link, sets a password, and is in.
3. Admin creates an account for the daughter in the "Kids" group; her phone logs in by scanning a QR code on the admin's phone.
4. Admin creates a group "Grandparents" that may only see the calendar and shopping lists.
5. Admin adds grandma as a person (no account) with her birthday.
6. A member edits data on the subway without network; changes sync when the phone is back online.

## Requirements

### Instance and household

- **FND-001** `MVP` `todo` — On first start the server offers a one-time setup to create the first admin and household.
- **FND-002** `MVP` `todo` — One instance can host several households; data of different households is fully isolated.
- **FND-003** `MVP` `todo` — Household settings: name, time zone, currency, default language.

### Accounts and sessions

- **FND-010** `MVP` `todo` — A user logs in with login + password; email is optional. Passwords are hashed with a modern KDF.
- **FND-011** `MVP` `todo` — Sessions survive app restarts; a user can see and revoke their sessions (devices).
- **FND-012** `MVP` `todo` — A member with `household.manage` can reset another member's password.
- **FND-013** `MVP` `todo` — A user can delete their account from inside the app (also a Google Play requirement).
- **FND-014** `MVP` `todo` — Any member can log in on a new device by scanning a QR code shown on a logged-in device
  (their own, or one of a member with `household.manage`).
- **FND-015** `later` `todo` — Login via external OpenID Connect provider (Authentik, Keycloak).

### Members, groups and permissions ([ADR-0014](../../adr/0014-group-based-permissions.md))

- **FND-020** `MVP` `todo` — Permissions are granted through groups; a member can be in several groups;
  effective permissions are the union of the groups' permissions.
- **FND-021** `MVP` `todo` — Built-in group "Administrators": cannot be deleted, always has at least one member, has all permissions
  and sees all data regardless of visibility rules.
- **FND-022** `MVP` `todo` — Members with `household.manage` create, rename and delete groups, edit group permissions and membership.
- **FND-023** `MVP` `todo` — Household setup creates editable templates "Adults" and "Kids" with the defaults below.
- **FND-024** `MVP` `todo` — Invite by link or QR code with chosen groups; invites expire.
- **FND-025** `MVP` `todo` — Members with `household.manage` can remove members.
- **FND-026** `MVP` `todo` — Ownership rules: creators edit their own items, assignees complete their tasks,
  everyone manages their own profile, sessions and notifications — independent of groups.
- **FND-027** `MVP` `todo` — Permissions are enforced offline on the client and authoritatively on the server.
- **FND-028** `MVP` `todo` — Sections a member has no access to are hidden from navigation, search and tag pages.

### Persons

- **FND-030** `MVP` `todo` — A person is anyone relevant to the family: members and relatives without accounts.
- **FND-031** `MVP` `todo` — Person fields: name, optional birth date (year optional), relation label, photo, notes, contacts.
- **FND-032** `MVP` `todo` — Every member has a person profile; a relative can later be linked to a new account.

### Offline and sync

- **FND-040** `MVP` `todo` — All module data except live location is readable and editable offline.
- **FND-041** `MVP` `todo` — Offline changes sync automatically when connectivity returns; no data is lost on conflict
  (design in [ADR-0008](../../adr/0008-offline-first-sync.md)).
- **FND-042** `MVP` `todo` — Changes by other members appear without manual refresh while the app is open.
- **FND-043** `MVP` `todo` — The UI shows sync state (synced, pending changes, offline, error).

### Notifications

- **FND-050** `MVP` `todo` — Time-based reminders are scheduled on the device and fire without network.
- **FND-051** `MVP` `todo` — Event-based notifications (e.g. "task assigned to you", "task overdue") reach the phone
  without Google services ([ADR-0006](../../adr/0006-no-proprietary-dependencies.md)).
- **FND-052** `MVP` `todo` — Each user can mute notification types.

### Client basics

- **FND-060** `MVP` `todo` — On first launch the app asks for the server URL (or reads it from the invite link/QR).
- **FND-061** `MVP` `todo` — UI languages: English and Russian; follows the system language, overridable in settings.
- **FND-062** `MVP` `todo` — Light and dark theme.

### Operations

- **FND-070** `MVP` `todo` — Deploy with one Docker Compose file on a VPS; HTTPS certificates obtained automatically.
- **FND-071** `MVP` `todo` — Documented backup and restore of all data.
- **FND-072** `MVP` `todo` — Server images for x86_64 and arm64.
- **FND-073** `MVP` `todo` — Resource targets of [ADR-0013](../../adr/0013-server-resource-budget.md) are measured by a load simulation.
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
