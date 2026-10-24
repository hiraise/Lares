---
id: M2-05
status: ready
depends_on: [M2-04]
requirements: [SHP-002, SHP-006, SHP-008, SHP-009, SHP-010, SHP-014, TAG-002, TAG-003]
---
# M2-05: Item sheet, suggestions, autocomplete and tags in Shopping

## Goal

Everything about a single item and about adding things fast: the item sheet (name, quantity, note, department with the
family's memory, tags, who added and bought, «Убрать из списка» with undo), suggestion chips under the empty input,
autocomplete while typing, «Больше не предлагать», list tags and the tag filter on the list page.

## Read first

- [Shopping spec](../../product/features/shopping.md) § Requirements (SHP-002, SHP-006, SHP-008…SHP-010, SHP-014)
- [Shopping design](../../architecture/shopping.md) § Models and views (`detail`, `autocomplete`), § Writer
- [Visual style](../../design/visual-style.md) § Component patterns (filter chips, buttons); mockup
  `docs/design/mockups/Shopping.dc.html` (suggestion row with the `history` icon and «+ Яйца» chips)
- Code: M2-04's `ui/shopping/*`; `ui/tasks/TagsSheet.kt` and the `EditorSheet` it uses, `ui/tasks/TaskEditorScreen.kt`
  (fact rows that open sheets), `ui/family/TagSheet.kt` (`TagEditor`: new tag from a picker), `ui/components/Pickers.kt`
  (`LabelChip`, `CheckRow`), `ui/components/Dialogs.kt` (`ChoiceDialog`, `ConfirmDialog`), `ui/common/DateTime.kt`

## Scope

- `ui/shopping/ItemSheet.kt` (`ShoppingItemSheet`) and its state in `ShoppingListViewModel`.
- Suggestion chips and the autocomplete dropdown in `QuickAddField` / the list page.
- List tags (menu item) and the tag filter chips on the list page.
- If `TagsSheet` / `EditorSheet` need a title parameter or a move to `ui/components` to be shared, do it without
  changing the tasks UI behaviour.
- Strings en/ru.

Out of scope: Today card, tag page, user manual (M2-06).

## Decisions (do not revisit)

- **Item sheet** opens on a long press of a row to buy or a bought row (`canEdit`; without it nothing happens). Title
  «Товар». Fields: «Название» (`FormField`, required), «Количество» (`FormField`, hint «Например, 2 шт или 1,5 кг»),
  «Заметка» (`FormField`, up to 3 lines). Fact rows (`ListRow` with chevron): «Отдел» (`category` icon, current label)
  → `ChoiceDialog` with the 14 departments in store order; under it the hint «Запомним для всех списков»; «Метки»
  (`sell`) → `TagsSheet` (new tag only with `tags.manage`, through the shared `TagEditor`). Info lines
  (`bodySmall` `onSurfaceVariant`; first names and `rememberTimeFormatter` as in M2-04): «Добавлено: Ольга · 10:15»;
  for items ever bought «Куплено: Дмитрий · 23 октября, 18:40» and «Покупали 12 раз» (plural; only when `boughtCount` > 1).
  Buttons: `PrimaryButton` «Сохранить» (enabled when something changed and the name is not blank) →
  `updateItem` with the changed fields, then `setCategory` if the department changed; `LinkButton` «Убрать из списка»
  → `remove(id)`, close, snackbar «Убрано из списка» with «Отменить» → `restore(listOf(id), previous status)`.
  Unsaved changes: dismissing the sheet with changes asks «Не сохранять изменения?» (`ConfirmDialog`).
- **Suggestion chips** (SHP-008): when the input is empty and `detail.suggestions` is not empty, a horizontally scrolling
  row under the input: `history` icon (20 dp, `onSurfaceVariant`), then chips «+ Яйца» (pill 36 dp, `outlineVariant`
  border, `bodyMedium` 600). Tap → `addSuggestion(listId, Suggestion(name, category, note, itemId = id), tagId)`. Long
  press → `ConfirmDialog` «Больше не предлагать «Яйца»?» / «Не предлагать» → `forget(id)`.
- **Autocomplete** (SHP-008): while the typed text has a key of 2+ characters, up to 5 rows in a `ListGroup` right under
  the field (chips hidden): name (`bodyLarge`) and department label (`bodySmall`). Tap → `addSuggestion(…)`, clear the
  input. IME Done still adds the typed text as typed. Computed in the view model from `observeInputs()` and the text
  (`ShoppingViews.autocomplete`), debounced 150 ms.
- **List tags** (SHP-014, TAG-002): menu item «Метки списка» (`canManage`) → `TagsSheet` → `updateList(tagIds = …)`.
  Cards on the Shopping tab show list tags with `TagChips` (max 2 + "+N").
- **Tag filter** (TAG-003): when `detail.tagsInUse` is not empty, a chip row under the progress block: «Все» and one chip
  per tag (selected = `inverseSurface` fill). A chosen tag filters items to buy and bought items
  (`observeDetail(listId, tagId)`; counts and progress follow it, suggestions do not), adding while filtered passes
  `tagId` so new items carry the tag, and «Убрать купленные» clears only the visible (tagged) bought items. The choice
  lives in the view model; it resets when the tag disappears from `tagsInUse`.
- Item rows show item tags as `TagChips` (max 2) at the end of the meta line.

## Steps

1. Item sheet and its view-model state; department dialog; tags.
2. Suggestion chips, forget dialog; autocomplete.
3. List tags; tag filter.
4. Tests:
   - jvmTest `ShoppingListViewModelTest` (extend): sheet save sends only changed fields; department change calls
     `setCategory` (the same name in another list changes too); remove + undo restores the previous status;
     suggestion tap brings a cleared item back; forget removes the chip; autocomplete for «мо» returns «Молоко» from
     another list and adding it creates a row in this list with that department; tag filter narrows the items and
     adding while filtered tags the new item; unsaved-changes prompt state.
   - Compose UI tests (extend `ShoppingUiTest`): chips row shows with an empty input and hides while typing; the
     autocomplete list shows at most 5 rows; the item sheet shows the info lines; the tag chip row appears only when a
     tag is in use.
5. Emulator check via the `emulator-tester` agent (`server.sh reset`, `seed.py family`, `seed.py shopping`): as Ольга —
   tap «+ Яйца» (returns to the list), type «тв» and pick «Творог», long press «Молоко 2,5%» → change quantity and note,
   move «Шоколад» to «Другое» and add «Шоколад» in «Аптека» (lands in «Другое»), «Убрать из списка» and undo, long press
   a chip → «Не предлагать»; tag «Дача» in «Дача» list → filter, add «Лопата» while filtered (tagged); as Аня the sheet
   opens and saves (Kids may edit items) but «Метки списка» is not in the menu; ru and en; light and dark.
   Screenshots: suggestions row, autocomplete, item sheet (ru light), tag filter (ru dark).

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest` while iterating; full gate at the end.
- Emulator walkthrough done (or skipped with the reason), screenshot paths in the handoff.

## Docs to update

- Shopping spec: SHP-002, SHP-006, SHP-008, SHP-009, SHP-010 → `done` if fully covered; SHP-014 note "list page part done".
- Tags spec: TAG-002, TAG-003 notes "Shopping done".
- CHANGELOG `[Unreleased]`: suggestions, departments you can correct, tags in lists.
- STATUS.md; plan status.

## Stop and report if

- The autocomplete dropdown cannot be placed under the field without covering the keyboard on a 390 × 844 screen;
  propose a layout instead of improvising one.

## Handoff

Russian summary with screenshots: suggestions under the input and while typing, editing an item, departments that the
family corrects once, «Убрать из списка» with undo, tags and the tag filter. Next M2-06 (Today, tag page, manual).
