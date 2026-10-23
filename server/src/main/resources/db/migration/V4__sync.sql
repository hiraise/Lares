-- Sync (M0-05): tags, the mutation idempotency log, personal colors (architecture/sync.md, FND-034, TAG-001).

CREATE TABLE tag (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  name text NOT NULL,
  -- PaletteColor wire name, e.g. 'blue'.
  color text NOT NULL,
  icon text NULL,
  archived boolean NOT NULL DEFAULT false,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX tag_household_seq ON tag (household_id, seq);
-- TAG-006: names are unique among non-deleted tags of a household, case-insensitive; archived tags count.
CREATE UNIQUE INDEX tag_household_name ON tag (household_id, lower(name)) WHERE deleted = false;

-- Every processed mutation (applied or rejected), so a retry returns the same result. Kept 30 days.
CREATE TABLE sync_mutation_log (
  household_id uuid NOT NULL REFERENCES household(id),
  mutation_id uuid NOT NULL,
  member_id uuid NOT NULL,
  status text NOT NULL,
  seq bigint NULL,
  error_code text NULL,
  processed_at timestamptz NOT NULL,
  PRIMARY KEY (household_id, mutation_id)
);
CREATE INDEX sync_mutation_log_processed_at ON sync_mutation_log (processed_at);

-- PaletteColor wire name; NULL for persons created before personal colors (clients use PaletteColors.fallbackFor).
ALTER TABLE person ADD COLUMN color text NULL;
