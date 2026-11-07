---
status: accepted  # design of M5; binding for the M5 plans
updated: 2026-11-07
---
# Budget module design (M5)

Technical design for the [budget spec](../product/features/budget.md). Decision behind it:
[ADR-0032](../adr/0032-budget-plan-versions-and-month-rows.md) (plan versions by month, month rows with derived ids).
Implemented by plans [M5-01…M5-06](../plans/README.md#m5-plans).

## Overview

- Four client-writable sync types, no parent/child cascade:
  - `budget_plan` — one **plan version**: the whole table (categories, incomes, cells) in force from a month on.
  - `budget_month` — the **month-only edits** of one month: a full copy of the table, or none (follows the plan).
  - `budget_receipt` — the **actual amount and date** of one income in one month; also extra incomes (bonuses).
  - `budget_funding` — **one tick**: category × income × month, with the transferred amount.
- Plan, month and planned-income receipt rows have **derived ids** (ADR-0022 algorithm): two phones that write the same
  month offline write the same row, and the fields merge. They are never deleted (upsert only). Funding rows are
  derived too. Only extra-income receipts have random ids and can be deleted.
- Which plan applies to a month: the version with the greatest `effectiveFrom ≤ month`. The table of a month: the
  month row's table if present, else that version's table. Everything else (sums, checks, shortfall, free money,
  differences from the plan) is computed by `BudgetMath` in `:contract`, on the phone.
- A month exists implicitly from the first version on; nothing is written to "open" it (BUD-010).
- The server stores, checks and syncs; no notifications (Q-032) and no jobs. Visibility: `budget.view`; writes:
  `budget.manage`.
- Amounts are whole roubles as `Long` (BUD notes); no currency field.

## Contract data (`contract/…/entities/BudgetEntities.kt`)

```kotlin
@Serializable
data class BudgetCategory(
    val id: String,                 // UUIDv7, stable across versions and months; never reused
    val name: String,               // trimmed, inner whitespace collapsed, 1..60; unique in the table, case-insensitive
    val amount: Long,               // monthly amount, 0..1_000_000_000
    val unused: Boolean = false,    // kept in the table, left out of months (BUD-001)
)

@Serializable
data class BudgetIncome(
    val id: String,                 // UUIDv7, stable across versions
    val day: Int,                   // 1..31; on shorter months the last day (BudgetMath.incomeDate)
    val amount: Long,               // expected, 0..1_000_000_000
)

@Serializable
data class BudgetCell(val categoryId: String, val incomeId: String, val amount: Long)   // amount 1..1_000_000_000

@Serializable
data class BudgetTable(
    val categories: List<BudgetCategory> = emptyList(),   // display order; ≤ 100
    val incomes: List<BudgetIncome> = emptyList(),        // ≤ 20; displayed by day, then list order
    val cells: List<BudgetCell> = emptyList(),            // one per (category, income) at most
)

@Serializable
data class BudgetPlanData(
    val effectiveFrom: YearMonth,   // kotlinx.datetime.YearMonth, wire "2026-12"; never changes
    val table: BudgetTable,
    val updatedBy: String,          // member id of the last writer (always the writer)
    val updatedAt: Instant,         // when it was saved last (the plan history, BUD-004)
)

@Serializable
data class BudgetMonthData(
    val month: YearMonth,           // never changes
    val table: BudgetTable? = null, // null = the month follows its plan version (BUD-013)
    val updatedBy: String,
)

@Serializable
data class BudgetReceiptData(
    val month: YearMonth,           // never changes
    val incomeId: String? = null,   // the planned income; null = extra income (bonus, BUD-011); never changes
    val label: String? = null,      // extra incomes only: trimmed, blank -> null, ≤ 60
    val amount: Long? = null,       // actual, 0..1_000_000_000; null = not recorded
    val date: LocalDate? = null,    // actual date; required when amount is set
    val updatedBy: String,
)

@Serializable
data class BudgetFundingData(
    val month: YearMonth,           // never changes
    val incomeId: String,           // never changes
    val categoryId: String,         // never changes
    val amount: Long? = null,       // transferred, 0..1_000_000_000; null = not funded (unticked)
    val fundedBy: String? = null,   // the writer when amount is set; kept when unticked
    val fundedAt: Instant? = null,
)
```

- Entity types: `EntityTypes.BUDGET_PLAN = "budget_plan"`, `BUDGET_MONTH = "budget_month"`,
  `BUDGET_RECEIPT = "budget_receipt"`, `BUDGET_FUNDING = "budget_funding"`.
- A cell with amount 0 is not stored (the normalizer drops it). Cells of unused categories are kept (they come back
  when the category is used again) but ignored by all sums.
- The tables are small (≈ 20 × 4 cells, a few kB of JSON), so a version or a month copy is one field.

### Derived ids (`contract/…/Ids.kt`)

```kotlin
fun budgetPlanRowId(householdId: String, effectiveFrom: YearMonth): String     // derive(householdId, index(effectiveFrom), PLAN_MARKER)
fun budgetMonthRowId(householdId: String, month: YearMonth): String            // derive(householdId, index(month), MONTH_MARKER)
fun budgetReceiptRowId(incomeId: String, month: YearMonth): String             // derive(incomeId, index(month), RECEIPT_MARKER)
fun budgetFundingRowId(incomeId: String, categoryId: String, month: YearMonth): String
    // derive(budgetReceiptRowId(incomeId, month), saltOf(categoryId), FUNDING_MARKER)
```

`index(m) = m.year * 12L + (m.month.number − 1)`. Markers are new byte-7 values distinct from the existing ones
(`0x5A` reminder, the place-alert marker): `PLAN_MARKER = 0x21`, `MONTH_MARKER = 0x22`, `RECEIPT_MARKER = 0x23`,
`FUNDING_MARKER = 0x24`. The derivation is repeated in `tools/dev/seed.py` (like the calendar ids).

### Logic (`contract/…/budget/BudgetMath.kt`, pure)

```kotlin
object BudgetMath {
    fun planFor(month: YearMonth, plans: Collection<BudgetPlanData>): BudgetPlanData?   // greatest effectiveFrom ≤ month
    fun firstMonth(plans: Collection<BudgetPlanData>): YearMonth?                       // smallest effectiveFrom
    fun tableFor(month: YearMonth, plans: Collection<BudgetPlanData>, monthRow: BudgetMonthData?): BudgetTable?
    fun incomeDate(month: YearMonth, day: Int): LocalDate                               // day clamped to the month's length
    fun check(table: BudgetTable): TableCheck
    fun differences(monthTable: BudgetTable, planTable: BudgetTable): TableDifferences
    fun summary(month: YearMonth, table: BudgetTable, receipts: Map<String, BudgetReceiptData>,  // keyed by row id
                fundings: Collection<BudgetFundingData>): MonthSummary
}

data class TableCheck(
    val rows: Map<String, Long>,          // used category id -> sum of its cells
    val columns: Map<String, Long>,       // income id -> sum of the cells of used categories
    val badRows: Set<String>,             // used categories whose row sum ≠ amount
    val badColumns: Set<String>,          // incomes whose column sum ≠ expected amount
    val expectedIncome: Long,             // Σ incomes
    val categoriesTotal: Long,            // Σ amounts of used categories
)

data class TableDifferences(               // what the month changed against its plan version (BUD-013)
    val changedCells: Set<Pair<String, String>>,      // (categoryId, incomeId) with a different amount or added/removed
    val addedCategories: Set<String>, val removedCategories: Set<String>,   // used in one table and not the other
    val changedCategories: Set<String>,               // name or monthly amount differs
    val addedIncomes: Set<String>, val removedIncomes: Set<String>, val changedIncomes: Set<String>,
) { val isEmpty: Boolean }

data class MonthSummary(
    val incomes: List<IncomeSummary>,     // planned incomes by date, then extra incomes by date
    val categories: List<CategorySummary>,// used categories in table order
    val expected: Long, val actual: Long, val transferred: Long,
    val shortfall: Long,                  // Σ max(0, expected − actual) over planned incomes that have an actual amount
    val free: Long,                       // actual − transferred (may be negative: transferred more than came)
)
data class IncomeSummary(val incomeId: String?, val receiptId: String?, val label: String?, val plannedDate: LocalDate?,
                         val expected: Long, val actual: Long?, val actualDate: LocalDate?, val transferred: Long,
                         val fundedCount: Int, val plannedCount: Int)   // plannedCount = cells of used categories
data class CategorySummary(val categoryId: String, val name: String, val amount: Long, val planned: Long,
                           val funded: Long, val complete: Boolean)    // complete = every planned cell ticked; false without cells
```

Receipts are passed with their row ids (M5-01): an extra income's `receiptId` and its order "by date, then id" need
the id, which the receipt data does not hold. `fundedCount` counts the ticked planned cells of an income;
`transferred` also counts ticks of a used category without a cell.

Rules: a funding counts only if its income and category are in the month's table and the category is used;
`transferred` sums funding amounts (not planned amounts). Extra incomes have `expected = 0` and no cells. An income
whose actual amount is not recorded contributes nothing to `actual`, `shortfall` or `free`; its ticks still count
toward `transferred` (the user may tick before recording; `free` can go negative and the UI says so).

## Write rules (`contract/…/permissions/BudgetRules.kt`)

`WriteRules.canMutate`: all four types → `budget.manage` (upsert); delete → `budget.manage` for `budget_receipt` only,
false for the other three (the coarse gate). Entity-level:

```kotlin
object BudgetRules {
    fun canManage(viewer: Viewer): Boolean                               // BUDGET_MANAGE
    /** True when [updatedBy] is someone other than the writer (plan, month, receipt rows). */
    fun foreignUpdater(viewer: Viewer, updatedBy: String): Boolean
    /** True when the write sets fundedBy to someone other than the writer (changed to a non-null other member). */
    fun foreignFunder(viewer: Viewer, existing: BudgetFundingData?, merged: BudgetFundingData): Boolean
}
```

| Action | Allowed when |
| --- | --- |
| write a plan version | `canManage`; `effectiveFrom` ≥ the current month (server check below) |
| write a month row, a receipt, a funding | `canManage`; not `foreignUpdater` / `foreignFunder` (else `FORBIDDEN`) |
| delete a receipt | `canManage` and the stored receipt has `incomeId == null` (else `FORBIDDEN`) |

## Validation (`Validators.budgetTable`, `.budgetPlan`, `.budgetMonth`, `.budgetReceipt`, `.budgetFunding`; normalizers)

Constants: `BUDGET_NAME_MAX = 60`, `BUDGET_MAX_CATEGORIES = 100`, `BUDGET_MAX_INCOMES = 20`,
`BUDGET_AMOUNT_MAX = 1_000_000_000`, `BUDGET_LABEL_MAX = 60`.

- Table (`table` field, one code; the first problem wins): more than 100 categories or 20 incomes → `OUT_OF_RANGE`;
  a blank or too long name, a duplicate name (case-insensitive) or id, an id that is not a UUID → `INVALID_FORMAT`;
  `day` outside 1..31 or any amount outside its range → `OUT_OF_RANGE`; a cell whose category or income is not in the
  table, or a second cell for the same pair → `INVALID_FORMAT`.
- Receipt: `incomeId == null` with no `label` is fine («Дополнительное поступление»); `label` with an `incomeId` →
  `label: INVALID_FORMAT`; `amount` set without `date` → `date: REQUIRED`; amount out of range → `OUT_OF_RANGE`.
- Funding: `amount` set without `fundedBy` / `fundedAt` → that field `REQUIRED`; amount out of range → `OUT_OF_RANGE`.
- `month` / `effectiveFrom` with a year outside 2000..2100 → `OUT_OF_RANGE`.
- Normalizers: names `ShoppingNames.normalize`-style (trim, collapse inner whitespace) — use a shared
  `TextNormalize.collapse` if one exists, else a private helper; cells with amount 0 dropped; label trimmed, blank →
  null.

Server-only checks:

- Every type: the row id must equal its derived id (`id: INVALID_FORMAT`), except extra-income receipts, whose id must
  be a UUIDv7; immutable fields (`effectiveFrom`, `month`, `incomeId`, `categoryId`) changed → `INVALID_FORMAT`.
- `budget_plan`: `effectiveFrom` earlier than the month of `ctx.now − 1 day` in the household time zone →
  `effectiveFrom: OUT_OF_RANGE` (past versions are read-only, BUD-004). The day of grace covers phones that saved
  "from this month" just before midnight on the last day.
- `budget_funding`: `fundedAt` changed to later than `ctx.now + 1 day` → `OUT_OF_RANGE` (like shopping ticks); the
  same for `budget_plan.updatedAt`.
- The server does **not** check that a funding's income and category exist in the month's table: resolving the table
  is client logic, and stale ticks are harmless (ignored by `BudgetMath`).

## Server

### Tables (`V12__budget.sql`)

```sql
budget_plan    (id uuid PK, household_id uuid NOT NULL → household, effective_from text NOT NULL,   -- "2026-12"
                plan_table text NOT NULL,                                                         -- JSON BudgetTable
                updated_by uuid NOT NULL → member, saved_at timestamptz NOT NULL,                 -- data.updatedAt
                seq, created_at, updated_at, deleted)
budget_month   (id uuid PK, household_id, month text NOT NULL, month_table text NULL, updated_by uuid NOT NULL → member,
                seq, created_at, updated_at, deleted)
budget_receipt (id uuid PK, household_id, month text NOT NULL, income_id uuid NULL, label text NULL, amount bigint NULL,
                date date NULL, updated_by uuid NOT NULL → member, seq, created_at, updated_at, deleted)
budget_funding (id uuid PK, household_id, month text NOT NULL, income_id uuid NOT NULL, category_id uuid NOT NULL,
                amount bigint NULL, funded_by uuid NULL → member, funded_at timestamptz NULL,
                seq, created_at, updated_at, deleted)
```

Indexes: `(household_id, seq)` on all four. (`table` is reserved in SQL, hence `plan_table` / `month_table`; the plan's
`updatedAt` is `saved_at`, since `updated_at` is the server's sync column.)

### Handlers (package `app.lares.server.budget`)

`BudgetPlanSyncHandler`, `BudgetMonthSyncHandler`, `BudgetReceiptSyncHandler`, `BudgetFundingSyncHandler` extend
`WritableSyncHandler`, registered after the location handlers.

- `canSee(viewer, row)` = `viewer.permissions.has(BUDGET_VIEW)` for all four.
- `authorize`: `BudgetRules.canManage`, then `foreignUpdater` / `foreignFunder`; receipt delete as in the rules table.
- `checkUpsert`: the server-only checks above; the household time zone from the household row (as the task handlers
  read it).
- No cascade. Housekeeping purges `budget_receipt` tombstones (the only deletable type).
- Removing a member does not touch budget rows: `updated_by` / `funded_by` keep the id (the member row is kept as
  removed, like task authors).

## Client

### Local tables (`6.sqm`, `DATA_SCHEMA_VERSION = 6`)

`budget_plan(id PK, effective_from TEXT, plan_table TEXT (JSON), updated_by)`;
`budget_month(id PK, month TEXT, month_table TEXT NULL, updated_by)`;
`budget_receipt(id PK, month TEXT, income_id TEXT NULL, label NULL, amount INTEGER NULL, date TEXT NULL, updated_by)`;
`budget_funding(id PK, month TEXT, income_id, category_id, amount INTEGER NULL, funded_by NULL, funded_at INTEGER NULL)`;
indexes on `month` for month, receipt and funding. Raising the schema version re-pulls everything (as before).

### Adapters

`BudgetPlanAdapter`, `BudgetMonthAdapter`, `BudgetReceiptAdapter`, `BudgetFundingAdapter` in `Adapters.kt`, registered
in `defaultEntityAdapters`: contract normalizers and validators, `BudgetRules`, derived-id and immutable-field checks
like the server (`VALIDATION_FAILED`). The plan adapter also applies the current-month check with the device clock and
the household zone, so the user sees the error before sync.

### Models and views (`data/budget/`)

`BudgetInputs(plans, monthRows, receipts, fundings, members, zone, viewer, today: LocalDate)`.

| Function (`BudgetViews`, pure) | Result |
| --- | --- |
| `months(inputs)` | `List<MonthRow(month, expected, actual, free, shortfall, current)>` from `firstMonth` to the current month (newest first); empty without a plan |
| `month(inputs, month)` | `MonthView(month, table, plan: BudgetPlanData, summary: MonthSummary, check: TableCheck, differences: TableDifferences?, canManage, isCurrent)`; null before the first version |
| `income(inputs, month, incomeId \| receiptId)` | `IncomeView(summary: IncomeSummary, rows: List<FundingRow(categoryId, name, planned, funding: Long?, fundedBy name?, changed)>)` — rows = used categories with a cell for this income, in table order |
| `plan(inputs, from: YearMonth)` | `PlanView(version: BudgetPlanData?, table, check, editableFrom: YearMonth)` — the version in force for `from` |
| `history(inputs)` | `List<VersionRow(effectiveFrom, updatedBy name, updatedAt, current: Boolean)>` newest first; `current` = in force today |
| `today(inputs)` | `BudgetToday(nextIncome: IncomeSummary?, notFunded: Int)` or null without a plan (BUD-021) |

`BudgetRepository(localDb, members, household, permissions, clock, ticks)`: `observeMonths()`, `observeMonth(month)`,
`observeIncome(month, key)`, `observePlan(from)`, `observeHistory()`, `observeToday()`.

### Writer (`BudgetWriter(writer, localDb, clock)`, all through `LocalWriter`; "me" = the logged-in member)

| Function | Writes |
| --- | --- |
| `savePlan(table, from: YearMonth)` | the version row for `from` (derived id) with `table`, `updatedBy = me`, `updatedAt = now`; when `from` is the current month and its month row has a table, that row's `table` → null (BUD-004 assumption: entered amounts and ticks stay) |
| `editMonth(month, table)` | the month row; a table equal to the plan's (structurally) is written as null |
| `resetMonth(month)` | the month row's `table` → null |
| `recordIncome(month, incomeId, amount, date)` / `clearIncome(month, incomeId)` | the derived receipt row; clear = amount and date null |
| `addExtraIncome(month, label?, amount, date): String` / `updateExtraIncome(id, …)` / `deleteExtraIncome(id)` | a receipt with a new id and `incomeId = null`; delete tombstones it |
| `fund(month, incomeId, categoryId, amount)` / `unfund(month, incomeId, categoryId)` | the derived funding row; unfund = amount null |
| `fundAll(month, incomeId)` | `fund` with the planned amount for every unticked row of the income (one mutation each) |

### Implementation notes (M5-03)

- `budget_plan` also stores `updated_at` (epoch ms; the version's `updatedAt`, needed by the history); `funded_at` is
  epoch ms too. The writer truncates its times to milliseconds so the local row and the shadow agree.
- Adapters live in `data/budget/BudgetAdapters.kt` (like the location adapters), registered in
  `defaultEntityAdapters`. Plan and month row ids are derived from the logged-in member's household id. The plan's
  past-month check uses the server's rule including the day of grace (month of `now − 1 day` in the household zone).
- `LocalWriter` writes one row per call, so `savePlan` from the current month writes the version first, then the
  month row (`table` null); the integration test of this case passes.
- The writer sends every field of a row with nulls as JSON null (a cleared amount or month table must travel).
  `editMonth` compares normalized tables and skips the write when there is no month copy and the table equals the
  plan; `resetMonth`, `clearIncome` and `unfund` write nothing when there is nothing to clear.
- `BudgetToday.notFunded` counts categories (not cells) with an unticked planned cell of a recorded income;
  `nextIncome` is the first unrecorded planned income dated today or later, else the earliest unrecorded one (M5-06).
- `isCategoryUsed` / `isIncomeUsed` count a funding row also when it is unticked.
- Integration tests: `Snapshot` has the four budget maps; `shared` leaves them out, since Kids hold none.

## UI (M5-04…M5-06)

Budget is the «Бюджет» tile in «Ещё» (FND-064), shown with `budget.view`. Strings in en and ru; amounts formatted with
a thin space and «₽» (`1 234 ₽`), no kopecks. Look per [visual style](../design/visual-style.md); the old
`Budget.dc.html` mockup shows the envelope concept and is superseded by this section (keep its hero card and list rows).

- **Budget screen (current month).** Top bar: month title with ‹ › and a tap opening «Все месяцы»; actions «План»
  and a menu (`budget.manage`): «Изменить этот месяц», «Вернуть как в плане» (when the month differs).
  Hero card (`displaySmall`): «Свободно 12 000 ₽», or «Недобор 12 000 ₽» when there is a shortfall and nothing free,
  or «Ожидается 346 000 ₽» before any income is recorded; one line under it: «Пришло 150 000 из 346 000 · переведено
  138 000». Then «Поступления»: one row per income — «5 декабря · 162 000 ₽», state «Ожидается» / «Пришло 150 000 ·
  4 дек», progress «Переведено 7 из 12», a check when all planned rows are ticked. Then «Категории»: name, «45 000 из
  60 000», progress bar, a dot «изменено» for month differences. Footer link «Добавить поступление» (extra income).
  Without a plan: empty state «Составьте план распределения» with «Создать план» (`budget.manage`) or a hint.
- **Income page.** Header «5 декабря · 162 000 ₽». Fields «Пришло» (amount, prefilled with the expected amount on
  first edit) and «Дата» (default today). List of its categories: name, planned amount, a shopping-style checkbox (it
  is a "done" mark, not a task); a tick writes the planned amount; tapping the amount opens a small sheet to change it
  («Перевели»), shown as «20 000 из 27 000». Button «Отметить все». Free money of this income at the bottom:
  «Не распределено 12 000 ₽» or «Переведено больше, чем пришло: 3 000 ₽».
- **Plan screen.** Summary bar: «Доход 346 000 · Категории 346 000 ✓» (a warning color and «не сходится на 6 000»
  otherwise). Segmented control «Категории | Поступления | Таблица».
  - Категории: rows with name, monthly amount and a warning icon for a bad row; unused ones in a collapsed «Не
    используются» group; drag handle to reorder; «Добавить категорию».
  - Category sheet: name, monthly amount, one amount field per income («5-е · из 162 000»), «Распределено 58 000 из
    60 000» live, switch «Не использовать», «Удалить» (only for a category that no earlier version, no month row and no funding uses; else only «Не
    использовать»).
  - Поступления: rows «5-е число · 162 000 ₽» with the column check; income sheet: day, expected amount, «Удалить»
    (only when no receipt or funding uses it, else the income can only be changed).
  - Таблица: read-only grid like the notebook, categories × incomes, sticky first column, horizontal scroll, bad sums
    in the warning color.
  - Editing is a local draft; «Сохранить» asks «С какого месяца действуют изменения?»: «С января» (default) / «С
    декабря (текущий месяц)». The second warns when the month has its own changes: «Разовые изменения декабря будут
    заменены планом. Отметки и суммы останутся». Leaving with unsaved changes asks to discard.
  - «История плана»: versions newest first, «С января 2027 · Али · 7 нояб.»; tap opens the read-only table of that
    version.
- **Month edit.** The plan screen in month mode: title «Только декабрь», no history, saving writes the month row;
  differences from the plan marked with a dot.
- **Today card** (BUD-021): «Бюджет» with the next expected income «20 декабря · 160 000 ₽» and «Не переведено: 5
  категорий»; tap opens the Budget screen. Placed after «Семья сейчас».

### Implementation notes (M5-04)

- `ui/budget/`: `BudgetFormat` (the one amount format: digits grouped by U+202F, «−» for negatives, then a no-break
  space and «₽»; input keeps up to 9 digits since M5-06, so a typed amount always fits the 1 000 000 000 limit), `BudgetLogic` (hero precedence and all row texts as `UiText`),
  `BudgetViewModel` (screen and «Все месяцы»), `IncomeViewModel`, `BudgetScreen`, `IncomePage`, `BudgetComponents`.
- Routes `BudgetDestination(month?)`, `BudgetIncomeDestination(month, incomeId)`, `BudgetMonthsDestination`. The month
  shown lives in the route's `rememberSaveable` (survives process death) and is passed to the view model; a month
  outside first month…current month falls back to the current one. Picking in «Все месяцы» replaces the Budget entry.
- The hero is the lake-gradient card (the one per screen); when free money takes the hero and the month also has a
  shortfall, a second line «Недобор N ₽» shows it. «Пришло … из … · переведено …» and «45 000 из 60 000» use digits
  without «₽»; titles and totals with «₽».
- Income page: the receipt fields are written on keyboard «Готово», when the amount field loses focus and when a date
  is picked; an emptied amount clears the receipt. Categories are split into «Перевести» (unticked, table order) and
  «Переведено» (ticked, with «Отметка: имя»). A category whose transfer differs from the planned cell shows
  «20 000 из 27 000». Extra incomes are listed on the month but have no page until M5-06.
- «Создать план», «План» and the month menu were hidden until M5-05 / M5-06; M5-05 added «План» and «Создать план».
- The «изменено» dot uses the `warning` color; category bars past 100 % use `error` on `errorContainer`.

### Implementation notes (M5-05)

- `ui/budget/plan/`: `PlanViewModel` (draft table, `TableCheck` on every change, sheets, save), `PlanLogic` (incomes by
  day, reorder, texts), `PlanScreen` (summary bar, segmented tabs, rows, save dialog), `PlanSheets` (category and
  income sheets), `PlanTable` (grid), `PlanHistoryPage` with `PlanHistoryViewModel`. Routes `BudgetPlanDestination
  (version?)` and `BudgetPlanHistoryDestination`; «План» is a text action in the month top bar for everyone with
  `budget.view` (read-only without `budget.manage`), «Создать план» in the empty state.
- The draft is the whole `BudgetTable` held by the view model; nothing is written until «Сохранить», which is enabled
  only when the draft differs from where it started. `PlanArgs.month` (month mode) loads the month's table and writes
  it with `editMonth` without the dialog; it is not routed until M5-06.
- Sheets edit a form and apply it to the draft on «Готово»; dismissing a sheet drops the form (a new income is already
  in the draft with day 1 and amount 0). Names are collapsed like the normalizer; an empty or duplicate name
  (case-insensitive) is refused in the sheet. An emptied amount field is 0; empty or 0 cells are no cells.
- «Удалить» is shown once `isCategoryUsed` / `isIncomeUsed` answer false for the default save month (the first save
  option): next month, or the current month for the very first plan. The draft's new rows are never used.
- Reorder: no list in the app has drag-to-reorder, so each category row has a menu with «Выше» / «Ниже», moving within
  its group (used or unused). Unused categories sit in a collapsed «Не используются» group.
- Summary bar: «Доход 346 000 · Категории 346 000» with a check when every row, column and the totals add up; else
  «не сходится на N» (|income − categories|, when they differ) and «Есть строки или столбцы, которые не сходятся».
- Category names in the list end with «…» after two lines (`ListRow(titleMaxLines)`); the table keeps one line.
- Table: sticky name column (128 dp), then «Сумма», the incomes by day («5-е» over the expected amount) and «Итого»;
  a totals row; bad row and column totals in `warning`; the numbers scroll sideways.
- Known limit: when a version from next month exists and the user saves the draft from the current month, the next
  month's version stays as it was (the draft started from it, so usually nothing is lost; the edits made now apply
  only to the current month).

### Implementation notes (M5-06)

- Month mode is `PlanViewModel` with `PlanArgs.month`, routed as `BudgetPlanDestination(month)`: title «Только
  декабрь» (string array `bud_months_only`), no history, «Сохранить» writes `editMonth` without the dialog.
  `PlanUiState.differences` compares the draft with the plan of that month (`BudgetMath.differences`, null when
  empty); `PlanLogic.categoryChanged` adds `removedCategories` (left out this month) to `BudgetLogic.categoryChanged`,
  `PlanLogic.incomeChanged` marks added and changed incomes. The dot is on category and income rows, table name cells
  and changed cells. «Удалить» in month mode only for ids the plan does not have and no receipt or tick of the month
  uses (`monthLocked`); else «Не использовать» («Не попадёт только в этот месяц, план не изменится»).
- `BudgetWriter.editMonth` compares tables up to the order of cells: the editor re-adds a category's cells at the end,
  so a draft set back to the plan's amounts is written as null.
- Budget screen: `BudgetUiState.monthChanged` = non-empty differences; the line under the hero opens month mode (plain
  text without `budget.manage`); the ⋮ menu (`budget.manage`) has «Изменить этот месяц» and, when changed, «Вернуть как
  в плане» with a confirmation. Planned incomes get the dot too.
- Extra incomes: «Добавить поступление» (link under «Поступления», `budget.manage`) opens `ExtraIncomeSheet` (amount
  first and required, optional name ≤ 60, date default today) and calls `addExtraIncome`. Their page is `IncomePage`
  with `IncomeArgs(month, null, receiptId)` (`BudgetIncomeDestination(month, receiptId = …)`): «Название», «Сумма»,
  «Дата» written like a receipt (an emptied amount is not written), no categories, «Удалить» with a confirmation; the
  page closes after the delete. `IncomeViewModel.startEdit` checks the local editing flag (the state may lag one
  update behind, which overwrote a typed label).
- Leaving an income page with a field still focused writes what was typed (back handler while editing, the top-bar
  arrow); the write runs `NonCancellable`, so it finishes after the view model is cleared. Amount fields keep 9 digits:
  with 10 a typed amount (e.g. digits inserted before the prefilled amount) could exceed the limit and be refused while
  the page was already closed. Known: the prefilled «Пришло» puts the cursor before the amount.
- Today card: `TodayViewModel(…, budget: BudgetRepository?)` maps `BudgetViews.today` to `TodayBudgetUi`; `build`
  drops it without `budget.view`; `TodayBudget` after «Семья сейчас» is one row (wallet icon, chevron) with
  `BudgetLogic.todayLines`: the next income, else «Не переведено: N категорий», else «Всё распределено»; «Не
  переведено» is the second line under an income. The card alone keeps Today out of its empty state.

## Invariants to test

- Two phones tick different categories of one income offline → both ticks survive; the same category → one row, last
  writer wins.
- Two phones record the same income offline → one receipt row.
- A version saved "from next month" does not change the current month's view; "from this month" does, and keeps receipts
  and ticks.
- A plan write for a past month is rejected on the server and on the phone.
- A member without `budget.view` gets no budget rows; one with only `budget.view` can read but every write is
  `FORBIDDEN`.
