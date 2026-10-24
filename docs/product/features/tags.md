---
status: approved
milestone: M0 (core), integrations in M1–M5
updated: 2026-10-24
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

- **TAG-001** `MVP` `done` — Members create, rename, recolor and archive tags (name, color, optional icon).
  *Server: tags through sync, `tags.manage` (M0-05); client core `TagsWriter`/`TagsRepository` (M0-06); Family › Tags:
  list, create/edit sheet with the palette, archive section, delete (M0-08). Icons are not offered in the UI yet.*
- **TAG-002** `MVP` `doing` — A tag can be attached to tasks, shopping lists and items, events, transactions and debts (as each module ships).
  *Tasks: done (M1-02, M1-06). Other modules add theirs.*
  *Tasks done: tags in the task editor, «+ Новая метка» for `tags.manage` (M1-06).*
- **TAG-003** `MVP` `doing` — Each module list can be filtered by tag.
  *Tasks: the «Метка» chip (M1-05). Other modules add theirs.*
  *Tasks done: «Метка» chip with a tag picker in the Tasks tab (M1-05).*
- **TAG-004** `MVP` `doing` — Tag page aggregates linked items across modules, grouped by module.
  *Page with the tasks section «Задачи · N» (archived tags too), opened from Family › Tags and from a tag chip on the
  task page; editing the tag moved to its top bar (M1-07). Other modules add their sections.*
- **TAG-005** `MVP` `todo` — Tag page shows spending for the tag over a chosen period (after M5).
- **TAG-006** `MVP` `done` — Tag names are unique within a household, case-insensitive.
  *Server: `TAG_NAME_TAKEN`, archived tags keep their name, deleted ones free it (M0-05); client checks local tags first (M0-06); inline "already exists" error in the tag sheet, sync rejections in
  the snackbar (M0-08).*
- **TAG-007** `later` `todo` — Places on the map can be tagged.
- **TAG-008** `later` `todo` — Nested tags.

## Permissions and privacy

`tags.manage` to create, rename, archive tags. Attaching a tag follows the edit permission of the item.
The tag page shows only items the viewer can see: no sections without access, no transactions of accounts not shared with them.

## Data

Tag, TagLink (tag ↔ any taggable entity). See [domain model](../../domain/model.md).

## Out of scope

- Per-member private tags.
