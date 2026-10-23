-- Identity and household membership (M0-03): users, persons, members, groups, sessions.
-- Syncable tables carry seq / created_at / updated_at / deleted and an index on (household_id, seq) (architecture/sync.md).

CREATE TABLE user_account (
  id uuid PRIMARY KEY,
  login text NOT NULL UNIQUE,
  password_hash text NULL,
  created_at timestamptz NOT NULL,
  deleted_at timestamptz NULL
);

CREATE TABLE person (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  name text NOT NULL,
  birth_year int NULL,
  birth_month int NULL,
  birth_day int NULL,
  relation text NULL,
  notes text NULL,
  contacts text NOT NULL DEFAULT '[]',
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX person_household_seq ON person (household_id, seq);

CREATE TABLE member (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  user_id uuid NULL REFERENCES user_account(id),
  person_id uuid NOT NULL REFERENCES person(id),
  access_version bigint NOT NULL DEFAULT 1,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX member_household_seq ON member (household_id, seq);
CREATE UNIQUE INDEX member_user_household ON member (user_id, household_id) WHERE deleted = false;

CREATE TABLE group_entity (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  name text NOT NULL,
  built_in text NULL,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX group_entity_household_seq ON group_entity (household_id, seq);

-- Permissions and memberships are part of the owning group's sync payload: changing them bumps the group's seq.
CREATE TABLE group_permission (
  group_id uuid NOT NULL REFERENCES group_entity(id) ON DELETE CASCADE,
  permission text NOT NULL,
  PRIMARY KEY (group_id, permission)
);

CREATE TABLE group_membership (
  group_id uuid NOT NULL REFERENCES group_entity(id) ON DELETE CASCADE,
  member_id uuid NOT NULL REFERENCES member(id),
  PRIMARY KEY (group_id, member_id)
);
CREATE INDEX group_membership_member ON group_membership (member_id);

-- One logged-in app installation. Rows are never deleted: ending a session sets revoked_at (architecture/auth.md).
CREATE TABLE session (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES user_account(id),
  member_id uuid NOT NULL REFERENCES member(id),
  household_id uuid NOT NULL REFERENCES household(id),
  device_name text NOT NULL,
  platform text NOT NULL,
  created_at timestamptz NOT NULL,
  last_seen_at timestamptz NOT NULL,
  access_hash bytea NOT NULL UNIQUE,
  access_expires_at timestamptz NOT NULL,
  refresh_hash bytea NOT NULL UNIQUE,
  refresh_expires_at timestamptz NOT NULL,
  previous_refresh_hash bytea NULL,
  rotated_at timestamptz NULL,
  access_used_since_rotation boolean NOT NULL DEFAULT false,
  revoked_at timestamptz NULL
);
CREATE INDEX session_previous_refresh_hash ON session (previous_refresh_hash);
CREATE INDEX session_user_id ON session (user_id);
