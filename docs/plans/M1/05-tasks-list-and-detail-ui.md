---
id: M1-05
status: ready
depends_on: [M1-04]
requirements: [TSK-003, TSK-004, TSK-005, TSK-007, TSK-015, TSK-020, TSK-021, TSK-024, TAG-003, FND-065]
---
# M1-05: Tasks UI — the Tasks tab and the task page

## Goal

The "Задачи" tab replaces its placeholder: filter chips, sections by date, task rows as in the mockup, ticking tasks off with
undo. The task page shows everything about a task: checklist (tick, add, rename, delete), routine history, completion state,
skip / mark as not done / delete (with "this occurrence or the whole series"). The editor comes in M1-06; until then the FAB
and "Edit" navigate to a route that M1-06 implements (stub screen with `placeholder_coming`).

## Read first

- [Tasks spec](../../product/features/tasks.md) — whole (short)
- [Visual style](../../design/visual-style.md) § Principles, § People, § Component patterns (task checkbox, filter chips, FAB,
  list group); mockup `docs/design/mockups/Tasks.dc.html` (header, chips, sections, row layout, meta line, FAB) and the
  "Задачи на сегодня" card of `Main.dc.html` (done row: "Аня · в 9:12")
- [Conventions § Client UI](../../process/conventions.md#client-ui)
- Code: `ui/main/MainScaffold.kt` (`MainTab`), `ui/main/Sections.kt` (placeholder to replace), `ui/Navigation.kt`,
  `ui/family/` (feature package pattern: Logic / ViewModel / Screen / Route), `ui/components/*`, `ui/common/DateTime.kt`,
  `ui/UiModule.kt`; tests `jvmTest/…/ui/FamilyFixture.kt`, `UiTestCore.kt`, `FamilyUiTest.kt`
- Client core from M1-04: `data/tasks/*`

## Scope

- Package `ui/tasks/`: `TasksLogic.kt` (pure UI formatting helpers), `TasksViewModel.kt`, `TasksScreen.kt`,
  `TaskDetailViewModel.kt`, `TaskDetailScreen.kt`, shared composables `TaskRow`, `TaskCheckbox`, `TagChip`, `AssigneeAvatars`
  in `ui/tasks/TaskComponents.kt` (M1-07 reuses them on Today and the tag page).
- Routes: `TaskDetail(id)`, `TaskEditor(id: String?)` (stub until M1-06) in `Navigation.kt`; the Tasks tab is still a
  `MainTab` content, not a route.
- Icons (add to `tools/design-assets/icons.txt` and visual-style § Icons, regenerate): `checklist`, `edit`, `skip_next`,
  `history`, `alarm`, `undo`.
- Strings en/ru for everything below (plurals for counts).

Out of scope: editor (M1-06); Today block, tag page, notification settings and taps (M1-07).

## Decisions (do not revisit)

- **Header:** `ScreenHeader("Задачи")` with actions: `search` (toggles a search field under the header; filters by title) and
  `tune` (bottom sheet "Filters": assignee — "Anyone" + members with `tasks.view`, as avatars with names; state — Open / Done).
- **Chips** (`AppFilterChip`, one row, horizontally scrollable): "All · N", "Mine · N", "Assigned by me", "Tag" (opens a tag
  picker sheet; when a tag is chosen the chip shows the tag name with a close icon). RU: «Все · N», «Мои · N», «Поручено мной»,
  «Метка». Default scope **Mine**; the selection is kept in `SavedStateHandle`-free ViewModel state (survives tab switches
  because the ViewModel is scoped to the main graph entry).
- **Sections:** `SectionHeader` + `ListGroup`: «Просрочено · N» (header in `error` color), «Сегодня · N», «Скоро» (upcoming),
  «Без срока»; with state "Done": a single «Выполнено» list. Empty states: Mine — "Nothing for you. Enjoy the day"; filters — "No
  tasks match the filters" with a "Reset filters" link.
- **Row** (`TaskRow`): 24 dp `TaskCheckbox` (overdue: `error` border; done: filled `primary` with check), title (`titleSmall`
  600; done: struck through, `onSurfaceVariant`), meta line (`bodySmall`): overdue → relative date + time in `error`
  («Вчера, 21:00», «Просрочено с 21 окт.»); routine → `repeat` icon + rule summary («Каждый день · до 20:00», «По пн и чт»,
  «Раз в 3 месяца после выполнения»); dated → «Сегодня, 17:30», «Завтра», «Суббота, 31 октября»; otherwise the first line of
  notes; then tag chips (`TagChip`: 12 sp 700, radius 8, palette `container`/`onContainer`, max 2 + "+N"); checklist progress
  «2/5» with `checklist` icon when the task has items. Done-today rows: meta = «<name> · в 9:12». Trailing `AssigneeAvatars`
  (28 dp, up to 2 overlapping + "+N"; "anyone" → no avatar). Tap row → task page.
- **Ticking off** from a row: allowed if `canComplete`; otherwise the checkbox is shown disabled (alpha 0.38) and a tap shows a
  snackbar "Only assignees can mark this task" / «Отметить могут только исполнители». After `complete` a snackbar «Выполнено» /
  "Done" with action «Отменить» / "Undo" (calls `undo(completionId)`), 5 s. Unticking a done-today row = undo of that
  completion (if allowed).
- **FAB:** `ExtendedGradientFab(add, «Задача»)` if the member has `tasks.create`; opens `TaskEditor(null)`.
- **Task page** (`AppPage` + `DetailTopBar(title = "", actions: edit (if canEdit), more_vert menu: Skip (routines, canComplete),
  Mark as not done (done one-off, allowed), Delete (canEdit)))`:
  1. Title (`headlineSmall`) and a status line: overdue / due text / «Выполнено: Аня, 23 окт., 9:12».
  2. Primary button: «Выполнить» / "Mark done" (canComplete, open) or tonal «Не выполнено» (done one-off).
  3. `ListGroup` of facts as `ListRow`s: date and time (`calendar_month`), repeat (`repeat`, rule summary + «Следующий раз: …»
     for routines), assignees (`group`, names or «Кто угодно»), tags (`sell`, chips), reminder (`alarm`, «За 15 минут»), notes
     (`notes` icon → use `subject` if `notes` is not in Material Symbols; add whichever exists), created by («Создала Ольга» →
     neutral: «Автор: Ольга» / "Created by Olga").
  4. Checklist section «Чек-лист · 2/5»: rows with the shopping-unlike round task checkbox (`TaskCheckbox` small 20 dp), text;
     long-press → menu Rename / Delete (canEdit); last row «+ Добавить пункт» (canEdit) opens an inline text field (IME Done adds
     and keeps the field open for the next item).
  5. History (routines only) «История»: last 20 completions — «Аня · вт, 21 окт., 19:40» or «Пропущено · Борис · …»; each own
     (or canEdit) entry has an undo icon button for the latest entry only.
- **Delete:** one-off → `ConfirmDialog(destructive)` «Удалить задачу?»; routine → `ChoiceDialog` «Удалить регулярную задачу»:
  «Только это повторение» (= `skip`) / «Всю серию» (= `delete`, then close the page) / «Отмена».
- **Errors:** writer exceptions → snackbar via `toUiError()`; sync rejections already surface through the main scaffold.
- **Formatting** lives in `TasksLogic.kt` (pure, unit-tested): relative day names (today/tomorrow/yesterday/weekday within 6 days/
  date), rule summaries for every rule shape (en/ru through string resources with arguments; weekday short names from
  `Res.array.weekdays`), reminder labels. Do not format with platform locale APIs in common code.

## Steps

1. Icons; strings; `TasksLogic` + tests.
2. Components (`TaskComponents.kt`).
3. `TasksViewModel` (state: filter, sections, counts, members for the filter sheet, tags for the tag picker, canCreate,
   snackbar events) and `TasksScreen`; replace the Tasks placeholder in `Sections.kt`.
4. `TaskDetailViewModel` (params via `TaskDetailArgs(id)`) and screen; stub `TaskEditor` route.
5. Tests:
   - jvmTest `TasksLogicTest`: rule summaries (all units/modes, weekdays sets, intervals), relative dates around midnight,
     overdue texts, reminder labels — in en and ru.
   - jvmTest `TasksViewModelTest` (`UiTestCore` with task changes added to the fixture pull): default scope Mine; chip counts;
     tag filter; complete + undo; Kid cannot complete an Adult-only task (snackbar event, nothing enqueued).
   - jvmTest `TaskDetailViewModelTest`: checklist add/tick/rename/delete; skip; delete series; history order.
   - Compose UI tests (`TasksUiTest`): sections render with headers and counts; overdue row shows the error-colored meta
     text; done row is struck through (assert text present and checkbox state via test tags); delete routine shows the choice
     dialog.
   - Update `ScreensUiTest` only if it asserts the Tasks placeholder.
6. Emulator check via the `emulator-tester` agent: `server.sh reset`, `seed.py family`, `seed.py tasks`, log in as Ольга (admin) and
   as Аня (Kids, `seed.py login-code Аня`) — scenarios: chips and sections match the mockup; tick a task and undo; open the routine page, tick checklist
   items, skip an occurrence; delete series; Аня cannot tick a task assigned to Дмитрий; ru and en; light and dark. Screenshots: tasks list
   (ru light), routine page (ru dark), delete-series dialog.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest` while iterating; full gate at the end.
- Emulator walkthrough done (or reported as skipped with the reason), screenshot paths in the handoff.

## Docs to update

- Tasks spec: TSK-003, TSK-004, TSK-005, TSK-007, TSK-015, TSK-020, TSK-021, TSK-024 → `done` if fully covered (server + client +
  UI); TAG-003 note "Tasks done".
- visual-style.md § Icons (new icons); glossary RU labels if any new term appeared.
- STATUS.md; plan status.

## Stop and report if

- A mockup layout conflicts with visual-style.md in a way the style guide does not settle.

## Handoff

Russian summary with screenshots: the Tasks tab — filters, sections, ticking off with undo, the task page with checklist and
history, deleting one occurrence or the whole series. Creating and editing tasks comes in the next plan (M1-06).
