---
status: outline
milestone: M3
updated: 2026-10-22
---
# Calendar (CAL)

## Summary

Family calendar with events and birthdays. Each event states who it concerns: members and relatives (persons).

## Requirements (outline)

- **CAL-001** `MVP` `todo` — Event: title, all-day or start/end time, location text, notes, tags.
- **CAL-002** `MVP` `todo` — Participants ("who it concerns"): any persons, members or relatives.
- **CAL-003** `MVP` `todo` — Recurrence: daily, weekly, monthly, yearly, with exceptions (edit one occurrence or the series).
- **CAL-004** `MVP` `todo` — Birthdays are generated from persons' birth dates, show age when the year is known;
  Feb 29 birthdays show on Feb 28 in non-leap years.
- **CAL-005** `MVP` `todo` — Other yearly dates per person (anniversary, memorial day).
- **CAL-006** `MVP` `todo` — Reminders per event; each user chooses their own reminders; default reminders for birthdays.
- **CAL-007** `MVP` `todo` — Member participants get notified when an event involving them is created or changed.
- **CAL-008** `MVP` `todo` — Views: month, week, agenda. Filter by person and tag.
- **CAL-009** `MVP` `todo` — Tasks with due dates appear in the agenda.
- **CAL-010** `later` `todo` — iCal subscription feed to see the family calendar in Google/Apple calendars.
- **CAL-011** `later` `todo` — CalDAV two-way sync.
- **CAL-012** `later` `todo` — Public holidays by country.

## Notes

- Times are stored as instants plus the event's time zone; all-day events and birthdays are dates without time zone.

## Open points

To be detailed before M3.
