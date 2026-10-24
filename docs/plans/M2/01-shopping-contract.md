---
id: M2-01
status: ready
depends_on: [M1-07]
requirements: [SHP-001, SHP-002, SHP-003, SHP-004, SHP-006, SHP-009, SHP-014, TAG-002]
---
# M2-01: Shopping contract — data, names, quick add, departments, write rules, validators

## Goal

All shared code of the shopping module in `:contract`: entity data classes, the name key, the quick-add parser, the
department dictionary with its guessing algorithm, permission rules and validators. Pure Kotlin, no I/O, fully
unit-tested; used unchanged by the server (M2-02) and the client (M2-03). No server or client code changes except what
is needed to compile.

## Read first

- [Shopping design](../../architecture/shopping.md) — **binding**; § Contract data, § Names and quick add,
  § Departments, § Write rules, § Validation
- [ADR-0021](../../adr/0021-shopping-items-kept-as-list-memory.md), [ADR-0018](../../adr/0018-tags-embedded-in-entities.md)
- [Shopping spec](../../product/features/shopping.md) § Requirements, § Permissions and privacy
- Existing contract code: `entities/TaskEntities.kt` (style, `@SerialName` enums), `entities/Entities.kt`
  (`ContactKind` + `ContactKindSerializer`: lenient enum **with a fallback**), `serialization/Lenient.kt`,
  `permissions/WriteRules.kt`, `permissions/TaskRules.kt`, `validation/Validators.kt` (task validators and normalizers),
  `sync/SyncDtos.kt` (`EntityTypes`); tests `commonTest/…/permissions/TaskRulesTest.kt`, `validation/TaskValidatorsTest.kt`

## Scope

- `entities/ShoppingEntities.kt`: `ShoppingListData`, `ShoppingItemData`, `ShoppingItemStatus`, `ProductCategory`
  exactly as in the design (field order as listed — new fields only ever appended).
- `sync/SyncDtos.kt`: `EntityTypes.SHOPPING_LIST`, `SHOPPING_ITEM`.
- Package `app.lares.contract.shopping`: `ShoppingNames.kt`, `QuickAdd.kt`, `ShoppingCategories.kt`,
  `CategoryDictionary.kt`.
- `permissions/ShoppingRules.kt`; `WriteRules.canMutate` gates for the two types.
- `validation/Validators.kt`: `shoppingList`, `shoppingItem`, `normalizeShoppingList`, `normalizeShoppingItem` + constants.

Out of scope: server tables and handlers (M2-02), client tables (M2-03), any UI, category labels (UI strings, M2-04).

## Decisions (do not revisit)

- Enum wire names lowercase snake via `@SerialName` (`needed`, `bought`, `cleared`; `produce`, `bakery`, `dairy`,
  `meat_fish`, `grocery`, `sweets`, `drinks`, `frozen`, `household`, `personal_care`, `baby`, `pets`, `pharmacy`,
  `other`). Both enums use the `ContactKind` pattern (`@KeepGeneratedSerializer` + `LenientEnumSerializer` with a
  fallback): `ShoppingItemStatus` → `CLEARED`, `ProductCategory` → `OTHER`. Give both a `key` property and
  `fromKey(key): E?` like `Permission` (the client stores wire names in its tables).
- `ProductCategory` entries in the design's store order; that order is the display order (do not sort elsewhere).
- API (exact names; all pure `object`s):
  ```kotlin
  object ShoppingNames {
      fun normalize(name: String): String          // trim + collapse whitespace
      fun key(name: String): String                // lowercase, ё→е, non letter/digit → space, collapse, trim
      fun displayName(name: String): String        // upper-case the first char if the first word has no upper case
  }
  data class QuickAddEntry(val name: String, val quantity: String?)
  object QuickAdd {
      const val MAX_ENTRIES = 50
      fun parse(text: String): List<QuickAddEntry>
  }
  object ShoppingCategories {
      fun guess(name: String): ProductCategory
  }
  ```
  Use `Char.isLetterOrDigit()` and `lowercase()`/`uppercase()` from the Kotlin stdlib (common code; no platform locale).
- Quick add: rules and the example table of design § Names and quick add, exactly. Implement with small regexes
  (Kotlin `Regex` in common code); the unit list is a `Set<String>` compared in lower case.
