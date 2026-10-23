---
status: outline
milestone: M5
updated: 2026-10-22
---
# Budget (BUD)

## Summary

Envelope-style family budget. Income is distributed into categories by rules; spending draws from categories.
Overspent categories carry a deficit that is repaid from future income; categories can lend to each other;
real debts (loans, mortgage, money borrowed from relatives) have their own balance and payment schedule.

## Concepts (confirmed by the customer, 2026-10-22)

1. **Allocation rules** — when income arrives, it is split across categories by an ordered rule set.
2. **Category deficit** — spending beyond a category's balance creates a deficit, repaid first from future allocations.
3. **Inter-category loan** — "Car" borrows from "Vacation"; the loan is tracked until repaid.
4. **Real debts** — credits, mortgage, personal loans, linked to a category, with a payment schedule.

## Requirements (outline)

### Accounts and transactions

- **BUD-001** `MVP` `todo` — Money accounts (cash, card, savings) with balance; access per account for members/groups (view, edit),
  set by members with `budget.manage`. Administrators see all accounts (ADR-0014).
- **BUD-002** `MVP` `todo` — Transactions: expense, income, transfer between accounts; amount, date, category, note, tags, who.
- **BUD-003** `MVP` `todo` — Quick expense entry in a few taps; works offline.

### Categories and allocation

- **BUD-010** `MVP` `todo` — Categories (envelopes) with a current balance; optional monthly target.
- **BUD-011** `MVP` `todo` — Allocation rule sets per income source: ordered rules, each a fixed amount or a percent of the remainder;
  the remainder stays unallocated or goes to a chosen category.
- **BUD-012** `MVP` `todo` — Recording income proposes an allocation by rules; the user can adjust before confirming.
- **BUD-013** `MVP` `todo` — Unallocated money is visible and can be assigned manually.

### Deficits and loans

- **BUD-020** `MVP` `todo` — A negative category balance is a deficit and is carried to the next period.
- **BUD-021** `MVP` `todo` — Allocation repays deficits first (configurable order).
- **BUD-022** `MVP` `todo` — Inter-category loan: amount moved from category A to B with an outstanding balance; repayment by transfer back or from B's allocations.

### Debts

- **BUD-030** `MVP` `todo` — Debt: direction (we owe / owed to us), counterparty (bank or person), principal, optional rate, linked category.
- **BUD-031** `MVP` `todo` — Payment schedule; upcoming payments appear as reminders and in the calendar agenda.
- **BUD-032** `MVP` `todo` — A payment transaction reduces the debt balance.

### Reports

- **BUD-040** `MVP` `todo` — Spending by category and by tag over a period; income vs expenses per month.
- **BUD-041** `MVP` `todo` — Overview: category balances, deficits, open loans, debts.

### Later

- **BUD-050** `later` `todo` — CSV import of bank statements.
- **BUD-051** `later` `todo` — Receipt photos (M5b, [files](files.md)).
- **BUD-052** `later` `todo` — Multi-currency accounts. M5 uses one household currency.

## Notes

- Money is stored as integer minor units plus ISO 4217 currency code; never floating point.

## Open points

Detailed design (periods, rollover semantics, rule evaluation, rounding) before M5.
