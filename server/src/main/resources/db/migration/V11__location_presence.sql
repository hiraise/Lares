-- Location module, part 3 (M4-04, architecture/location.md § Server › Tables, LOC-022): who is in which place since when.

-- One row per member and live place, created lazily by the first usable position. inside NULL = unknown (new place,
-- new member, place moved); candidate = a reading that differs from inside, waiting for a second one (hysteresis).
-- Deleted with the place (tombstone, purge) and with the member (MemberRemoval).
CREATE TABLE place_presence (
  member_id uuid NOT NULL REFERENCES member(id),
  place_id uuid NOT NULL REFERENCES place(id) ON DELETE CASCADE,
  household_id uuid NOT NULL REFERENCES household(id),
  inside boolean NULL,
  since timestamptz NULL,
  candidate boolean NULL,
  candidate_since timestamptz NULL,
  PRIMARY KEY (member_id, place_id)
);
CREATE INDEX place_presence_place ON place_presence (place_id);
