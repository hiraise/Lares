---
id: M5-05
status: ready
depends_on: [M5-04]
requirements: [BUD-001, BUD-002, BUD-003, BUD-004]
---
# M5-05: Budget UI — the plan editor, the table, versions and history

## Goal

Members with `budget.manage` build and change the plan: categories with monthly amounts and per-income amounts,
incomes with day and amount, live checks of rows and columns, a read-only table like the notebook, saving «с какого
месяца», and the plan history. Members with only `budget.view` see the plan and its history read-only.

## Read first

- [Budget design](../../architecture/budget.md) § UI (Plan screen), § Client (`plan`, `history`, writer `savePlan`,
  `isCategoryUsed`)
- [Budget spec](../../product/features/budget.md) § Scenarios 1, 5; BUD-001…BUD-004
- [Visual style](../../design/visual-style.md) (segmented control, list group, sheets, warning color)
- Code templates: `ui/calendar/EventEditor*` (local draft, discard dialog), `ui/family/TagSheet.kt` (sheet with fields),
  drag-to-reorder if any list has it (else plain up/down actions in the row menu — see Decisions)

## Scope

- `ui/budget/plan/`: `PlanViewModel` (holds the draft `BudgetTable`, `TableCheck` recomputed on every change, mode
  plan/month — month mode is wired in M5-06), `PlanScreen` with «Категории | Поступления | Таблица», `CategorySheet`,
  `IncomeSheet`, `PlanTable` (grid), the save dialog, `PlanHistoryPage`, read-only version view.
- «План» action on the Budget screen and «Создать план» in the empty state route here.
- Strings en and ru.

Out of scope: month-only edits, extra incomes, Today card (M5-06).

## Decisions (do not revisit)

- The draft starts from `plan(inputs, nextMonth)` (the version that would apply next month, else the one in force),
  so a change already saved "from next month" is what you edit again.
- New ids for categories and incomes with `Ids.newId()`; a new category goes to the end; a new income gets day 1 and
  amount 0 and opens its sheet.
- Category sheet: per-income fields list incomes by day; an empty field = no cell; «Распределено X из Y» in the warning
  color when they differ.
- Reorder: if the codebase has no drag-to-reorder list yet, use «Выше» / «Ниже» in each category row's menu
  (no new dependency).
- «Удалить» category or income follows `isCategoryUsed` / `isIncomeUsed` with `before` = the save month; otherwise
  the action reads «Не использовать» (category) or is absent (income; change day/amount instead).
- Save dialog: «С <next month>» (default) and «С <current month> (текущий месяц)»; the warning about month changes
  only when the current month has a month row with a table. The very first plan offers the current month as default.
- Saving with bad rows/columns is allowed (spec BUD-003); the summary bar shows «не сходится на N».
- Table tab: categories as rows (sticky name column), incomes as columns by day, totals row and column; unused
  categories hidden; cells empty when 0; bad sums in the warning color; read-only.
- History: `history(inputs)`; tapping a version opens the Table tab of that version read-only.
- Back with unsaved changes asks «Отменить изменения?».

## Steps

1. View model with the draft and checks (unit tests: add/rename/remove category, per-income amounts, unused toggle,
   add/remove income, checks follow, save calls `savePlan` with the chosen month).
2. Screens, sheets, table, dialog, history; navigation; strings.
3. UI tests (jvmTest): build the notebook plan from scratch (scenario 1) and see «Доход 346 000 · Категории 346 000»;
   a row that does not add up shows the warning; saving from next month leaves the current month unchanged on the
   Budget screen; history lists two versions; read-only member sees no edit actions.
4. Manual walkthrough by `emulator-tester`: scenario 1 (build the plan), scenario 5 (mark «Сейф» unused from next
   month), the table tab with 20 categories (scroll, sticky column), light and dark, ru; screenshots.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest --tests '*Budget*'`; full gate at the end; emulator report without open
  bugs.

## Docs to update

- Budget design "Implementation notes (M5-05)"; spec progress notes; STATUS.md; plan status.

## Stop and report if

- The grid needs a dependency not in `gradle/libs.versions.toml`.

## Handoff

Russian summary with screenshots: you can build the distribution plan like in the notebook, see where numbers do not
add up, choose from which month a change applies, and look at earlier versions. Next plan M5-06.
