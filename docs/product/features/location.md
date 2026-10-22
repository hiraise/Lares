---
status: outline
milestone: M4 (phones, places); GPS trackers later
updated: 2026-10-22
---
# Location (LOC, TRK)

## Summary

See where family members are on a map. Phones share location through the app; later, GPS trackers
(kids' watches, car trackers) may feed positions through Traccar-compatible protocols. Places (home, school)
trigger arrival/departure notifications.

## Requirements — phones and map (M4, outline)

- **LOC-001** `MVP` `todo` — A member enables location sharing in the app; the phone sends positions in the background.
- **LOC-002** `MVP` `todo` — While sharing is on, the phone shows a persistent notification (Android foreground service).
- **LOC-003** `MVP` `todo` — Map with the latest position of each visible member: time of fix, accuracy, battery level.
- **LOC-004** `MVP` `todo` — Movement history per member and day, drawn as a track.
- **LOC-005** `MVP` `todo` — History is deleted after the retention period: 30 days by default, configurable by admin.
- **LOC-006** `MVP` `todo` — Visibility rules by groups and members: `<subject> sees <target>`, current position or with history;
  set by administrators; administrators see everyone. Pausing own sharing requires `location.pause_own`
  (ADR-0014; defaults in [foundation](foundation.md#permission-catalogue)).
- **LOC-007** `MVP` `todo` — Battery-aware tracking: frequent updates while moving, rare while stationary.
- **LOC-008** `MVP` `todo` — The app guides the user to disable battery optimization on aggressive vendors and warns when positions are stale.
- **LOC-009** `MVP` `todo` — Map tiles from an OpenStreetMap-based source configured on the server (ADR-0006).

## Requirements — places and geofences (M4, outline)

- **LOC-020** `MVP` `todo` — Places: named circle (center + radius) on the map, e.g. "Home", "School".
- **LOC-021** `MVP` `todo` — Subscriptions: `notify me when <member> arrives at / leaves <place>`.
- **LOC-022** `MVP` `todo` — Geofences are evaluated on the server, so they work for phones and trackers alike.
- **LOC-023** `later` `todo` — Schedule-aware alerts ("not at school by 8:30 on a weekday").

## GPS trackers (later)

Not scheduled: the customer has no trackers yet (2026-10-22). Kept for when it is planned.

- **TRK-001** `MVP` `todo` — A tracker is registered by admin and linked to a member or to an object (car, pet).
- **TRK-002** `MVP` `todo` — Trackers speaking the OsmAnd HTTP protocol report directly to the server.
- **TRK-003** `MVP` `todo` — Other protocols (incl. kids' GPS watches) via an optional Traccar server forwarding positions (ADR-0009).
- **TRK-004** `later` `todo` — Tracker battery and connectivity alerts.

## Privacy

- Location is the most sensitive data in the system. Positions are visible only per the visibility rules,
  never exposed through tags or other modules, and deleted after retention.
- The tracked person always sees that sharing is active.

## Open points

To be detailed before M4.
