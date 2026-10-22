-- Household tenancy (ADR-0010) and the per-household change sequence (architecture/sync.md).

CREATE TABLE household (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  time_zone text NOT NULL,
  currency char(3) NOT NULL,
  default_language text NOT NULL,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);

CREATE TABLE household_sync_state (
  household_id uuid PRIMARY KEY REFERENCES household(id),
  last_seq bigint NOT NULL DEFAULT 0,
  purge_horizon_seq bigint NOT NULL DEFAULT 0,
  sync_epoch uuid NOT NULL
);
