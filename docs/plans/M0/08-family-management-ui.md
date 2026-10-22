---
id: M0-08
status: ready
depends_on: [M0-07]
requirements: [FND-003, FND-012, FND-014, FND-020, FND-021, FND-022, FND-023, FND-024, FND-025, FND-030, FND-031, FND-032, TAG-001, TAG-006]
---
# M0-08: Android app — family management screens

## Goal

The "Family" area of the app: members, groups and permissions editor, invites with QR, login QR for a member,
relatives (persons), tags, household settings. Administrative actions call the REST API (online only);
persons and tags are written through the sync engine (work offline).

## Read first

- [Foundation spec](../../product/features/foundation.md) — access model, permission catalogue, requirements
- [Tags spec](../../product/features/tags.md), [ADR-0014](../../adr/0014-group-based-permissions.md)
- [API](../../architecture/api.md) admin endpoints, [auth design](../../architecture/auth.md) invites and login codes
- UI conventions and components from M0-07

## Scope

- Entry: avatar menu → "Family" (enable the item disabled in M0-07). Family screen with tabs: **Members**, **Relatives**,
  **Groups**, **Tags**; toolbar action "Family settings" (admins).
- Visibility: tabs Members/Relatives/Tags visible to every member (read-only without permissions); Groups visible to
  everyone but editable only with `household.manage`; every edit control hidden or disabled per effective permissions
  (`PermissionsRepository`).

Out of scope: location visibility rules editor (M4), money account access editor (M5), person photos (later).

## Decisions (do not revisit)

- **Online-only admin actions** (members, groups, invites, login codes, household settings): disable the action buttons
  while `SyncStatus.state == OFFLINE` and show "Needs internet connection". After a successful admin call, call
  `syncEngine.request()` so the change appears through sync (do not patch local tables directly).
- **Permission editor wording**: plain-language labels, grouped by section, defined in string resources, e.g.
  Tasks: "See tasks", "Create tasks", "Edit others' tasks"; Shopping: "See lists", "Add and check items",
  "Create and delete lists"; Calendar: "See calendar", "Create events", "Edit others' events"; Map: "See the map",
  "Pause own location sharing", "Manage places"; Budget: "See budget", "Manage budget"; Family: "Manage relatives",
  "Manage tags", "Manage the family (people, groups, rights)". Russian equivalents in `values-ru`.
  Checking an action auto-checks its section's "See …" (mirror `PermissionCatalogue.withImplied`); unchecking "See …"
  unchecks that section's actions.
- **Administrators group**: shown first with a lock icon and the text "Full access to everything, including all data";
  permissions not editable; membership editable (cannot remove the last member — show the `LAST_ADMIN` message).
- **Invite sharing**: show QR (qrose) + "Share link" (Android share sheet with the `familyhub://invite?...` payload and
  a short instruction text) + expiry time. List of active invites with "Revoke".
- **Login QR for a member** (admin): full-screen QR with 5-minute countdown and instruction
  "Open FamilyHub on the new phone → Log in with QR code".
- **Tags**: color chosen from the `TagColor` palette (10 swatches); archived tags in a collapsible section; delete asks
  for confirmation. Name conflicts surface as sync rejections (`TAG_NAME_TAKEN`) and as inline validation when the
  name already exists locally.
- **Relatives**: list sorted by name, members' profiles marked with a "member" badge; form fields: name, birthday
  (day + month required together, year optional — use pickers, not free text), relation (suggestions: mother, father,
  grandmother, grandfather, brother, sister, aunt, uncle, friend — free text allowed), notes, contacts (kind + value list).
  Deleting a member's profile is not offered.

## Steps

1. **FamilyScreen** with tabs and permission-aware actions; navigation routes in the `main` graph.
2. **Members tab**: list (avatar initials, name, groups as chips, "admin" badge); member detail: groups (multi-select
   dialog → `PUT /members/{id}/groups`), "Reset password" (dialog with new password + repeat), "Log in by QR" (login code
   for that member), "Remove from family" (confirmation). "Add member" FAB → choice: "Send invitation" (pick groups,
   expiry 1/3/7 days → invite QR/share) or "Create account without password" (name, groups → then show login QR). Both flows offer "This is an existing
   relative" — a picker of relatives not linked to any member; the choice is sent as `personId` (FND-032).
   Active invites list at the bottom with revoke.
3. **Groups tab**: list with member counts; editor screen (name, permissions checklist as decided, members checklist);
   create and delete (confirmation stating members lose these rights).
4. **Relatives tab**: list + detail/edit form as decided; create FAB (requires `persons.manage`); own profile editable by
   everyone.
5. **Tags tab**: list with color dots; create/edit bottom sheet (name + palette); archive/unarchive; delete.
6. **Family settings** (admins): family name, time zone picker, currency, default language → `PATCH /household`.
7. **Error and offline handling**: map `ErrorCode`s (`LAST_ADMIN`, `BUILTIN_GROUP`, `LOGIN_TAKEN`, `FORBIDDEN`,
   `VALIDATION_FAILED` fields) to messages; offline state disables admin actions as decided.
8. **Tests**:
   - ViewModel tests (jvmTest) with fakes: permission-based visibility of actions for admin / Adults / Kids;
     permission checklist implication logic; invite creation state; relative form validation (birthday rules incl. Feb 29
     without year); tag name local duplicate check.
   - Compose UI tests on JVM: group editor checklist behavior; members list shows admin badge.
   - Integration (in `integration-tests/`, reusing M0-06 setup): admin creates an invite via the client API layer, a second
     client accepts it, both see each other in `MembersRepository` after sync.
9. **Visual check on the emulator** (if available): walkthrough of every tab in Russian and English, light and dark theme;
   screenshots of: members list, member detail, invite QR, group editor, relatives form, tags, family settings.

## Acceptance

- `./gradlew check :client:android:assembleDebug` passes.
- Emulator walkthrough done (or reported as skipped with the reason), screenshots listed in the handoff.

## Docs to update

- Foundation spec and tags spec: requirement states for everything this plan completes (UI done) → `done`.
- [glossary](../../domain/glossary.md) if UI introduced new terms (keep RU labels consistent with the glossary).
- STATUS.md; plan status.

## Stop and report if

- A needed admin endpoint is missing or behaves differently from api.md.

## Handoff

Russian summary with screenshots: how to invite family members, create a child's account and log it in by QR,
configure groups and rights, add relatives and tags; how it was tested; next plan M0-09.
