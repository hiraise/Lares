---
status: approved  # customer, 2026-11-07 (Q-031; Q-032, Q-033 open)
milestone: M5
updated: 2026-11-07
---
# Budget (BUD)

## Summary

The family plans how each month's income is distributed into categories. A **distribution plan** is a table: rows
are categories (Food, Car, Utilities, Vacation…), each with a monthly amount; columns are the incomes expected on
given days of the month (162 000 on the 5th, 12 000 on the 10th…); a cell says how much of that income goes to that
category. Each month the family records what actually came in on each income, ticks the categories they have funded
(transferred in the bank) and sees the shortfall or the free money.

The budget is about **distributing income, not about spending**: the app does not record expenses, accounts or
balances (customer, 2026-11-07). The money itself sits on bank accounts; the app does not track where.

This replaces the envelope concept of 2026-10-22 (allocation rules, category deficits, inter-category loans, real
debts, accounts and transactions): see Not in scope.

## Scenarios

The customer's notebook today: expected income 346 000 a month — 162 000 on the 5th, 12 000 on the 10th, 160 000 on
the 20th, 12 000 on the 25th; about twenty categories.

1. Mom opens Budget for the first time and builds the plan: adds the four incomes (day and amount) and the categories
   with their monthly amounts (Food 60 000, Car 20 000, Utilities 25 000…), then fills the cells: Food gets 27 000 on
   the 5th, 2 000 on the 10th, 29 000 on the 20th and 2 000 on the 25th. A row whose cells do not add up to the
   category's amount and a column that does not add up to the income are highlighted.
2. On 1 December the app opens the December month from the current plan. On the 4th the 5th's salary comes early and
   is 150 000 instead of 162 000: Mom enters 150 000 and the 4th. The app shows «Недобор 12 000» for that income.
3. Mom transfers money in the bank app and ticks the categories of that income one by one. Food's 27 000 she
   changes to 20 000 because the money is short; the cell shows 20 000 of 27 000. The income shows how much is left
   undistributed.
4. In December the family wants 30 000 for Gifts instead of 10 000, only this month: Mom changes the month, not the
   plan. The month shows the cell as changed from the plan.
5. In January the family stops putting money in the safe: Mom marks the category «Сейф» unused in the plan from
   February. December and January still show it; February does not.
6. Dad opens Budget and sees the current month: per income expected and actual, what is funded, the shortfall or the
   free money of the month. He opens November to compare.
7. The 14-year-old son has no Budget section (no `budget.view`).

## Requirements

### Plan

- **BUD-001** `MVP` `done` — Categories: name, monthly amount, order in the table. A category can be unused (kept for
  history and to use again, not in new months) or deleted when no month uses it. Categories can be reordered.
  *Contract: categories with `unused`, name and amount rules, normalizer (M5-01).*
  *UI: «План › Категории» with the category sheet, «Не использовать», «Удалить» only when nothing uses it, «Выше» /
  «Ниже» in the row menu (no drag) (M5-05).*
- **BUD-002** `MVP` `done` — Incomes of the plan: day of the month (1–31; on shorter months the last day) and
  expected amount. No source or person is recorded (customer: the date is enough). Two incomes may share a day.
  *Contract: incomes by day, `BudgetMath.incomeDate` clamps to the month's last day (M5-01).*
  *UI: «План › Поступления» by day with the income sheet (day, expected amount) (M5-05).*
- **BUD-003** `MVP` `done` — Plan cells: for each category and income, the amount of that income that goes to the
  category; empty means nothing. The table shows row sums against the category's monthly amount, column sums against
  the income's expected amount, the total expected income and the total of the categories; a row or column that does
  not add up is highlighted, saving is still allowed (customer: «давай посмотрим как получится»).
  *Contract: cells, table validation, `BudgetMath.check` (row and column sums, bad rows and columns) (M5-01).*
  *UI: per-income fields with «Распределено X из Y», the summary bar «Доход · Категории» with «не сходится на N»,
  warning icons, the read-only «Таблица» like the notebook (M5-05).*
- **BUD-004** `MVP` `done` — The plan is versioned. Saving a change asks from which month it applies: the next month
  (default) or the current one. Earlier months keep the version they were made from; the plan's history lists the
  versions with their date and author. ASSUMPTION: applying to the current month replaces its plan cells but keeps
  what was already entered or ticked.
  *Contract: plan versions with derived ids, `BudgetMath.planFor` / `firstMonth` (M5-01).*
  *Server: versions stored and synced; versions of past months read-only, with a day of grace after midnight (M5-02).*
  *Client core: versions kept offline; saving from the current month returns its month copy to the plan, keeping
  amounts and ticks; past versions refused on the phone too; history newest first (M5-03).*
  *UI: the plan is edited as a local draft; «Сохранить» asks «С января» / «С декабря (текущий месяц)» with the warning
  about month changes; «История плана» opens each version read-only (M5-05).*

### Month

- **BUD-010** `MVP` `doing` — A month is opened from the plan version in force (automatically on its first day, or
  when someone opens it earlier). Past months stay viewable; a month is never deleted.
  *Client core: months listed from the first version to the current one, nothing written to open a month (M5-03).*
  *UI: «Ещё › Бюджет» opens the current month, ‹ › and «Все месяцы» open past months; without a plan the empty state
  «Составьте план распределения» (M5-04).*
