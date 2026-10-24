---
status: approved  # customer, 2026-10-24 (Q-018…Q-020 answered the same day)
milestone: M2
updated: 2026-10-24
---
# Shopping lists (SHP)

## Summary

Shared shopping lists that work in a store with no network. Items are grouped by store department automatically; bought
items collapse at the bottom; whatever the family bought before comes back as one-tap suggestions. Everybody with access
to Shopping sees every list (Q-018); there are no shopping notifications (Q-020).
Technical design: [architecture/shopping.md](../../architecture/shopping.md).

## Scenarios

1. Mom types «Молоко 2 л, хлеб, яблоки 1 кг» into «Продукты»: three items appear, already under «Молочное и яйца»,
   «Хлеб и выпечка» and «Овощи и фрукты», with the quantities on the right.
2. Dad is in the store with no network. He ticks items; they move to «Куплено · 3» at the bottom; a banner says the marks
   are saved and will be sent. Back online, Mom's phone shows them bought.
3. Mom and Dad shop in the same store from one list. Each sees the other's ticks within seconds; the list shows
   «Дмитрий сейчас в магазине».
4. At home Mom opens Today: the hero card shows «Продукты», «6 из 9», «Осталось: Молоко, Хлеб, Яблоки» and
   «Дмитрий сейчас в магазине». She adds «Сметана» — Dad sees it appear while he is still in the store.
5. After the trip Dad taps «Убрать купленные». The list is clean; next week «+ Молоко», «+ Хлеб», «+ Яйца» are offered as
   suggestions under the input, and typing «тв» offers «Творог».
6. «Творожный сыр» landed in «Молочное и яйца», but the family buys it elsewhere; Mom moves it to «Другое». From now on
   it goes there in every list.
7. Аня (Kids) adds «Тетради» to «Канцтовары» and ticks things off, but cannot create, rename or delete lists.

## Requirements

### Lists

- **SHP-001** `MVP` `todo` — Several lists per household (e.g. «Продукты», «Аптека», «Хозтовары»). Create, rename, delete.
  Deleting a list asks for confirmation and removes its items and suggestions for everybody.
- **SHP-006** `MVP` `todo` — Items to buy are grouped by store department in a fixed store order (fruit and vegetables,
  bread, dairy and eggs, meat and fish, pantry, sweets, drinks, frozen, household, personal care, baby, pets, pharmacy,
  other). The department is chosen automatically from a built-in dictionary (Russian and English names); unknown items
  go to «Другое». A member can move an item to another department; the family's choice is remembered for that product
  name in all lists (Q-019). Grouping can be turned off per list (e.g. «Подарки»).

### Items

- **SHP-002** `MVP` `todo` — Item: name (required), optional quantity with unit as free text («2», «1,5 кг», «3 уп»),
  optional note, tags, department. The list shows who added an item (if not me) and who bought it and when.
- **SHP-003** `MVP` `todo` — Quick add: one input at the top of the list; one entry per line, several entries at once
  separated by commas or line breaks («молоко, хлеб, яйца»); a quantity at the end of an entry is recognized
  («Молоко 2 л», «Яйца 10», «Хлеб x2»). A comma inside a number does not split («Молоко 2,5%»).
- **SHP-008** `MVP` `todo` — Suggestions from history: under the empty input, chips with items bought in this list
  before and not in it now (most often bought first, at most 8); while typing, up to 5 matching names from this list and
  the family's other lists. A tap adds the item with its department and note. A suggestion can be removed for good
  («Больше не предлагать»).
- **SHP-009** `MVP` `todo` — No duplicates: adding a name that is already to buy in this list does not create a second
  item («Уже в списке»; a new quantity replaces the old one). Adding a name that was bought or removed earlier brings
  that item back to the list (with its department and note).
- **SHP-010** `MVP` `todo` — Edit an item (name, quantity, note, department, tags) from its sheet (long press);
  «Убрать из списка» removes it from the list but keeps it as a suggestion; undo right after.

### In the store

- **SHP-004** `MVP` `todo` — Tick items off offline: a tap on the row marks it bought; bought items move to a collapsed
  «Куплено · N» section at the bottom (expandable; a tap there returns an item to the list). «Убрать купленные» clears
  them (they stay as suggestions), with undo. Bought items stay until somebody clears them.
