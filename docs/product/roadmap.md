---
status: approved
updated: 2026-10-24
---
# Roadmap

Each milestone ends with a build the family can install (APK) and a short changelog for the customer in Russian.
**MVP = M0–M5.** Public release follows the MVP (see [ADR-0007](../adr/0007-distribution.md)).

| # | Milestone | Spec | State |
| --- | --- | --- | --- |
| M0 | Foundation | [foundation](features/foundation.md), [tags](features/tags.md) | implemented (M0-01…M0-11); customer acceptance pending |
| M1 | Tasks | [tasks](features/tasks.md) | implemented (M1-01…M1-07); customer acceptance pending |
| M2 | Shopping lists | [shopping](features/shopping.md) | spec approved, plans M2-01…M2-06 ready |
| M3 | Calendar | [calendar](features/calendar.md) | not started |
| M4 | Location | [location](features/location.md) | not started |
| M5 | Budget | [budget](features/budget.md) | not started |
| M5b | Files and photos | [files](features/files.md) | not started |
| M6 | Public release | — | not started |
| M7 | Web client | — | not started |

## M0 Foundation

Goal: everything later modules stand on, proven end to end.

- Gradle monorepo, CI (build, test, lint), release pipeline for server image and APK.
- Server: Ktor skeleton, PostgreSQL, migrations, health endpoint, structured logs.
- Identity: first-run admin setup, login, sessions, invitations (link / QR), QR login on new devices.
- Household, members, groups and permissions with templates "Adults" and "Kids" (ADR-0014).
- Persons (relatives without accounts).
- Tags (core entity and API; module integrations come with each module).
- Sync engine: offline storage on the client, push/pull protocol, conflict handling (ADR-0008).
- Notifications infrastructure (local reminders, push channel).
- Android shell: server URL, login, navigation, settings, en/ru localization.
- Deploy: Docker Compose with Caddy (auto HTTPS) for a VPS; backup and restore instructions.
- Load simulation measuring the resource budget (ADR-0013).

Exit: a family can install the server on a VPS, invite members, log in on several phones, configure groups,
and see tags sync offline → online; resource targets met.

## M1 Tasks

One-off and recurring tasks, assignment, completion history, reminders, overdue notifications, tags.
Family starts using the app daily ("family alpha").

## M2 Shopping lists

Multiple shared lists, quick add, check off in store offline, suggestions from history, automatic store departments
(Q-019), who is shopping right now, the active list on Today.

## M3 Calendar

Events, participants (members and relatives), recurrence, birthdays from persons, reminders, tags.

## M4 Location

Location sharing from phones, live map, history (30 days by default), places and geofence notifications,
visibility rules by groups.

## M5 Budget

Accounts, categories, transactions, allocation rules, category deficits, inter-category loans, debts with schedules,
reports, tags. One currency.

## M5b Files and photos

Photos on the family's server: person avatars, task photos, receipt photos (customer, 2026-10-23).
Numbered M5b so that the existing milestone numbers, referenced in accepted ADRs, stay unchanged.

## M6 Public release

F-Droid listing; decision on Google Play and, if yes, its listing (developer account, demo server, closed test);
privacy policy; user manuals (en, ru); final name and branding.

## M7 Web client

Browser access, first of all for budget and calendar on a large screen.

## Later (not scheduled)

- GPS trackers and kids' GPS watches via Traccar compatibility ([ADR-0009](../adr/0009-gps-trackers-via-traccar.md),
  [location § trackers](features/location.md#gps-trackers-later)). Customer has no devices yet (2026-10-22).
- Task rotation between members (TSK-014).
- Install and update the app from the family's own server (not allowed for Play-distributed builds).
- iOS and desktop clients (same KMP codebase).
- Calendar export / subscription (iCal feed), CalDAV sync.
- Budget: CSV import from banks, multi-currency.
- SOS button, low-battery alerts for members sharing location.

## Declined

- Kids' points and rewards (customer, 2026-10-22).
