---
id: M2-04
status: done
depends_on: [M2-03]
requirements: [SHP-001, SHP-002, SHP-003, SHP-004, SHP-005, SHP-006, SHP-009, SHP-011, SHP-012, FND-043, FND-065]
---
# M2-04: Shopping UI — the Shopping tab and the list page

## Goal

The «Покупки» tab replaces its placeholder: the family's lists as cards with progress, creating a list. The list page is
what people use in the store: offline banner, progress with "who is shopping", the quick-add field, items to buy grouped by
department, ticking with a tap, the collapsed «Куплено · N» section, «Убрать купленные» with undo, and the list menu
(rename, grouping, clear, delete). The item sheet, suggestions, autocomplete and tags come in M2-05; Today and the tag
page in M2-06.

## Read first

- [Shopping spec](../../product/features/shopping.md) — whole (short)
- [Shopping design](../../architecture/shopping.md) § UI, § Models and views, § Writer
- [Visual style](../../design/visual-style.md) § Principles, § Component patterns (shopping checkbox, list group, progress
  bar, FAB, sync state banner), § Icons; mockup `docs/design/mockups/Shopping.dc.html` (header, banner, progress block,
  input, department sections, row layout, bought card) — the bottom navigation in that mockup is not shown on detail
  pages in this app (visual-style wins)
