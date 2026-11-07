---
id: M5-04
status: done
depends_on: [M5-03]
requirements: [BUD-010, BUD-011, BUD-012, BUD-014, BUD-020]
---
# M5-04: Budget UI — the month screen, the income page, all months

## Goal

Members with `budget.view` open «Ещё › Бюджет» and see the current month: the hero with free money or shortfall, the
incomes with their state, the categories with funded of planned. Members with `budget.manage` record an income and
tick its categories. Past months open from «Все месяцы». Without a plan: the empty state.

## Read first

- [Budget design](../../architecture/budget.md) § UI (Budget screen, Income page), § Client (views)
- [Budget spec](../../product/features/budget.md) § Scenarios 2, 3, 6, 7
- [Visual style](../../design/visual-style.md) (hero card, list group, shopping checkbox, progress bar, typography
  `displaySmall`)
- Code templates: `ui/shopping/` (list page with checkboxes, sheets), `ui/location/` (newest section wiring from «Ещё»),
  `ui/main/MoreScreen.kt` and `Navigation.kt`, `ui/components/`; UI test style in `client/shared/src/jvmTest/…/ui/`

## Scope

- Navigation: «Бюджет» from «Ещё» opens `BudgetScreen`; routes for the income page and «Все месяцы».
- `ui/budget/`: `BudgetViewModel` (month shown, ‹ › within `months`), `BudgetScreen`, `IncomeViewModel`,
  `IncomePage`, `MonthsPage`, components (`AmountText` with the «1 234 ₽» format, income row, category row).
- Income page: «Пришло» and «Дата» fields (prefill expected amount and today on first edit; «Очистить» resets the
  receipt), the category list with checkboxes, the «Перевели» sheet to change an amount, «Отметить все», the free money
  line.
- Empty state without a plan: «Составьте план распределения»; the «Создать план» button navigates to a placeholder
  route that M5-05 fills (until then it is hidden).
- Strings en and ru for everything above.

Out of scope: plan editor (M5-05), month-only edits, extra incomes, Today card (M5-06).

## Decisions (do not revisit)

- Amount format: digits grouped by a narrow no-break space (U+202F), then « ₽»; negative with «−». One shared
  formatter in `ui/budget/`, unit-tested.
- Amount input: digits only, max 10 digits, numeric keyboard; empty = not recorded.
- Hero text precedence: free > 0 → «Свободно N ₽»; shortfall > 0 → «Недобор N ₽»; free < 0 → «Переведено больше, чем
  пришло: N ₽»; nothing recorded → «Ожидается N ₽»; else «Всё распределено».
- Read-only (only `budget.view`): no checkboxes, fields shown as text, no menu.
- A tick writes immediately (like shopping); the «Перевели» sheet saves on «Готово».
- The month shown is kept across process death (saved state), default the current month.

## Steps

1. Formatter and components; view models over `BudgetRepository`.
2. Screens and navigation; strings.
3. UI tests (jvmTest, names start with requirement ids): the screen with the notebook fixture shows the hero, four
   incomes, categories; recording 150 000 for the 5th shows «Недобор 12 000 ₽»; ticking a category moves its row to
   funded and updates «Переведено 1 из N»; changing an amount shows «20 000 из 27 000»; read-only member sees no
   checkboxes; «Все месяцы» lists months newest first; empty state without a plan.
4. Manual walkthrough by the `emulator-tester` agent with `seed.py budget`: scenarios 2, 3, 6 of the spec in light and
   dark theme, ru; screenshots for the handoff.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest --tests '*Budget*'` while iterating; full gate at the end; emulator report
  without open bugs.

## Docs to update

- Budget design "Implementation notes (M5-04)" for UI details decided here; spec progress notes (*UI: …*); STATUS.md;
  plan status.

## Stop and report if

- The design's screen structure does not fit a phone screen with ~20 categories in a way that a small layout change
  cannot fix.

## Handoff

Russian summary with screenshots: the budget section opens with the current month; you can enter what came in and tick
the categories you transferred; shortfall and free money are shown. Next plan M5-05 (the plan editor).