- **SHP-005** `MVP` `todo` — Two people shopping at once see each other's check-offs within seconds when online.
- **SHP-011** `MVP` `todo` — Without network the list shows a banner «Нет сети. Отметки сохранены и отправятся, когда
  связь появится.» (or «Нет сети. Список может быть не самым свежим.» when nothing is waiting to be sent) (FND-043).
- **SHP-012** `MVP` `todo` — "Who is shopping": if another member ticked an item in a list during the last 15 minutes,
  its card and the Today block show «Дмитрий сейчас в магазине», the list page the short «Дмитрий в магазине»
  (Q-020). Derived from the ticks; nothing extra is sent or stored.

### Elsewhere in the app

- **SHP-013** `MVP` `todo` — Today block (FND-063): the active list — the list with items to buy that changed most
  recently — as the hero card: name, «6 из 9» (bought of all), «Осталось: Молоко, Хлеб, Яблоки», progress, who is
  shopping, «Открыть»; «Ещё N списка» when other lists have items to buy. No card when nothing is to buy.
- **SHP-014** `MVP` `todo` — Tags: lists and items can carry tags (TAG-002). The list page can be filtered by a tag used
  in it (TAG-003). The tag page shows the items to buy with this tag and the tagged lists (TAG-004).

### Later

- **SHP-007** `later` `todo` — Turn a finished trip into a budget expense (after M5).
- **SHP-015** `later` `todo` — Stores: department order per store, remembered.
- **SHP-016** `later` `todo` — Hidden lists visible only to chosen members, e.g. gifts (Q-018).
- **SHP-017** `later` `todo` — Notifications: «Иду в магазин» and new items added while someone is shopping (Q-020).
- **SHP-018** `later` `todo` — Move an item to another list; reorder items by hand; item photos (M5b).

## Permissions and privacy

Everybody with access to Shopping (`shopping.view`) sees all lists of the family (Q-018, ADR-0014). Members without it
receive no shopping data. Permissions ([catalogue](foundation.md#permission-catalogue)):

| Action | Who |
| --- | --- |
| See lists and items, who added and who bought | `shopping.view` |
| Add, edit, tick off, remove items; move to another department; remove a suggestion | `shopping.edit` |
| Create, rename, delete lists; list tags; department grouping switch | `shopping.manage_lists` |

Templates: Adults have all three; Kids have `shopping.view` and `shopping.edit`. Administrators have everything.
The "who is shopping" line uses only the ticks every member with access already sees; no location is used.

## Data

Shopping list, shopping item. An item stands for "this product in this list" and is kept after it is bought or removed:
that is the list's memory for suggestions and departments ([ADR-0021](../../adr/0021-shopping-items-kept-as-list-memory.md)).
Tags are ids inside the list or item ([ADR-0018](../../adr/0018-tags-embedded-in-entities.md)).

## Notifications

None in M2 (Q-020). Changes appear on open phones within seconds through the realtime sync channel (FND-042).

## Customer decisions (2026-10-24)

- Q-018 — every member with access to Shopping sees all lists; hidden lists later (SHP-016).
- Q-019 — departments are chosen automatically; a correction is remembered by the family.
- Q-020 — no shopping notifications; the "who is shopping" line instead.

## Assumptions

Agent defaults, easy to change after feedback:

- The Shopping tab shows the lists (in creation order) with progress; a tap opens a list. Today opens the active list directly.
- A tap anywhere on an item row ticks it (big target in the store); a long press opens the item sheet.
- Re-adding an item keeps its note and department; the quantity is the newly typed one (or none).
- A quantity is recognized only at the end of an entry; «2 кг картошки» stays one name.
- Unticking a bought item returns it to the list; nothing is cleared automatically.
- Department headers are shown when the items to buy span at least two departments.
- Concurrent first-time adds of the same new name on two offline phones can give two items; either can be removed.

## Out of scope (M2)

- Prices and totals (budget link is SHP-007).
- Barcode scanning, product photos, recipes, sharing lists outside the family.
- Ordering a list by hand, moving items between lists (SHP-018).
- Per-member list visibility (SHP-016), shopping notifications (SHP-017).