- `CategoryDictionary` (`internal object`): `stems: Map<ProductCategory, List<String>>`, `phrases: Map<…, List<String>>`
  (space-separated stems), `modifiers: Map<…, List<String>>`. At least 20 Russian and 15 English stems per category
  except `OTHER`; modifiers exactly as listed in the design. Write the stems by hand from common product names
  (groceries in a Russian supermarket, plus common English names); include inflection-safe stems (`огурц`, `яйц`,
  `сол`, `мук`) and the longer stems that resolve clashes (`печенье` vs `печень`, `лимонад` vs `лимон`). Do not add
  stems that would turn the `OTHER` examples (`тетради`, `подарок маме`, `birthday card`) into a category.
- `guess` implements the five steps of design § Departments literally (head/tail split at the preposition list,
  phrases, modifiers, adjective-looking words last, last matching word, longest stem in a word). Build lookup structures
  once (lazy) — no per-call allocation of the whole dictionary.
- `ShoppingRules` exactly as in the design; `foreignAuthorFields` returns the field names (`"addedBy"`, `"boughtBy"`):
  `addedBy` is foreign if `existing == null || existing.addedBy != merged.addedBy` and `merged.addedBy != viewer.memberId`;
  `boughtBy` is foreign if it changed, is non-null and is not the viewer.
- `WriteRules.canMutate`: `SHOPPING_LIST` → `permissions.has(SHOPPING_MANAGE_LISTS)`; `SHOPPING_ITEM` →
  `permissions.has(SHOPPING_EDIT)`; update its KDoc (entity-level checks are `ShoppingRules`).
- Validators return field maps like `Validators.task`; constants as in the design (`SHOPPING_MAX_TAGS = 20` separate from
  the task constant). `status = BOUGHT` with `boughtBy == null` → `boughtBy: REQUIRED`, with `boughtAt == null` →
  `boughtAt: REQUIRED` (both may be reported).

## Steps

1. Data classes, enums, `EntityTypes`.
2. `ShoppingNames`, `QuickAdd`.
3. `CategoryDictionary` and `ShoppingCategories.guess`.
4. `ShoppingRules`, `WriteRules` gates; validators and normalizers.
5. Tests (`contract/src/commonTest`, names start with requirement ids):
   - `ShoppingEntitiesTest`: round trips of both data classes (ISO instants, tag sets, missing
     optional fields decode to defaults, nulls omitted — `LaresJson` encodes defaults), unknown status → `CLEARED`, unknown category → `OTHER`, `fromKey`.
   - `ShoppingNamesTest`: `key` (case, `ё`, punctuation, `%`, digits, repeated spaces), `normalize`, `displayName`.
   - `QuickAddTest` (SHP-003): every row of the design table; units in upper case; `шт.` with a dot; 60 entries → 50;
     a 250-character name → 200; quantity cut to 30; comma rules (`2,5` kept, `молоко,хлеб` split); entries with an empty key (`"—"`) dropped.
   - `ShoppingCategoriesTest` (SHP-006): every name of both design tables → its category (one parameterized loop with a
     readable failure message listing all misses at once); dictionary invariants: every entry equals its own
     `ShoppingNames.key` and has no spaces (stems) or only spaces between stems (phrases), no stem in two categories, no
     stem shorter than 3 characters, at least 20 Cyrillic and 15 Latin stems per category except `OTHER`.
   - `ShoppingRulesTest`: Adults / Kids / custom "view only" / admin × create list (own and foreign `createdBy`), manage
     list, edit items; `foreignAuthorFields` for create (own, foreign), tick by me, a change that keeps another
     member's `addedBy`/`boughtBy` (not foreign), re-add by me.
   - `WriteRulesTest`: the two gates.
   - `ShoppingValidatorsTest`: every field error listed in the design; normalizers.

## Acceptance

- `tools/dev/verify.sh :contract:check` passes; then the full gate `tools/dev/verify.sh` (server and client still compile).
  If an existing test uses `shopping_list` as an example of an unknown type, change it to `"widget"`.

## Docs to update

- Shopping design only if a detail had to change (explain in the handoff).
- STATUS.md; plan status.

## Stop and report if

- A row of the design's department table cannot be classified without breaking another row under the algorithm as
  written (report the pair; do not change the algorithm on your own).

## Handoff

Russian summary: the shared rules of shopping lists are ready — how a typed line becomes items with quantities, how the
department is chosen (with the example table that passes), who may do what. Next plan M2-02.
