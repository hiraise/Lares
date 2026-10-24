---
status: accepted
date: 2026-10-24
---
# ADR-0021: A shopping item is "this product in this list" and is kept after it is bought

## Context

The shopping spec asks for suggestions from previously bought items (SHP-008), "clear bought" (SHP-004), no duplicates
when a name is added again (SHP-009) and departments that the family corrects once and that stay corrected (SHP-006,
Q-019). Everything must work offline on several phones and merge field by field (ADR-0008). A family buys the same
few hundred products over and over; what grows over time is the number of purchases, not the number of products.

## Decision

1. A `shopping_item` row stands for one product in one list, not for one purchase. Its `status` moves
   `needed` → `bought` → `cleared` and back to `needed` when the product is added again. "Clear bought" and "remove from
   list" set `cleared`; only "remove the suggestion for good" and deleting the list tombstone item rows.
2. Adding a name first looks for a row of the same list with the same name key (`ShoppingNames.key`: lower case,
   `ё` → `е`, letters and digits only, single spaces). A `needed` match is kept (no duplicate); a `bought` or `cleared`
   match returns to `needed`. Only a name the list has never had creates a row.
3. Suggestions are the list's `cleared` rows ranked by `boughtCount`, then `boughtAt`; autocomplete also reads the
   family's other lists. No separate product or purchase entity exists.
4. The department (`category`) is stored on the item and chosen by the phone that creates the row: the category of an
   existing row with the same key anywhere in the household, else the built-in dictionary
   (`ShoppingCategories.guess`), else `other`. A correction rewrites the category of every row with that key in the
   household — that is the family's memory.
5. `boughtCount` is an ordinary field (last writer wins). Two phones ticking the same item offline may count one
   purchase instead of two; it only ranks suggestions.

## Consequences

- Rows are bounded by distinct products per list, not by purchases; nothing needs pruning in M2.
- Re-adding brings back the note and the department for free; field-level merge handles concurrent tick, clear and
  re-add of one row without special rules.
- Two offline phones adding the same new name at the same time create two rows. Rare; both are visible and either can
  be removed. The client never merges rows by itself.
- Removing every row of a name forgets the family's correction for it. A new app version with a better dictionary does
  not move existing items.
- Items are children of a list: deleting the list tombstones them (server cascade, like tasks).

## Alternatives considered

- **Delete items on "clear" plus a household `shopping_product` catalogue entity** (name, department, counters) —
  one more synced type, two writes per add, and the catalogue's counters conflict in the same way.
- **A purchase log** (one row per purchase) — grows forever, and suggestions would need aggregation on every phone.
- **Deterministic item ids from (list, name)** — no duplicates even offline, but re-adding a name whose row was removed
  for good hits `ENTITY_DELETED` until the tombstone is purged, renames break the mapping, and common Kotlin has no
  name-based UUID.