- **BUD-011** `MVP` `doing` — Actual income: for each income of the month, the amount and date it actually came; the
  date may be earlier or later than planned. ASSUMPTION: an extra income not in the plan (a bonus) can be added to a
  month; it has no cells and counts as free money.
  *Contract: receipts of planned and extra incomes, validation (M5-01).*
  *Server: receipts stored and synced; only extra incomes can be deleted (M5-02).*
  *Client core: record / clear an income, add, change and delete extra incomes offline; two phones recording one
  income write one row (M5-03).*
  *UI: the income page records «Пришло» and «Дата» (prefilled with the expected amount and today), «Очистить»; extra
  incomes in UI with M5-06 (M5-04).*
- **BUD-012** `MVP` `doing` — Funding: in each income's column the member ticks the categories transferred from it.
  The transferred amount is the planned one and can be changed (less or more). The category row shows funded of its
  monthly amount.
  *Contract: ticks with derived ids, `fundedBy` written only as oneself (M5-01).*
  *Server: ticks stored and synced; two members ticking the same cell write one row, the later amount wins (M5-02).*
  *Client core: tick, untick, «Отметить все»; the income view with who ticked and changed cells; ticks of different
  categories on two phones offline both survive (M5-03).*
  *UI: square checkboxes per category of the income, «Перевели» sheet for another amount («20 000 из 27 000»),
  «Отметить все»; read-only without checkboxes (M5-04).*
- **BUD-013** `MVP` `doing` — Changing a month without changing the plan: a cell's planned amount, a category added
  or left out for this month only. Every difference from the plan is marked in the month (customer: «должно быть
  отражено»).
  *Contract: month copy of the table, `BudgetMath.tableFor` / `differences` (M5-01).*
  *Server: month copies stored and synced (M5-02).*
  *Client core: month copy written only when it differs from the plan; reset to the plan (M5-03).*
- **BUD-014** `MVP` `doing` — Totals of the month and of each income: expected, actual, transferred; shortfall
  (actual below expected) and free money (actual minus transferred). Free money is only shown; it is not carried to
  the next month or assigned anywhere (customer, 2026-11-07).
  *Contract: `BudgetMath.summary` — expected, actual, transferred, shortfall, free money (M5-01).*
  *Client core: totals of every month and income on the phone (M5-03).*
  *UI: hero «Свободно / Недобор / Ожидается», per income «Недобор» and «Не распределено», categories funded of
  planned (M5-04).*
- **BUD-015** `MVP` `doing` — Works offline and syncs like the other modules (FND-040).
  *Server: four sync types, `budget.view` reads and `budget.manage` writes (M5-02).*
  *Client core: four local tables and adapters with the server's checks; members without `budget.view` hold no
  budget rows (M5-03).*

### Overview

- **BUD-020** `MVP` `doing` — Budget opens on the current month: the next expected income, what is not yet funded,
  the month's shortfall or free money. The list of months shows for each its expected, actual and free money.
  *Client core: views of the current month and of all months (M5-03).*
  *UI: the Budget screen and «Все месяцы» with expected, actual and free money or shortfall (M5-04).*
- **BUD-021** `MVP` `doing` — «Today» shows a budget card to members with `budget.view` (FND-063). ASSUMPTION: the next
  expected income and how many categories of the month are not funded yet.
  *Client core: the next unrecorded income and the categories of recorded incomes not ticked yet (M5-03).*
- **BUD-022** `later` `todo` — Analytics over months (plan vs. actual, totals per category over a year, months with a
  shortfall). The customer will say which (Q-033).

### Later

- **BUD-030** `later` `todo` — Reminder on an income's day to record it and fund the categories (Q-032).
- **BUD-031** `later` `todo` — Spending: category balances and expenses (customer: «пока нет»).
- **BUD-032** `later` `todo` — Where the money of a category lies (bank account); not needed now.
- **BUD-033** `later` `todo` — Other currencies.

## Not in scope (customer, 2026-11-07)

The concept of 2026-10-22 is dropped: money accounts and transactions, allocation rules by percent and order,
category balances and deficits, loans between categories, real debts with schedules (a debt is just a category,
«Долги»), spending reports by category and tag. Receipt photos (M5b) wait for spending.

## Access

| Who | What |
| --- | --- |
| Members with `budget.view` | See the Budget section, the plan, every month and its history |
| Members with `budget.manage` | Change the plan, open months, record incomes, tick funding, change a month |
| Others | No Budget section and no budget card on «Today»; budget data never reaches their phones |

Template defaults: Administrators have both; Adults have both (ASSUMPTION, as for the other modules); Kids have none.
There is no per-category access.

## Notes

- Amounts are whole roubles (customer, 2026-11-07): integers, no kopecks; one household currency, ₽.
- Glossary terms: distribution plan, plan version, budget category, income, budget month, funding, shortfall, free
  money ([glossary](../../domain/glossary.md#budget)).

## Open points

- Q-032: reminders on the income day.
- Q-033: which analytics.
- Technical design (sync of the plan and months, versions, conflicts when two phones tick the same cell) before the M5
  plans.
