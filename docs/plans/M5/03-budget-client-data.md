---
id: M5-03
status: ready
depends_on: [M5-02]
requirements: [BUD-004, BUD-010, BUD-011, BUD-012, BUD-013, BUD-014, BUD-015, BUD-020, BUD-021]
---
# M5-03: Budget in the client core — tables, adapters, views, repository, writer

## Goal

The phone keeps the budget offline and syncs it: local tables, four adapters mirroring the server, pure views for
every screen (`BudgetViews`), a repository with flows and a writer. Multi-device tests prove the invariants of the
design.

## Read first

- [Budget design](../../architecture/budget.md) § Client, § Invariants to test; § Contract data for the types
- [Sync protocol](../../architecture/sync.md) § Client data model, § Local write
- [Conventions § Client](../../process/conventions.md#client) — "New syncable type on the client"
- Code templates: `client/shared/…/data/shopping/` and `data/calendar/` (inputs, pure views, repository, writer),
  `client/shared/…/sync/Adapters.kt` (`EventOccurrenceAdapter` for derived ids), `sqldelight/migrations/5.sqm`,
  `sync/SyncState.kt`; DI module where the calendar repository and writer are bound; `integration-tests/…`
  (`familyTest`, `TestNetwork`, `assertConverged`)

## Scope

- `6.sqm` and the `.sq` table files as in the design; `DATA_SCHEMA_VERSION = 6`.
- Adapters `BudgetPlanAdapter`, `BudgetMonthAdapter`, `BudgetReceiptAdapter`, `BudgetFundingAdapter`, registered in
  `defaultEntityAdapters`, with the same checks as the server (derived ids, immutable fields, author fields, receipt
  delete gate, the current-month check for plans with the device clock and household zone).
- `data/budget/`: `BudgetInputs`, `BudgetViews` (functions of the design table), view models of the data layer
  (`MonthRow`, `MonthView`, `IncomeView`, `FundingRow`, `PlanView`, `VersionRow`, `BudgetToday`), `BudgetRepository`,
  `BudgetWriter`; Koin bindings.
- Integration tests in `integration-tests/` (`BudgetSyncTest`).

Out of scope: UI (M5-04…M5-06).

## Decisions (do not revisit)

- Tables are stored as JSON text with the contract `Json`; parse once per emission in the repository, not per row
  render.
- "Current month" and "today" come from the clock in the household zone (`householdZone`), re-evaluated on the
  existing ticks flow (midnight rollover like the calendar).
- `months(inputs)` lists from `firstMonth` to the current month; future versions (saved "from next month") do not add
  months. `month(inputs, m)` for a future month is allowed (the plan screen previews it) but the Budget screen never
  navigates past the current month.
- `IncomeView` key: a planned income by `incomeId`, an extra one by its receipt id (`sealed interface IncomeKey`).
- `FundingRow.changed` = the cell differs from the plan (month edits). `fundedBy` name from members (removed members
  shown by name as elsewhere).
- `BudgetWriter.savePlan` with `from` = the current month also resets a month row that has a table (design § Writer);
  both writes in one `LocalWriter` transaction if the writer supports it, else plan first.
- `editMonth` compares the new table with the plan's structurally (data class equality after normalizing) and writes
  null when equal.
- `fundAll` writes only unticked rows of used categories with a cell for that income, in table order.
- The category-delete rule of the UI («only when nothing uses it») is a repository query
  `isCategoryUsed(categoryId, before: YearMonth)`: true if any version with `effectiveFrom < before`, any month row
  table, or any funding references it; likewise `isIncomeUsed(incomeId, before)` (versions, month rows, receipts,
  fundings).

## Steps

1. Tables, migration, schema version; adapters with unit tests (each server rule mirrored once).
2. `BudgetViews` with unit tests on the notebook fixture of M5-01: months list across two versions, month view with a
   month copy and differences, income view with partial and changed ticks, plan view for a future month, history
   order and `current`, `today` before and after all incomes are funded, no plan → empty/null.
3. Repository and writer with unit tests (in-memory SQLite): `savePlan` from next month leaves the current month view
   unchanged; from the current month resets its month copy and keeps receipts and ticks; `editMonth` equal to the plan
   writes null; `recordIncome`/`clearIncome`; extra income add/update/delete; `fund`/`unfund`/`fundAll`;
   `isCategoryUsed`.
4. Integration tests (`integration-tests/…/BudgetSyncTest`): two clients offline tick different categories of one
   income → both survive after sync; the same category → one row, converged; both record the same income → one row;
   one saves the plan from next month while the other ticks this month → both kept; a client without `budget.view`
   holds no budget rows; `assertConverged` at the end of each test.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest` and `:integration-tests:test` while iterating; full gate at the end.

## Docs to update

- `architecture/sync.md` § Client data model if it lists tables; budget design "Implementation notes (M5-03)" for any
  detail decided here.
- Budget spec progress notes (*Client core: …*); STATUS.md; plan status.

## Stop and report if

- The `LocalWriter` cannot write two rows of different types in one call and the plan-then-month order breaks an
  invariant test.

## Handoff

Russian summary: the phone now keeps the budget offline and syncs it; two phones can record incomes and tick
categories at the same time without losing anything; N tests. Next plan M5-04 (the first budget screens).
