---
status: accepted  # design of M2; binding for the M2 plans
updated: 2026-10-24
---
# Shopping module design (M2)

Technical design for the [shopping spec](../product/features/shopping.md). Decisions behind it:
[ADR-0021](../adr/0021-shopping-items-kept-as-list-memory.md) (items are kept as the list's memory) and
[ADR-0018](../adr/0018-tags-embedded-in-entities.md) (tags). Implemented by plans [M2-01…M2-06](../plans/README.md#m2-plans).

## Overview

- Two client-writable sync types: `shopping_list` and its child `shopping_item`.
- An item is "this product in this list" (ADR-0021). `status` moves `needed` → `bought` → `cleared` → `needed`;
  clearing and removing never delete. Only "remove the suggestion for good" and list deletion tombstone items.
- Adding matches existing rows of the list by a **name key**; the department of a new row comes from the household's
  existing rows with that key, else from a built-in dictionary (`ShoppingCategories`), else `other`.
- Suggestions, autocomplete, "who is shopping" and the Today card are computed on the phone from the local tables.
- The server stores, checks and syncs; it sends no shopping notifications (Q-020) and runs no shopping jobs.
- Realtime delivery of ticks (SHP-005) is the existing sync events channel (FND-042); nothing new.

## Contract data (`contract/…/entities/ShoppingEntities.kt`)

```kotlin
@Serializable
data class ShoppingListData(
    val name: String,                                   // trimmed, 1..100
    val createdBy: String,                              // member id; set on create, never changes
    val tagIds: Set<String> = emptySet(),               // TAG-002
    val groupByCategory: Boolean = true,                // SHP-006: sections by department
)

@Serializable
data class ShoppingItemData(
    val listId: String,                                 // never changes
    val name: String,                                   // trimmed, inner whitespace collapsed, 1..200
    val addedBy: String,                                // member who added it to the list last (always the writer)
    val addedAt: Instant,                               // when it was added last; orders the list (kotlin.time.Instant)
    val status: ShoppingItemStatus = ShoppingItemStatus.NEEDED,
    val category: ProductCategory = ProductCategory.OTHER,
    val quantity: String? = null,                       // free text with unit: "2", "1,5 кг"; blank -> null, ≤ 30
    val note: String? = null,                           // blank -> null, ≤ 500
    val tagIds: Set<String> = emptySet(),
    val boughtBy: String? = null,                       // member who ticked it last (always the writer)
    val boughtAt: Instant? = null,                      // kept after clearing: ranks suggestions, "who is shopping"
    val boughtCount: Int = 0,                           // times ticked; 0..100_000; ranks suggestions
)
```

- `ShoppingItemStatus`: `needed`, `bought`, `cleared` (`@SerialName`), lenient with fallback `CLEARED` — an unknown future
  state hides the item rather than showing it as something to buy.
- `ProductCategory` in **store order** (the enum order is the display order):

  | Enum | Wire | RU | EN |
  | --- | --- | --- | --- |
  | `PRODUCE` | `produce` | Овощи и фрукты | Fruit and vegetables |
  | `BAKERY` | `bakery` | Хлеб и выпечка | Bread and bakery |
  | `DAIRY` | `dairy` | Молочное и яйца | Dairy and eggs |
  | `MEAT_FISH` | `meat_fish` | Мясо и рыба | Meat and fish |
  | `GROCERY` | `grocery` | Бакалея | Pantry |
  | `SWEETS` | `sweets` | Сладкое и снеки | Sweets and snacks |
  | `DRINKS` | `drinks` | Напитки | Drinks |
  | `FROZEN` | `frozen` | Замороженное | Frozen |
  | `HOUSEHOLD` | `household` | Хозтовары и химия | Household |
  | `PERSONAL_CARE` | `personal_care` | Гигиена и косметика | Personal care |
  | `BABY` | `baby` | Детское | Baby |
  | `PETS` | `pets` | Для животных | Pets |
  | `PHARMACY` | `pharmacy` | Аптека | Pharmacy |
  | `OTHER` | `other` | Другое | Other |

  Lenient with fallback `OTHER`. Labels are UI strings (`shopping_category_<wire>`), not contract data.
- Entity types: `EntityTypes.SHOPPING_LIST = "shopping_list"`, `SHOPPING_ITEM = "shopping_item"`.

The server's strict round trip rejects unknown `status`/`category` values (`INVALID_FORMAT`), as for other lenient enums.

## Names and quick add (`contract/…/shopping/`)

### `ShoppingNames`

- `normalize(name)`: trim, collapse runs of whitespace to one space. Stored names are normalized (validators' normalizer).
- `key(name)`: `lowercase()`, `ё` → `е`, every character that is not a letter or digit → space, collapse spaces, trim.
  `"Молоко 2,5%"` → `"молоко 2 5"`. Two items match iff their keys are equal. The client stores the key in a local
  column; it never travels.
- `displayName(name)`: if the first word has no upper-case letter, upper-case its first character
  (`"молоко"` → `"Молоко"`, `"iPhone чехол"` unchanged). Applied by the quick-add parser only.

### `QuickAdd.parse(text): List<QuickAddEntry>` — `data class QuickAddEntry(val name: String, val quantity: String?)`

1. Split into entries at line breaks, at `;`, and at `,` unless the comma has a digit on both sides (`2,5` stays).
2. Each entry: `ShoppingNames.normalize`; drop empty entries; keep at most `QuickAdd.MAX_ENTRIES = 50` entries.
3. Trailing quantity (only at the end of an entry; case-insensitive), first matching rule:
   - `<name> <number> <unit>` or `<name> <number><unit>` with an optional `.` after the unit → quantity
     `"<number> <unit lower case>"`;
   - `<name> <number>` → quantity `"<number>"`;
   - `<name> x<digits>`, `х<digits>` (Cyrillic), `×<digits>`, or `<digits>x` / `<digits>х` / `<digits>×` as the last
     word → quantity `"<digits>"`.

   `<number>` = `\d+([.,]\d+)?`, written back as typed. Units: `шт штук штуки штука кг г гр грамм л литр литра литров мл
   уп упак упаковка упаковки пач пачка пачки пачек бут бутылка бутылки банка банки пакет пакета пакетов рулон рулона
   рулонов десяток pc pcs piece pieces kg g gr l ml lb lbs oz pack packs bottle bottles can cans box boxes bag bags roll
   rolls dozen`. A number followed by anything else (`2,5%`, `AA`) is not a quantity. Leading numbers are never parsed
   (`"2 кг картошки"` is a name).
4. If what is left as the name has no letter, the whole entry is the name without quantity (`"1984"`).
5. Name: `ShoppingNames.displayName`, cut to 200 characters; quantity cut to 30. Entries whose name key is empty
   (`"—"`, `"!!!"`) are dropped.

| Input | Result |
| --- | --- |
| `Молоко 2 л, хлеб, яблоки 1 кг` | `Молоко`/`2 л`, `Хлеб`/–, `Яблоки`/`1 кг` |
| `Молоко 2,5%` | `Молоко 2,5%`/– |
| `Яйца 10` · `Хлеб x2` · `Хлеб х2` · `Хлеб 2×` | `Яйца`/`10` · `Хлеб`/`2` (all three) |
| `Батарейки AA 4 шт.` | `Батарейки AA`/`4 шт` |
| `Сыр 0,5КГ` · `Cheese 1.5 lb` | `Сыр`/`0,5 кг` · `Cheese`/`1.5 lb` |
| `молоко,хлеб` · `Вода 5 л; сок` | `Молоко`, `Хлеб` · `Вода`/`5 л`, `Сок` |
| `2 кг картошки` · `1984` · `Кола 0,5` | `2 кг картошки`/– · `1984`/– · `Кола`/`0,5` |
| `"Молоко\n\n  хлеб  "` · `"   "` | `Молоко`, `Хлеб` · nothing |

## Departments (`contract/…/shopping/ShoppingCategories.kt`)

`ShoppingCategories.guess(name: String): ProductCategory` — pure, deterministic, no I/O. The data lives in
`CategoryDictionary` (same package, `internal`): for each category except `OTHER`, Russian and English **stems**
(at least 20 Russian and 15 English per category), **phrases** (stems of consecutive words, e.g. `"подсолнечн масл"`),
and **modifiers** (stems that decide the department wherever they stand: `FROZEN`: `замороженн`, `заморожен`, `frozen`;
`GROCERY`: `консерв`, `canned`, `tinned`). All entries are written as keys (lower case, `е` for `ё`, letters only).

A stem **matches** a word if the word starts with it and has at most 3 more characters (stems of 3 characters or fewer:
the word equals the stem or has 1 more character); phrase stems match the same way. A modifier matches any word that
starts with it. Algorithm:

1. `words` = `ShoppingNames.key(name)` split by space. `head` = words before the first preposition
   (`для от из с со без в во на под к по for with without from of in on`), `tail` = the words after it.
2. **Phrases** over all words: if any matches, the longest one (by stem characters) decides.
3. **Modifiers** anywhere: the first matching modifier in word order decides.
4. **Words** in `head` (then, if nothing matched, in `tail`): among words that match a stem, prefer words that do not
   look like Russian adjectives (Cyrillic, length ≥ 5, ending in `ый ий ой ая яя ое ее ые ие ую юю ого его ым им`);
   among those, the **last** one; for that word the longest matching stem decides.
5. Otherwise `OTHER`.

Every stem belongs to one category only (a test checks it). Recommended phrases besides those the tables need:
`детск питан` (`BABY`), `мыть посуд` (`HOUSEHOLD`). The dictionary must classify this table exactly (the test
table of M2-01; it may grow, never shrink):

| RU → category | | |
| --- | --- | --- |
| Яблоки, Бананы, Картошка, Помидоры черри, Огурцы, Лук репчатый, Зелёный лук, Морковь, Укроп, Лимон → `PRODUCE` | Хлеб, Батон нарезной, Лаваш, Круассаны → `BAKERY` | Молоко 2,5%, Молоко без лактозы, Кефир, Сметана 20%, Творог, Сыр российский, Сливочное масло, Масло, Яйца, Йогурт питьевой → `DAIRY` |
| Курица, Куриное филе, Фарш говяжий, Колбаса докторская, Сосиски, Лосось, Креветки → `MEAT_FISH` | Гречка, Рис, Макароны, Мука, Сахар, Соль, Подсолнечное масло, Оливковое масло, Томатная паста, Горошек консервированный, Овсяные хлопья, Майонез → `GROCERY` | Печенье овсяное, Шоколад, Конфеты, Чипсы → `SWEETS` |
| Вода, Минеральная вода, Апельсиновый сок, Зелёный чай, Кофе молотый, Пиво → `DRINKS` | Мороженое, Пельмени, Замороженные овощи → `FROZEN` | Туалетная бумага, Средство для мытья посуды, Стиральный порошок, Губки для посуды, Мешки для мусора, Батарейки AA → `HOUSEHOLD` |
| Зубная паста, Шампунь, Дезодорант, Мыло → `PERSONAL_CARE` | Подгузники, Детское питание → `BABY` | Корм для кошек, Наполнитель для лотка → `PETS` |
| Парацетамол, Пластырь, Таблетки от головы → `PHARMACY` | Тетради, Подарок маме → `OTHER` | |

| EN → category | | |
| --- | --- | --- |
| Apples, Bananas, Potatoes, Tomatoes, Onions → `PRODUCE` | Bread, Bagels → `BAKERY` | Milk, Eggs, Butter, Cheddar cheese, Greek yogurt → `DAIRY` |
| Chicken breast, Ground beef, Salmon → `MEAT_FISH` | Rice, Pasta, Flour, Olive oil, Peanut butter, Tomato paste, Canned beans → `GROCERY` | Cookies, Chocolate → `SWEETS` |
| Orange juice, Green tea, Coffee → `DRINKS` | Ice cream, Frozen peas → `FROZEN` | Toilet paper, Dish soap, Laundry detergent → `HOUSEHOLD` |
| Toothpaste, Shampoo → `PERSONAL_CARE` | Diapers → `BABY` | Cat food → `PETS` |
| Ibuprofen → `PHARMACY` | Birthday card → `OTHER` | |

## Write rules (`contract/…/permissions/ShoppingRules.kt`)

`WriteRules.canMutate` gates: `shopping_list` → `shopping.manage_lists`; `shopping_item` → `shopping.edit` (both ops; action
permissions imply `shopping.view`). Entity-level checks, used by the server handlers and the client adapters:

```kotlin
object ShoppingRules {
    fun canManageLists(viewer: Viewer): Boolean                              // SHOPPING_MANAGE_LISTS
    fun canEditItems(viewer: Viewer): Boolean                                // SHOPPING_EDIT
    fun canCreateList(viewer: Viewer, list: ShoppingListData): Boolean      // canManageLists && createdBy == me
    /** Author fields the write sets to someone else: addedBy (create or changed), boughtBy (changed to non-null). */
    fun foreignAuthorFields(viewer: Viewer, existing: ShoppingItemData?, merged: ShoppingItemData): Set<String>
}
```

| Action | Allowed when |
| --- | --- |
| create a list | `canCreateList` |
| rename, tag, toggle grouping, delete a list | `canManageLists` |
| create, change, delete an item | `canEditItems` and `foreignAuthorFields` is empty (else `FORBIDDEN`) |

## Validation (`Validators.shoppingList`, `.shoppingItem`; normalizers `normalizeShoppingList`, `normalizeShoppingItem`)

Constants: `SHOPPING_LIST_NAME_MAX = 100`, `SHOPPING_ITEM_NAME_MAX = 200`, `SHOPPING_QUANTITY_MAX = 30`,
`SHOPPING_NOTE_MAX = 500`, `SHOPPING_MAX_TAGS = 20`, `SHOPPING_BOUGHT_COUNT_MAX = 100_000`.

- List: `name` REQUIRED / TOO_LONG; more than 20 tags → `tagIds: OUT_OF_RANGE`.
- Item: `name` REQUIRED / TOO_LONG; `quantity`, `note` TOO_LONG; `tagIds` OUT_OF_RANGE; `boughtCount` outside
  0..100 000 → OUT_OF_RANGE; `status = bought` without `boughtBy` / `boughtAt` → that field REQUIRED.
- Normalizers: list name trimmed; item name `ShoppingNames.normalize`; quantity and note trimmed, blank → null.

Server-only checks (they need the database or the clock):

- List: `createdBy` ≠ viewer on create → `FORBIDDEN` (via `canCreateList`); a change of `createdBy` →
  `createdBy: INVALID_FORMAT`; tag ids **added** by the write must be non-deleted tags of the household → `tagIds:
  INVALID_FORMAT` (ADR-0018).
- Item: parent list missing or of another household → `ENTITY_NOT_FOUND`; tombstoned → `ENTITY_DELETED`; `listId` changed →
  `listId: INVALID_FORMAT`; added tag ids as for lists; `addedAt` / `boughtAt` changed by the write to later than server
  now + 1 day → `OUT_OF_RANGE`. As for task completions, a phone whose clock is more than a day ahead gets its adds and
  ticks rejected; accepted.

## Server

### Tables (`V7__shopping.sql`)

```sql
shopping_list (id uuid PK, household_id uuid NOT NULL → household, name text NOT NULL,
      created_by uuid NOT NULL → member, tag_ids text NOT NULL DEFAULT '[]',          -- JSON array, like task.tag_ids
      group_by_category boolean NOT NULL DEFAULT true, seq, created_at, updated_at, deleted)
shopping_item (id uuid PK, household_id, list_id uuid NOT NULL → shopping_list ON DELETE CASCADE, name text NOT NULL,
      status text NOT NULL, category text NOT NULL,                                  -- wire names
      quantity text NULL, note text NULL, tag_ids text NOT NULL DEFAULT '[]',
      added_by uuid NOT NULL → member, added_at timestamptz NOT NULL,
      bought_by uuid NULL → member, bought_at timestamptz NULL, bought_count int NOT NULL DEFAULT 0,
      seq, created_at, updated_at, deleted)
```

Indexes: `(household_id, seq)` on both; `shopping_item(list_id)`.

### Handlers (package `app.lares.server.shopping`)

`ShoppingListSyncHandler`, `ShoppingItemSyncHandler` extend `WritableSyncHandler`, registered in `AppDeps.syncHandlers`
after the task handlers.

- `canSee(viewer, row)` = `viewer.permissions.has(SHOPPING_VIEW)` for both types.
- `authorize`: the rules table above; the item handler loads the parent list (`requireParentList`, like
  `requireParentTask`) for upserts.
- **Cascade:** tombstoning a list tombstones its live items in the same transaction, each with its own `recordChange` seq,
  ordered by id (as the task cascade).
- `invalidField` and `requireAddedTags` move from `tasks/TaskChecks.kt` to `sync/ReferenceChecks.kt` (package
  `app.lares.server.sync`, internal) so both modules share them.
- Housekeeping: `SyncHousekeeping.purgeTombstones` purges `shopping_item`, then `shopping_list` tombstones.
- No notifier, no background job.

## Client

### Local tables (`3.sqm`, `DATA_SCHEMA_VERSION = 3`)

`shopping_list(id PK, name, created_by, tag_ids TEXT (JSON), group_by_category INTEGER)`;
`shopping_item(id PK, list_id, name, name_key, status TEXT, category TEXT, quantity NULL, note NULL, tag_ids TEXT (JSON),
added_by, added_at INTEGER (epoch ms), bought_by NULL, bought_at INTEGER NULL, bought_count INTEGER)` + indexes on
`list_id` and `name_key`. `name_key` = `ShoppingNames.key(name)`, written by the adapter; status and category are
stored as wire names. Items of a list deleted locally stay until the server's cascade tombstones arrive; queries ignore
items whose list is missing.

### Adapters

`ShoppingListAdapter`, `ShoppingItemAdapter` in `Adapters.kt`, registered in `defaultEntityAdapters`: normalizers and
validators from the contract; `authorize` with `ShoppingRules` (the item adapter loads the parent list from the local
table; missing → `LocalWriteException(ENTITY_NOT_FOUND)`); a changed `createdBy` or `listId` → `VALIDATION_FAILED`, like
the server. Times are written in whole milliseconds.

### Models and views (`data/shopping/`)

```kotlin
data class ShoppingInputs(val lists: List<Pair<String, ShoppingListData>>, val items: List<Pair<String, ShoppingItemData>>,
                          val members: List<MemberView>, val tags: List<Tag>, val viewer: Viewer?, val now: Instant)
data class ShoppingItemView(val id: String, val data: ShoppingItemData, val tags: List<Tag>,
                            val addedBy: MemberView?, val boughtBy: MemberView?, val mine: Boolean)  // mine: added by me
data class ShoppingListView(val id: String, val data: ShoppingListData, val needed: Int, val bought: Int,
                            val remaining: List<String>,      // first 3 names to buy, in display order
                            val shopper: MemberView?,         // SHP-012
                            val lastActivity: Instant?,       // max addedAt / boughtAt of its items
                            val tags: List<Tag>)
data class DepartmentSection(val category: ProductCategory?, val items: List<ShoppingItemView>)  // null = not grouped
data class ShoppingListDetail(val list: ShoppingListView, val sections: List<DepartmentSection>,
                              val bought: List<ShoppingItemView>, val suggestions: List<ShoppingItemView>,
                              val tagsInUse: List<Tag>, val canEdit: Boolean, val canManage: Boolean)
data class Suggestion(val name: String, val category: ProductCategory, val note: String?,
                      val itemId: String?)                    // a row of this list to bring back, or null = new row
data class ActiveList(val list: ShoppingListView, val othersToBuy: Int)
data class ShoppingTagView(val items: List<Pair<ShoppingItemView, String>>,  // items to buy with the tag + list name
                           val lists: List<ShoppingListView>)
```

Pure functions in `ShoppingViews` (no database; unit-tested):

| Function | Rule |
| --- | --- |
| `lists(inputs)` | all lists in creation order (id = UUIDv7) |
| `detail(inputs, listId, tagId?)` | `needed` and `bought` items, both filtered by the tag if set (the list's counts, progress and the "≥ 2 departments" rule follow the filter; `suggestions` and `tagsInUse` do not); grouped when `groupByCategory` and they span ≥ 2 categories (sections in enum order), else one section with `category = null`; inside a section `addedAt` asc, then id. `bought`: `boughtAt` desc. `suggestions`: the list's `cleared` items by `boughtCount` desc, `boughtAt` desc (null last), name; at most 8. `tagsInUse`: tags on the list's needed or bought items |
| `autocomplete(inputs, listId, query)` | query key shorter than 2 → empty. Candidates: this list's `bought`/`cleared` rows (`itemId` set), then other lists' rows of any status whose key this list does not have (`itemId` null, note null). A candidate matches if its key starts with the query key or one of its words does. Order: this list first, whole-key prefix before word prefix, `boughtCount` desc, name. Distinct by key, at most 5 |
| `active(inputs)` | lists with `needed > 0`; the one with the latest `lastActivity` (ties: larger id); `othersToBuy` = the rest |
| `shopper(items, me, now)` (member id; the list view resolves it to a `MemberView`, unknown → null) | among the list's `bought` items with `boughtBy ≠ me` and `boughtAt` within `[now − 15 min, now + 1 min]` (a phone with a clock ahead must not keep the line for hours), the member of the latest |
| `forTag(inputs, tagId)` | needed items with the tag in all lists (list order, then display order) and lists with the tag |

Unknown or deleted member and tag ids are dropped (archived tags kept), as in tasks.

`ShoppingRepository(localDb, members: MembersRepository, tags: TagsRepository, permissions: PermissionsRepository, clock,
ticks = minuteTicks(clock))` — the same inputs as `TasksRepository` minus the household — combines `shopping_list`,
`shopping_item`, members, tags, the viewer and the ticks
(so "who is shopping" expires without data changes): `observeInputs()`, `observeLists()`, `observeDetail(listId, tagId)`,
`observeActive()`, `observeForTag(tagId)`. View models compute autocomplete from `observeInputs()` and the typed text.

### Writer (`ShoppingWriter`, all through `LocalWriter`; ids `Ids.newId()`; "me" = the logged-in member)

| Function | Writes |
| --- | --- |
| `createList(name): String` | list with `createdBy = me` |
| `updateList(id, name?, groupByCategory?, tagIds?)` | the given fields only |
| `deleteList(id)` | delete (the server cascades items) |
| `add(listId, text, tagId?): AddResult` | `QuickAdd.parse`, entries with equal keys merged (last non-null quantity wins), then per entry `addOne` |
| `addSuggestion(listId, s: Suggestion, tagId?)` | `s.itemId` set → bring back that row; else `addOne(name, quantity = null)` with `s.category` |
| `setBought(itemId, true)` | `status = bought, boughtBy = me, boughtAt = now, boughtCount + 1` |
| `setBought(itemId, false)` | `status = needed, boughtCount = max(0, count − 1)` (boughtBy/At unchanged); both directions write nothing when the item is already in that state |
| `clearBought(listId, tagId?): List<String>` | every `bought` item (with the tag, when the page is filtered) → `cleared`; returns their ids for undo |
| `restore(ids, status)` | `status` only (undo of clear → `bought`, undo of remove → `needed`); items gone locally meanwhile are skipped |
| `remove(itemId): ShoppingItemStatus` | `status = cleared`; returns the previous status for undo |
| `forget(itemId)` | delete (tombstone) — "Больше не предлагать" |
| `updateItem(itemId, name, quantity, note, tagIds)` | changed fields only |
| `setCategory(itemId, category)` | the item, then — best effort — every other local item with the same `name_key` whose list exists locally and whose category differs (Q-019); a `LocalWriteException` on one of those extra rows is skipped, never fails the user's own change |

`addOne(listId, name, quantity, category?)`: look up the list's rows with `name_key = key(name)` (prefer `needed`, then latest
`addedAt`): `needed` → write `quantity` if a new non-null one differs, report the name in `AddResult.alreadyThere`;
`bought`/`cleared` → `status = needed, addedBy = me, addedAt = now, quantity` (null clears it); none → create with
`category ?: categoryFor(key, name)`. With `tagId` (the list page is filtered by that tag) new rows get `tagIds = {tagId}` and
kept or brought-back rows get it added, so the item stays visible in the filtered list. `categoryFor`: the household's rows with that key in lists that exist locally, latest `addedAt` first → its
category; none → `ShoppingCategories.guess(name)`. `data class AddResult(val added: Int, val alreadyThere: List<String>)`.

## UI (M2-04…M2-06)

- **Shopping tab** (`ui/shopping/`): lists as cards (name, «6 из 9», progress, «Осталось: …», who is shopping, tags),
  extended FAB «Список» for `shopping.manage_lists`, empty state. A tap opens the list page.
- **List page** (`ShoppingListDestination(id, tagId?)`; `tagId` opens it filtered, from the tag page): top bar with the list name and a menu (rename, list tags, grouping,
  clear bought, delete); offline banner; progress line; quick-add field with suggestion chips and autocomplete; items to
  buy by department; collapsed «Куплено · N»; a tap ticks, a long press opens the item sheet.
- People are shown by first name (first word of `MemberView.name`); times of adds and ticks with the existing
  `rememberTimeFormatter` (device zone: «18:40» today, «23 октября, 18:40» otherwise); item names as stored.
- **Item sheet:** name, quantity, note, department picker («Запомним для всех списков»), tags, who added / bought;
  «Сохранить», «Убрать из списка».
- **Today:** the active list as the gradient hero card above the tasks block (mockup `Main.dc.html`;
  `TodayViewModel` combines task inputs with `observeActive()`, `TodayUiState.shopping`, only with `shopping.view`);
  all text 100 % white for contrast; «Ещё N списка» under it opens the Shopping tab.
- **Tag page:** a «Покупки · N» section after the tasks (items to buy with the tag → the list filtered by the tag;
  tagged lists → the list), only when it has rows (`TagViewModel` adds `observeForTag`).
- Shopping checkbox: rounded square 26 dp, radius 8 (visual-style § Component patterns).
