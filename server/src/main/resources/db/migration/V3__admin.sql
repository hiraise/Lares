-- Household administration (M0-04): members without a login, invites, one-time login codes (architecture/auth.md).

-- Members created by an administrator may have no login (QR login only). The UNIQUE constraint stays:
-- PostgreSQL treats NULLs as distinct, so it applies to non-null logins only.
ALTER TABLE user_account ALTER COLUMN login DROP NOT NULL;

-- Codes are stored as SHA-256 of the normalized code (Codes.normalize); the plain code is shown once.
CREATE TABLE invite (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  code_hash bytea NOT NULL UNIQUE,
  -- JSON array of group ids; groups deleted after the invite was created are skipped on accept.
  group_ids text NOT NULL,
  -- Optional relative (person) that the accepting user becomes (FND-032).
  person_id uuid NULL REFERENCES person(id),
  created_by_member_id uuid NOT NULL REFERENCES member(id),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at timestamptz NULL,
  used_by_member_id uuid NULL REFERENCES member(id),
  revoked_at timestamptz NULL
);
CREATE INDEX invite_household ON invite (household_id);

CREATE TABLE login_code (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  member_id uuid NOT NULL REFERENCES member(id),
  code_hash bytea NOT NULL UNIQUE,
  created_by_member_id uuid NOT NULL REFERENCES member(id),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at timestamptz NULL
);
