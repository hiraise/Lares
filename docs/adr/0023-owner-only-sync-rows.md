---
status: accepted
date: 2026-10-24
---
# ADR-0023: Personal reminder rows sync only to their member

## Context

Every member can set their own reminder for any event (CAL-006, Q-021), offline, on all their phones. Until now every
sync type was visible to everybody with the section permission. Muted notification types are per member too, but they
go through a REST endpoint and do not work offline.

## Decision

1. `event_reminder(eventId, memberId, minutesBefore?)` is a client-writable sync type. `canSee` on the server returns
   true only for the member named in the row (and only with `calendar.view`). `memberId` must be the writer and never
   changes.
2. The id is derived from the event and the member (`Ids.reminderRowId`), so there is at most one row per pair and two
   own phones merge. Clients never delete these rows; "no reminder for me" is `minutesBefore = null`. The event's
   cascade tombstones them.
3. Administrators do not see other members' reminder rows: they are personal settings, not family data (Q-012 covers
   family data).

## Consequences

- The pull filter already skips rows `canSee` rejects while advancing the cursor; no protocol change.
- Visibility never changes for a row (its member is fixed), so no reset logic is needed.
- The member's birthday lead time is not per event and stays in the notification settings (REST), next to muted types.

## Alternatives considered

- **A REST resource like notification settings** — no offline changes, a second storage path for calendar data.
- **Visible to everybody** — leaks nothing sensitive but sends every member's settings to every phone for no use.
