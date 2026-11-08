-- Budget module (M5-02, architecture/budget.md § Server › Tables, ADR-0032): plan versions, month-only copies, actual
-- incomes and funding ticks. Synced to members with budget.view. Months are text "YYYY-MM"; tables are JSON
-- BudgetTable text ("table" is reserved in SQL). Ids are derived (ADR-0032), except extra-income receipts.

-- One plan version in force from effective_from (BUD-004). Upsert only; past versions are read-only.
CREATE TABLE budget_plan (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  effective_from text NOT NULL,
  plan_table text NOT NULL,
  updated_by uuid NOT NULL REFERENCES member(id),
  -- BudgetPlanData.updatedAt as the writer sent it (the plan history).
  saved_at timestamptz NOT NULL,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX budget_plan_household_seq ON budget_plan (household_id, seq);

-- Month-only changes (BUD-013); a null month_table means the month follows its plan version. Upsert only.
CREATE TABLE budget_month (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  month text NOT NULL,
  month_table text NULL,
  updated_by uuid NOT NULL REFERENCES member(id),
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX budget_month_household_seq ON budget_month (household_id, seq);

-- An actual income of a month (BUD-011): a planned income (derived id, upsert only) or an extra one (income_id null,
-- random id, deletable).
CREATE TABLE budget_receipt (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  month text NOT NULL,
  income_id uuid NULL,
  label text NULL,
  amount bigint NULL,
  date date NULL,
  updated_by uuid NOT NULL REFERENCES member(id),
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX budget_receipt_household_seq ON budget_receipt (household_id, seq);

-- One funding tick: a category's money from one income in one month (BUD-012). Upsert only; a null amount is unticked.
CREATE TABLE budget_funding (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  month text NOT NULL,
  income_id uuid NOT NULL,
  category_id uuid NOT NULL,
  amount bigint NULL,
  funded_by uuid NULL REFERENCES member(id),
  funded_at timestamptz NULL,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX budget_funding_household_seq ON budget_funding (household_id, seq);
