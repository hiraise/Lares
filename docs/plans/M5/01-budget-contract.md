---
id: M5-01
status: done
depends_on: [M4]
requirements: [BUD-001, BUD-002, BUD-003, BUD-004, BUD-011, BUD-012, BUD-013, BUD-014]
---
# M5-01: Budget contract — data, derived ids, rules, validators, BudgetMath

## Goal

Everything server and client share for the budget: the four entity data classes, entity type names, derived row ids,
write rules, validators and normalizers, and the pure calculations (`BudgetMath`) that every screen is built on.

## Read first

- [Budget design](../../architecture/budget.md) § Overview, § Contract data (incl. Derived ids and Logic), § Write rules,
  § Validation
- [ADR-0032](../../adr/0032-budget-plan-versions-and-month-rows.md), [ADR-0022](../../adr/0022-event-series-with-occurrence-rows.md) (derived ids)
- [Budget spec](../../product/features/budget.md) § Requirements, § Access
- Code templates: `contract/…/entities/ShoppingEntities.kt`, `CalendarEntities.kt`; `contract/…/Ids.kt`;
  `contract/…/permissions/ShoppingRules.kt`, `WriteRules.kt`; `contract/…/validation/Validators.kt`; the calendar
  schedule code and its tests as the style for pure logic; `tools/dev/seed.py` (derived id functions)

## Scope

- `contract/…/entities/BudgetEntities.kt`: the data classes exactly as in the design; `EntityTypes` constants.
- `kotlinx.datetime.YearMonth` on the wire as `"2026-12"`; if the library's default serializer is not that ISO string,
  add `YearMonthSerializer` in `contract/…/serialization/` and use it for every `YearMonth` field.
- `Ids`: `budgetPlanRowId`, `budgetMonthRowId`, `budgetReceiptRowId`, `budgetFundingRowId` with the markers and
  `index(month)` of the design; the same four functions in `tools/dev/seed.py`.
- `BudgetRules` and the `WriteRules.canMutate` gates for the four types (delete only for `budget_receipt`).
- `Validators.budgetTable`, `.budgetPlan`, `.budgetMonth`, `.budgetReceipt`, `.budgetFunding` and the normalizers,
  constants as in the design.
- `contract/…/budget/BudgetMath.kt` with `TableCheck`, `TableDifferences`, `MonthSummary`, `IncomeSummary`,
  `CategorySummary`.

Out of scope: server (M5-02), client (M5-03).

## Decisions (do not revisit)

- Amounts are `Long` whole roubles; no currency field.
- `BudgetTable` defaults to empty lists so an empty plan decodes.
- `incomeDate(month, day)` clamps to the month's last day (31 → 30 April, 28/29 February).
- `summary` orders planned incomes by planned date, then table order; extra incomes after them by actual date, then id.
- `differences` compares by ids: a category used in one table and missing or unused in the other is added/removed;
  same id with a different name or amount is changed; cells compare amounts of used categories only.
- `check` ignores unused categories in rows and columns; `badRows` / `badColumns` hold ids, not names.
- `free` may be negative; `shortfall` counts only planned incomes whose actual amount is recorded.
- Validation reports one code for the whole `table` field (first problem wins, in the order listed in the design).

## Steps

1. Entities, type names, `YearMonth` serialization; derived ids in Kotlin and `seed.py`.
2. Rules and validators with normalizers.
3. `BudgetMath`.
4. Tests (`contract/src/commonTest/…`, names start with requirement ids):
   - Serialization round trip of all four types with every field; `YearMonth` as `"2026-12"`; an empty plan table.
   - Derived ids: stable, version 8, distinct for plan vs month of the same month, receipt vs funding, two categories of
     one income; `seed.py` gives the same ids (a fixed vector in the Kotlin test and a doctest-like check in `seed.py`).
   - Validators: each rule of § Validation once, including duplicate names in another case, a cell for an unknown
     income, 101 categories, day 0 and 32, `label` with an `incomeId`, funding amount without `fundedBy`.
   - Normalizers: whitespace in names collapsed, zero cells dropped, blank label → null.
   - `BudgetMath` with the customer's notebook as the fixture (four incomes 162 000 / 12 000 / 160 000 / 12 000 on the
     5th, 10th, 20th, 25th; Food 60 000 = 27 000 + 2 000 + 29 000 + 2 000, Car 20 000 = 10 000 + 10 000, …):
     `planFor` picks the right version across three versions; `tableFor` prefers the month row; `incomeDate` on 31 in
     February and April; `check` flags a row and a column that do not add up and ignores an unused category;
     `differences` lists changed cells, an added one-off category and a renamed one; `summary` with a short income
     (shortfall), a partially funded category, a tick before the income is recorded (negative free money), an extra
     income, a stale funding for a removed category (ignored).

## Acceptance

- `tools/dev/verify.sh :contract:jvmTest` while iterating; full gate `tools/dev/verify.sh` at the end.

## Docs to update

- Budget design only if a detail had to change; `architecture/calendar.md` § Derived ids: one line that the budget adds
  markers 0x21–0x24.
- Budget spec: progress notes (*Contract: …*) under the requirements touched, state `doing`.
- STATUS.md; plan status.

## Stop and report if

- `kotlinx.datetime.YearMonth` is missing or unusable in common code with kotlinx-datetime 0.8.0.

## Handoff

Russian summary: the shared "rules of the budget" are ready — what a plan, a month, an income and a tick are, how the
app checks the numbers and computes shortfall and free money, tested on the customer's own table. Next plan M5-02.