- [Conventions § Client UI](../../process/conventions.md#client-ui)
- Code — the tasks UI is the template: `ui/tasks/TasksScreen.kt`, `TasksViewModel.kt`, `TaskDetailScreen.kt`,
  `TaskDetailViewModel.kt` (args, menu, dialogs, snackbar events), `TaskComponents.kt` (`TaskCheckbox`, `TagChips`),
  `ui/main/MainScaffold.kt` (`MainTab`, `MainNavigation`, the `SHOPPING → SectionPlaceholder` branch to replace),
  `ui/Navigation.kt`, `ui/components/*` (`ListGroup`, `ListRow`, `SectionHeader`, `ExtendedGradientFab`, `ConfirmDialog`,
  `FormField`, `NoticeBanner`, `EmptyState`, `DetailTopBar`, `AppPage`), `ui/common/DateTime.kt`, `ui/UiModule.kt`;
  tests `jvmTest/…/ui/TaskUiFixture.kt`, `UiTestCore.kt`, `TasksViewModelTest.kt`, `TasksUiTest.kt`
- Client core from M2-03: `data/shopping/*`

## Scope

- Package `ui/shopping/`: `ShoppingLogic.kt` (pure formatting), `ShoppingComponents.kt` (`ShoppingCheckbox`,
  `ShoppingItemRow`, `ShoppingListCard`, `BoughtSection`, `QuickAddField`, `ShoppingProgress`),
  `ShoppingListsViewModel.kt` + `ShoppingListsScreen.kt` (`ShoppingRoute` for the tab), `ShoppingListViewModel.kt` +
  `ShoppingListScreen.kt`; Koin view models in `uiModule()`.
- Route `ShoppingListDestination(id: String)` in `Navigation.kt`; `MainNavigation.onShoppingList: (String) -> Unit`;
  `MainScaffold`: `MainTab.SHOPPING` → `ShoppingRoute`, Calendar keeps the placeholder.
- Icons (add to `tools/design-assets/icons.txt` and visual-style § Icons, regenerate): `category`,
  `remove_shopping_cart`, `add_shopping_cart`, `remove_done`.
- Strings en/ru for everything below (plurals for counts) and the 14 department labels `shopping_category_<wire name>`
  (texts from the design table).

Out of scope: item sheet, suggestions, autocomplete, tags (M2-05); Today card, tag page, manual (M2-06).

## Decisions (do not revisit)

- **Tab** (`ShoppingListsScreen`): `ScreenHeader(«Покупки»)` without actions. Lists from `observeLists()` as
  `ShoppingListCard`s (surface card, radius 20, padding 16, 12 dp apart): name (`titleMedium` 700) with «6 из 9» on the
  right (`bodySmall`, `onSurfaceVariant`; hidden when the list has no items to buy and none bought); a 6 dp progress bar
  (`primary` on `divider`, bought / (bought + to buy)) when that total > 0; a line «Осталось: Молоко, Хлеб, Яблоки и
  ещё 2» (names as stored, from `remaining` + count), «Всё куплено» (`success`) or «Пусто»; the
  shopper line (`shopping_cart` 16 dp + «Дмитрий сейчас в магазине», `bodySmall` 600 `primary`) when `shopper` is set.
  A tap opens the list page. Extended FAB `ExtendedGradientFab(add, «Список»)` for `canManageLists` → dialog «Новый
  список» (`FormField` «Название», hint «Например, Продукты»; «Создать» disabled while blank) → `createList` → open the
  new list. Empty state: `shopping_cart`, «Списков пока нет»; with the right to create, the text «Создайте первый
  список — например, «Продукты».» and a `PrimaryButton` «Создать список» (same dialog).
- **List page** (`AppPage` + `DetailTopBar(title = list name, onBack)` with a `more_vert` menu):
  «Переименовать» (`canManage`, dialog like «Новый список» prefilled, «Сохранить»), «Группировать по отделам»
  (`canManage`, a check icon when on; toggles `groupByCategory`), «Убрать купленные» (`canEdit`, only when bought > 0),
  «Удалить список» (`canManage`, `ConfirmDialog(destructive)` «Удалить список «Продукты»?» / «Товары и подсказки этого
  списка удалятся у всех.» → `deleteList`, close the page). A list deleted elsewhere while open → close the page (like
  `TaskDetailScreen` with `state.gone`).
  Content top to bottom, 16 dp side padding:
  1. Offline banner (`NoticeBanner`) when sync state is `OFFLINE`: pending > 0 → «Нет сети. Отметки сохранены и
     отправятся, когда связь появится.»; else «Нет сети. Список может быть не самым свежим.» (SHP-011).
  2. `ShoppingProgress` when the list has any item to buy or bought: «Куплено 6 из 9» (`labelLarge` 700) left, the
     shopper in the short form «Дмитрий в магазине» (`bodySmall` 600 `primary`; deliberate: the row is narrow) right, a 6 dp progress bar under it.
  3. `QuickAddField` (`canEdit` only): rounded field (radius 16, `surfaceContainerHigh`, 52 dp), placeholder «Добавить
     товар», trailing 40 dp round `primary` button with `add`. IME action Done or the button → `add(listId, text)`;
     the field clears and keeps focus. If the value ever contains a line break (paste), add it at once and clear.
     `AddResult.alreadyThere` non-empty → snackbar «Уже в списке: Молоко, Хлеб».
  4. Items to buy: one `ListGroup` per `DepartmentSection`; header `SectionHeader(department label)` only when
     `category != null`. Rows: `ShoppingItemRow` — `ShoppingCheckbox` (26 dp, radius 8, 1.5 dp `onSurfaceVariant`
     border; checked: filled `primary` with a white `check` 18 dp), name (`bodyLarge`), meta line (`bodySmall`
     `onSurfaceVariant`) = note, then for items not added by me a `person` 14 dp icon + the adder's first name; trailing
     quantity (`bodyMedium` 600 `onSurfaceVariant`). Dividers inset to the text start. Min height 56 dp.
     Empty list (nothing to buy, nothing bought): `EmptyState(add_shopping_cart, «Список пуст. Добавьте, что нужно
     купить.»)`; all bought: a row «Всё куплено» with `check` in `success`.
  5. `BoughtSection` when bought > 0: a card with a 32 dp `successContainer` circle and `check` (`success`),
     «Куплено · 6» (`titleSmall` 700), preview «Творог, Яйца, Сыр и ещё 3» (names as stored) (`bodySmall`), `expand_more`/`expand_less`.
     Collapsed by default (state in the view model per list, not persisted). Expanded: bought rows — checked checkbox,
     name struck through in `onSurfaceVariant`, meta «Дмитрий · 18:40» — the time from `rememberTimeFormatter` («18:40» today, «23 октября, 18:40» otherwise);
     then `LinkButton` «Убрать купленные» (`canEdit`).
- **Ticking:** a tap anywhere on a row (or its checkbox) calls `setBought(id, !bought)`; no snackbar (untick in
  «Куплено»). Without `canEdit` rows are shown with the checkbox at alpha 0.38 and a tap shows «Нет права изменять
  список». Long press: reserved for the item sheet (M2-05) — wire `onLongClick` in `ShoppingItemRow`, no-op here.
- **Clear bought:** `clearBought(listId)` (M2-05 passes the active tag filter) → snackbar «Убрано: 6» with «Отменить» (5 s) → `restore(ids, BOUGHT)`.
- **Errors:** writer exceptions → snackbar via `toUiError()`; sync rejections surface through the main scaffold.
- **People and times:** members by first name — `firstName(member)` = first word of `MemberView.name` (in
  `ShoppingLogic.kt`); times with `rememberTimeFormatter`.
- **Formatting** in `ShoppingLogic.kt` (pure, unit-tested): progress texts, remaining and bought previews, banner
  choice, department label mapping, `firstName`. Plurals through string resources; no platform locale APIs.

## Steps

1. Icons; strings; `ShoppingLogic` + tests.
2. Components.
3. Tab view model and screen; route and navigation wiring; replace the placeholder.
4. List page view model (`ShoppingListArgs(id)`; state: detail, sync banner, bought expanded, dialogs, snackbar events)
   and screen.
5. Tests:
   - jvmTest `ShoppingLogicTest`: every text above in en and ru, plural forms (1, 2, 5, 21), `firstName`.
   - jvmTest `ShoppingListsViewModelTest` (fixture `ShoppingUiFixture` like `TaskUiFixture`, with shopping rows in the
     pull): cards in creation order with counts, remaining and shopper; Kid has no create action; create list → id
     returned and opened.
   - jvmTest `ShoppingListViewModelTest`: add text → items in their departments; «Уже в списке» event; tick and untick;
     clear + undo; rename; grouping toggle; delete closes; banner state for offline with and without pending changes;
     view-only member cannot tick (event, nothing enqueued).
   - Compose UI tests `ShoppingUiTest`: department headers render only with ≥ 2 departments; bought section collapsed
     shows count and preview, expanded shows struck-through rows; offline banner text; no FAB and no input for a member
     without the rights.
   - Update `ScreensUiTest` only if it asserts the Shopping placeholder.
6. Emulator check via the `emulator-tester` agent: `server.sh reset`, `seed.py family`, `seed.py shopping`, log in as
   Ольга (admin) and as Аня (Kids, `seed.py login-code Аня`). Scenarios:
   - cards match the mockup style; open «Продукты»: departments and the bought section;
   - type «Майонез 1 шт, хлеб»: Майонез lands in «Бакалея», Хлеб is already there → snackbar;
   - tick three items, expand «Куплено», untick one; «Убрать купленные» and undo;
   - "no network": `adb reverse --remove tcp:8080` (the app reaches the server only through this tunnel; Wi-Fi
     switches do not cut it) → banner, tick offline; `adb reverse tcp:8080 tcp:8080` → synced, banner gone;
   - with the list open run `seed.py tick Курица --as dima` → the tick and «Дмитрий в магазине» appear within seconds
     (SHP-005, SHP-012);
   - rename and delete a list as Ольга; Аня has no FAB and no list menu items except «Убрать купленные»;
   - ru and en; light and dark.

   Screenshots: tab (ru light), list page in a store with the banner (ru dark), bought section expanded.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest` while iterating; full gate at the end.
- Emulator walkthrough done (or reported as skipped with the reason), screenshot paths in the handoff.

## Docs to update

- Shopping spec: SHP-001, SHP-003, SHP-004, SHP-005, SHP-009 (adding), SHP-011, SHP-012 (list page and cards) → `done`
  when server, client core and UI cover them fully; progress notes otherwise. SHP-006: note "UI: sections (M2-04);
  correction in M2-05".
- FND-043 note: the in-store offline banner is done.
- visual-style.md § Icons (new icons).
- CHANGELOG `[Unreleased]`: shopping lists (one line, user language).
- STATUS.md; plan status.

## Stop and report if

- A mockup layout conflicts with visual-style.md in a way the style guide does not settle.
- Ticking feels laggy on the emulator because each tick waits for a database round trip (more than one frame of
  delay is not expected from `LocalWriter`); report measurements instead of adding optimistic UI state.

## Handoff

Russian summary with screenshots: the Shopping tab and the list page — adding several items in one line, departments,
ticking in the store without network, «Куплено», clearing with undo, rename and delete. Item editing, suggestions and
tags come next (M2-05).
