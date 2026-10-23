---
status: outline
milestone: M5b
updated: 2026-10-23
---
# Files and photos (FIL)

## Summary

Photos stored on the family's own server and attached to persons, tasks and transactions. Decided by the customer on
2026-10-23: a separate milestone right after the MVP (after M5 Budget), before the public release (M6).
Detail this spec right before M5b starts.

## Scenarios

1. Mom sets her photo as an avatar; the whole family sees it on tasks, in the calendar and on the map.
2. Dad attaches a photo "this shelf, this size" to the task "Buy a shelf".
3. After shopping, the receipt is photographed and attached to the expense.

## Requirements

- **FIL-001** `MVP` `todo` — Members upload photos to the household's server; the client downscales them
  (long side ≤ 1600 px, about 200–300 KB) before upload.
- **FIL-002** `MVP` `todo` — A photo is visible exactly to those who can see the item it is attached to.
- **FIL-003** `MVP` `todo` — A photo added offline is uploaded later; viewed photos are cached on the device.
- **FIL-004** `MVP` `todo` — Photos are part of the household data: included in backup and restore, deleted with
  their item.
- **FIL-005** `MVP` `todo` — Photos are stored on the server's own disk; the admin sees how much space they use.
- Uses: person photo (FND-033), task photos (TSK-006), receipt photos (BUD-051).

## Permissions and privacy

Follows the item (FIL-002). No public links; photos are served only to logged-in members of the household.

## Assumptions

- ASSUMPTION: about 50 MB per family per year; to be checked against the resource budget (ADR-0013) in M5b.
