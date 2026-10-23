---
status: approved
milestone: M0 (core), integrations in M1–M5
updated: 2026-10-23
---
# Tags (TAG)

## Summary

A tag is a household-wide label such as "Car", "Dacha", "School". It can be attached to items in every module.
A tag page shows everything linked to it: open tasks, upcoming events, shopping items, spending.
Tags complement budget categories: car spending spread over "Fuel", "Insurance", "Repairs" is summed under the tag "Car".

## Scenarios

1. Creating the recurring task "Change oil", the user adds the tag "Car".
2. The insurance renewal event and the insurance payment also get "Car".
3. Opening the "Car" tag shows the next oil change, the renewal date and car spending this year.

## Requirements

- **TAG-001** `MVP` `doing` — Members create, rename, recolor and archive tags (name, color, optional icon).
  *Server: tags through sync, `tags.manage` (M0-05); UI: later plan.*
- **TAG-002** `MVP` `todo` — A tag can be attached to tasks, shopping lists and items, events, transactions and debts (as each module ships).
- **TAG-003** `MVP` `todo` — Each module list can be filtered by tag.
- **TAG-004** `MVP` `todo` — Tag page aggregates linked items across modules, grouped by module.
- **TAG-005** `MVP` `todo` — Tag page shows spending for the tag over a chosen period (after M5).
- **TAG-006** `MVP` `doing` — Tag names are unique within a household, case-insensitive.
  *Server: `TAG_NAME_TAKEN`, archived tags keep their name, deleted ones free it (M0-05); UI: later plan.*
- **TAG-007** `later` `todo` — Places on the map can be tagged.
- **TAG-008** `later` `todo` — Nested tags.

## Permissions and privacy

`tags.manage` to create, rename, archive tags. Attaching a tag follows the edit permission of the item.
The tag page shows only items the viewer can see: no sections without access, no transactions of accounts not shared with them.

## Data

Tag, TagLink (tag ↔ any taggable entity). See [domain model](../../domain/model.md).

## Out of scope

- Per-member private tags.
