---
status: accepted
date: 2026-11-07
---
# ADR-0032: The budget plan is versioned by month; months, receipts and ticks are rows with derived ids

## Context

The budget (M5, [spec](../product/features/budget.md)) is a table of categories × incomes that changes rarely and must
keep its history, plus per-month data that several phones write offline: the actual amount of each income and the
ticks of funded categories. A month may also differ from the plan for that month only. Sync is field-level
last-writer-wins in server arrival order and a tombstoned id cannot be written again
([sync](../architecture/sync.md)); ADR-0022 solved the same shape for calendar occurrences with derived ids.

## Decision

1. A **plan version** is one `budget_plan` row holding the whole table (categories, incomes, cells) as one JSON field and
   `effectiveFrom` (a year-month). The version in force for a month is the one with the greatest `effectiveFrom` not
   after it. Saving the plan writes the version of the chosen month; versions of past months are read-only (server
   and phone check).
2. Month-only edits are a `budget_month` row with a full copy of the table (null = follows the plan). Nothing is written
   to open a month.
3. The actual amount of a planned income in a month is a `budget_receipt` row; one tick is a `budget_funding` row per
   month × income × category. Extra incomes are receipt rows without an income.
4. Plan, month, planned receipt and funding rows have ids **derived** from the household (plan, month) or the income
   and category ids and the month, with the ADR-0022 algorithm and new byte-7 markers. They are upsert-only; clearing
   writes nulls. Only extra-income receipts have random ids and may be deleted.
5. All sums, checks and differences are computed on the phone by shared code (`BudgetMath`); the server stores and
   checks fields only.

## Consequences

- Two phones recording the same income or ticking the same category offline write one row; ticks of different
  categories never collide.
- Two concurrent edits of the plan (or of one month's copy) do not merge: the later save wins as a whole. Acceptable:
  the plan changes rarely and is edited by one person at a time.
- Category and income ids are stable across versions, so months and later analytics (Q-033) follow a category through
  renames.
- Up to ≈ 80 funding rows a month; small.

## Alternatives considered

- **Categories, incomes and cells as separate rows with validity periods** — history and "from which month" need
  interval logic on every row and many writes per plan change; concurrent edits merge better, which the plan does not
  need.
- **Ticks embedded in the month row** — field-level LWW would lose one of two concurrent ticks.
- **Month row with a copy of the plan written when the month opens** — two phones opening a month offline would race,
  and a late open could overwrite month edits.
