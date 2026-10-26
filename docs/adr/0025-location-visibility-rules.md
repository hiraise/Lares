---
status: accepted
date: 2026-10-25
---
# ADR-0025: Location visibility rules

## Context

ADR-0014 defines data visibility "who sees whose location" by groups and members, set by administrators; Q-008 fixed
the defaults (Adults see everyone with history, Kids see Adults where now). LOC-006 needs two levels (current position,
with history), LOC-015 needs the tracked member to see who sees them. Template groups carry no marker in the data.

## Decision

1. A rule is `subject → target : level`. Subject and target are each EVERYONE, a GROUP or a MEMBER; level is CURRENT or
   HISTORY. Rules are stored on the server (`location_rule`), at most 100 per household, edited as a whole list by
   administrators (`GET/PUT /location/rules`, `household.manage`).
2. Evaluation (`LocationVisibility.level`, shared pure code): no `location.view` → NONE; oneself → HISTORY;
   administrators → HISTORY; otherwise the highest level of all matching rules; no match → NONE.
3. New households get the default rules at setup; the migration adds them to existing households whose groups are named
   Adults/Взрослые and Kids/Дети.
4. Deleting a group or a member deletes the rules naming it.
5. Rules are not synced to phones; a viewer learns only the levels that concern them (in `/location/now`), the tracked
   member learns who sees them (`/location/me/watchers`).

## Consequences

- No "deny" rules: visibility only widens. Simple to explain («кто кого видит»), no conflicts to resolve.
- Rule changes take effect at once on the live channel (recompute per connection) and need no client data changes.
- The admin editor works online only, like the other admin screens.

## Alternatives considered

- **Per-pair matrix (member × member)** — explicit but grows with the family and breaks when people join; groups cover
  the cases the customer named.
- **Rules as a sync type** — every phone would learn everybody's visibility for no use, and visibility of the rows
  themselves would need rules.
