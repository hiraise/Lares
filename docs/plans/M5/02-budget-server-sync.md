---
id: M5-02
status: ready
depends_on: [M5-01]
requirements: [BUD-004, BUD-011, BUD-012, BUD-013, BUD-015]
---
# M5-02: Budget on the server — tables, sync handlers, permissions

## Goal

The server stores and syncs the four budget types: migration, four `WritableSyncHandler`s with derived-id, immutable
field and past-version checks, visibility by `budget.view`, writes by `budget.manage`, housekeeping. No notifications,
no jobs.

## Read first

- [Budget design](../../architecture/budget.md) § Write rules, § Validation (server-only checks), § Server
- [Sync protocol](../../architecture/sync.md) § Push, § Housekeeping
- [Conventions § Server](../../process/conventions.md#server) — "New syncable type"
- Code templates: `server/…/calendar/` (`EventOccurrenceSyncHandler` for derived ids and upsert-only rows),
  `server/…/shopping/` (tables, repository, author checks), `server/…/location/` (`place_alert`, last registered
  handlers), `sync/WritableSyncHandler.kt`, `sync/ReferenceChecks.kt`, `sync/SyncHousekeeping.kt`, `Application.kt`;
  `db/migration/V8__calendar.sql`; tests under `server/src/test/…/calendar/` and fixtures in `testFixtures/…/testing/`

## Scope

- `V10__budget.sql` as in the design.
- Package `app.lares.server.budget`: `BudgetTables.kt`, `BudgetRepository.kt` (row ↔ data; tables as JSON text with the
  contract `Json`), `BudgetChecks.kt` (derived-id check, current-month check using the household zone),
  `BudgetPlanSyncHandler.kt`, `BudgetMonthSyncHandler.kt`, `BudgetReceiptSyncHandler.kt`, `BudgetFundingSyncHandler.kt`.
- Registration in `AppDeps.syncHandlers` after the location handlers; `SyncHousekeeping` purges `budget_receipt`
  tombstones.
- Test fixtures `testFixtures/…/testing/BudgetFixtures.kt` (mutation builders `newBudgetPlan`, `budgetReceipt`,
  `budgetFunding`, `budgetMonth`, computing derived ids), shared with `integration-tests`.
- `tools/dev/seed.py budget`: writes the notebook plan from the current month, records the first income and ticks half
  of its categories (uses the M5-01 id functions) — for manual checks.

Out of scope: client (M5-03), UI.

## Decisions (do not revisit)

- Columns as in the design; `effective_from` and `month` are text `"YYYY-MM"`; `date` is `date`; instants
  `timestamptz`.
- Order of checks per mutation as in M1–M4: coarse `WriteRules`, decode/normalize, `authorize`, `validate`,
  `checkUpsert`.
- `authorize`: `canManage`; `foreignUpdater` (plan, month, receipt) / `foreignFunder` (funding) → `FORBIDDEN`; deletes
  only for receipts whose stored `incomeId` is null (else `FORBIDDEN` "Planned income receipts cannot be deleted").
- `checkUpsert`: id ≠ derived id → `invalidField("id")` (extra receipts: must be a UUIDv7); changed `effectiveFrom`,
  `month`, `incomeId`, `categoryId` → `invalidField`; plan `effectiveFrom` before the month of `ctx.now − 1 day` in the
  household zone → `invalidField("effectiveFrom", OUT_OF_RANGE)`; `fundedAt` / plan `updatedAt` changed to later than
  `ctx.now + 1 day` → `OUT_OF_RANGE`.
- The current-month check runs on every plan upsert, also one that changes nothing else (a past version is
  read-only). An unchanged upsert of a past version is still rejected — clients never send one.
- No check that a funding's income/category exist in a table (design § Validation).
- `sync.md` § Entity types: four rows (`budget_plan` — "Budget (M5-02); one plan version; derived id; upsert only",
  `budget_month` — "month-only edits; derived id; upsert only", `budget_receipt` — "actual income; derived id for planned
  incomes (upsert only), random id for extra incomes (deletable)", `budget_funding` — "one tick; derived id; upsert
  only"); § Housekeeping the receipt purge.

## Steps

1. Migration, tables, repository, checks.
2. Four handlers, registration, housekeeping; fixtures; `seed.py budget`.
3. Tests (`server/src/test/…/budget/`, names start with requirement ids):
   - `BudgetSyncTest`: create each type and pull it back with the exact JSON; an unchanged upsert is a no-op
     (`seq: null`); a plan for the current month accepted, for last month rejected `effectiveFrom: OUT_OF_RANGE`, for
     last month at 00:30 on the 1st (household zone, fixed clock) accepted (grace day); wrong derived id →
     `id: INVALID_FORMAT`; changed `month` → `INVALID_FORMAT`; a table with a duplicate category name →
     `table: INVALID_FORMAT`; a receipt with amount and no date → `date: REQUIRED`; delete of a planned receipt →
     `FORBIDDEN`, of an extra receipt → tombstone, then re-upsert → `ENTITY_DELETED`; funding `fundedAt` two days ahead
     → `OUT_OF_RANGE`; unticking keeps `fundedBy`.
   - Two members tick different categories of one income → two rows; the same category → one row, the later amount wins.
   - `BudgetPermissionsTest` (spec § Access): Adults read and write; Kids get no budget rows in a pull and `FORBIDDEN` on
     writes; a custom group with only `budget.view` reads but every write is `FORBIDDEN`; `updatedBy` / `fundedBy` of
     another member → `FORBIDDEN`.
   - Housekeeping purges old receipt tombstones.

## Acceptance

- `tools/dev/verify.sh :server:test` (Docker running); full gate at the end. `tools/dev/server.sh reset && tools/dev/seed.py
  family && tools/dev/seed.py budget` runs without errors.

## Docs to update

- `architecture/sync.md` (above); budget design only if a detail changed; `docs/process/dev-setup.md` or the seed's
  help: the `budget` command.
- Budget spec progress notes (*Server: …*); STATUS.md; plan status.

## Stop and report if

- The household zone is not reachable from a sync handler without changing `WritableSyncHandler`.

## Handoff

Russian summary: the server now keeps the family's budget plan, months, incomes and ticks; only those with access see
it, past versions of the plan cannot be changed; N server tests. Next plan M5-03.
