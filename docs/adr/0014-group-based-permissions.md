---
status: accepted
date: 2026-10-22
---
# ADR-0014: Group-based permissions

## Context

The customer (2026-10-22): a child is a regular user like an adult; no special "child" role. The family admin
creates groups (e.g. "Kids", "Grandparents"), adds members, and grants permissions as needed.
Some access is about specific data, not actions: who sees whose location, who sees which money account.
An earlier draft had administrators "with all permissions" who still could not see private accounts — a contradiction,
since whoever manages permissions can grant themselves any access. Resolved with the customer: administrators see everything.

## Decision

1. **No fixed roles.** Permissions are granted to groups; a member can belong to any number of groups.
2. **Effective permissions = union** of the member's groups. Allow-only; no deny rules.
3. **Four kinds of permissions:**

   | Kind | Question it answers | Examples |
   | --- | --- | --- |
   | Section access | Does the member see this section at all? | `tasks.view`, `budget.view` |
   | Actions | What may the member do inside the section? | `tasks.create`, `calendar.edit_any`, `budget.manage` |
   | Data visibility | Which sensitive items inside the section are visible? | location rules, money account access |
   | Household management | Who manages people, groups and permissions? | `household.manage` |

   - Data visibility exists only where data is sensitive: **location** (rules `<subject> sees <target>`, current
     position or with history) and **money accounts** (per-account access for members/groups, view or edit).
     Everything else in a section (tasks, lists, events, tags, persons) is visible to everyone with section access.
   - An action permission implies access to its section; the UI grants both together.
4. **Built-in group "Administrators":** cannot be deleted, always has at least one member, holds every permission
   **and sees all data** — every account, every member's location and history — regardless of visibility rules.
   Nothing can be hidden from administrators. This is also technically honest: the server operator can read the database
   (ADR-0011).
5. **Templates at household setup:** groups "Adults" and "Kids" with default permissions
   ([foundation spec](../product/features/foundation.md#permission-catalogue)). They are ordinary groups: rename, edit, delete.
6. **Ownership rules apply regardless of groups:** the creator can edit their own items; an assignee can complete
   tasks assigned to them; every member manages their own profile, sessions and notification settings.
7. **Permission names** are `<module>.<action>`. The catalogue lives in the foundation spec; each module spec adds its own.
8. **Permission evaluation is shared Kotlin code** (ADR-0004): the client uses it offline to hide sections and block actions;
   the server is authoritative and rejects violating mutations (ADR-0008).
9. Login without email and login by QR code are available to every member, not tied to any group.

## Consequences

- One mechanism for every family structure (teenagers, grandparents, nannies); no special cases in code.
- Money privacy exists between non-admin members only. Spouses who are both administrators see each other's accounts.
- Reports and tag pages are computed per viewer from the data they can see.
- The group editor needs clear presets and plain wording; templates cover the common case.
- Every module spec lists its permissions and template defaults; tests cover each permission and visibility rule.
- Changing rules may hide data a member already synced: the client must purge it (ADR-0008 follow-up).

## Alternatives considered

- **Fixed roles admin / adult / child** — the customer rejected a special child role.
- **Administrators manage but cannot see owner-private data** — needs owner-controlled access that admins cannot override,
  and private-account spending could not use shared budget categories without leaking through category balances.
  Declined by the customer, 2026-10-22.
- **No section access, only actions** — simpler, but cannot express "grandma sees only calendar and lists".
- **Per-member permissions without groups** — tedious for several members.
- **Allow + deny rules** — more expressive but harder to understand; the customer chose allow-only.
