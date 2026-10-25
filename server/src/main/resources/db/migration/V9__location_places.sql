-- Location module, part 1 (M4-02, architecture/location.md § Server › Tables): family places, arrive / leave alerts,
-- visibility rules and the history retention setting. Positions come in V10.

-- A family place (LOC-020): a circle on the map. Synced to members with location.view.
CREATE TABLE place (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  name text NOT NULL,
  -- Wire name: PlaceIcon.key.
  icon text NOT NULL DEFAULT 'place',
  latitude double precision NOT NULL,
  longitude double precision NOT NULL,
  radius_m int NOT NULL,
  address text NULL,
  created_by uuid NOT NULL REFERENCES member(id),
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX place_household_seq ON place (household_id, seq);

-- One subscriber's arrive / leave alerts about one member (LOC-021, ADR-0023 pattern): synced only to the subscriber.
-- The id is derived from member and subscriber.
CREATE TABLE place_alert (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  member_id uuid NOT NULL REFERENCES member(id),
  subscriber_id uuid NOT NULL REFERENCES member(id),
  -- JSON array of place ids; ids of later deleted places stay and are ignored by readers.
  place_ids text NOT NULL DEFAULT '[]',
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX place_alert_household_seq ON place_alert (household_id, seq);
CREATE INDEX place_alert_member ON place_alert (member_id);

-- Who sees whose location (LOC-006, ADR-0025). Server only, not syncable. Kinds and levels are wire names
-- (PartyKind.key, VisibilityLevel.key). Groups and members are tombstoned, and live deletes remove their rules
-- explicitly; the cascades only matter when housekeeping hard-deletes old tombstones.
CREATE TABLE location_rule (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  subject_kind text NOT NULL,
  subject_group_id uuid NULL REFERENCES group_entity(id) ON DELETE CASCADE,
  subject_member_id uuid NULL REFERENCES member(id) ON DELETE CASCADE,
  target_kind text NOT NULL,
  target_group_id uuid NULL REFERENCES group_entity(id) ON DELETE CASCADE,
  target_member_id uuid NULL REFERENCES member(id) ON DELETE CASCADE,
  level text NOT NULL,
  -- Order of the list the administrator saved.
  position int NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX location_rule_household ON location_rule (household_id);

-- Days of location history kept (LOC-005): one of 7, 14, 30, 60, 90.
ALTER TABLE household ADD COLUMN location_history_days int NOT NULL DEFAULT 30;

-- Default rules for existing households (ADR-0025): Adults see everybody with history, Kids see Adults now. Template
-- groups carry no marker, so they are found by name; the oldest live match wins; without both groups nothing is added.
-- lower() of the literal as well, so the exact template names match even where lower() leaves Cyrillic unchanged.
WITH adults AS (
  SELECT DISTINCT ON (household_id) household_id, id
  FROM group_entity
  WHERE NOT deleted AND lower(name) IN ('adults', 'взрослые', lower('Взрослые'))
  ORDER BY household_id, created_at, id
), kids AS (
  SELECT DISTINCT ON (household_id) household_id, id
  FROM group_entity
  WHERE NOT deleted AND lower(name) IN ('kids', 'дети', lower('Дети'))
  ORDER BY household_id, created_at, id
)
INSERT INTO location_rule (id, household_id, subject_kind, subject_group_id, subject_member_id, target_kind,
                           target_group_id, target_member_id, level, position, created_at)
SELECT gen_random_uuid(), a.household_id, 'group', a.id, NULL::uuid, 'everyone', NULL::uuid, NULL::uuid, 'history', 0, now()
FROM adults a JOIN kids k ON k.household_id = a.household_id
UNION ALL
SELECT gen_random_uuid(), a.household_id, 'group', k.id, NULL::uuid, 'group', a.id, NULL::uuid, 'current', 1, now()
FROM adults a JOIN kids k ON k.household_id = a.household_id;
