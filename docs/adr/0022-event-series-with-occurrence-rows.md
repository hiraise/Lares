---
status: accepted
date: 2026-10-24
---
# ADR-0022: A repeating event is one row; changed occurrences are rows with derived ids

## Context

The calendar (CAL-003, CAL-015) needs repeating events whose single occurrences can be moved, changed or cancelled,
and series that can be split ("this and following"). Everything works offline on several phones and syncs with
field-level last-writer-wins in server arrival order ([sync](../architecture/sync.md)). A tombstoned id can never be
written again. Tasks already compute routine occurrences instead of storing them (ADR-0017) and keep dates as wall-clock
values in the household time zone.

## Decision

1. A repeating event is one `event` row with the tasks' fixed `Recurrence` and an optional `repeatUntil`. Occurrences
   are computed by `EventSchedule` in `:contract`; nothing is stored per occurrence unless it differs.
2. A changed or cancelled occurrence is an `event_occurrence` row keyed by the scheduled date it overrides. Each override
   field is nullable (null = as the series), so later changes of the series still reach changed occurrences.
3. The row id is **derived** from the event id and the date (`Ids.occurrenceRowId`, version-8 UUID). Two phones that
   change the same occurrence offline write the same row; their fields merge like any other entity. Server and client
   check that the id matches.
4. Clients never delete occurrence rows (the delete gate is false). Resetting an occurrence writes an empty override.
   Only the event's cascade tombstones them, and an event id is never reused, so a derived id never hits a tombstone.
5. "This and following" creates a new series with `splitFrom` = the old id and ends the old one with `repeatUntil`.
6. Event dates and times are local dates and wall-clock times in the household time zone, like tasks. Events in other
   time zones are later (CAL-022). This replaces "event times carry an IANA zone id" in the conventions.

## Consequences

- A daily event is still one row; month and week views compute occurrences on the phone (a few hundred events at most).
- Concurrent edits of one occurrence merge instead of producing duplicates; concurrent edits of the series and of an
  occurrence both survive.
- Schedule changes of the whole series reset changed occurrences (spec assumption, the app warns).
- Empty override rows accumulate only on explicit resets; they are purged with their event.
- The derivation is shared code (and repeated in `seed.py`); changing it would orphan existing rows.

## Alternatives considered

- **Overrides embedded in the event row (a map by date)** — field-level LWW would lose one of two concurrent
  occurrence edits.
- **Occurrence rows with random ids** — duplicates for the same date need a winner rule, and a reset that deletes a row
  cannot be followed by a new change of that date without another fresh id; views get harder.
- **Storing every occurrence** — unbounded rows for open-ended series, series edits touch many rows.
- **RFC 5545 RRULE strings** — far more than the spec needs; CalDAV (CAL-011) can map the subset later.
