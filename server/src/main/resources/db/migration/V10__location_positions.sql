-- Location module, part 2 (M4-03, architecture/location.md § Server › Tables, ADR-0024): positions outside the sync log
-- and each member's sharing state with the newest position.

-- Positions uploaded by the sharing phone (LOC-001). Append-only; deleted after the household's retention (LOC-005).
-- A re-upload of the same fix is ignored (member + device time).
CREATE TABLE position (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  member_id uuid NOT NULL REFERENCES member(id),
  session_id uuid NULL,
  recorded_at timestamptz NOT NULL,
  latitude double precision NOT NULL,
  longitude double precision NOT NULL,
  accuracy_m real NOT NULL,
  speed_mps real NULL,
  bearing_deg real NULL,
  altitude_m double precision NULL,
  battery smallint NULL,
  charging boolean NULL,
  received_at timestamptz NOT NULL,
  CONSTRAINT position_member_recorded UNIQUE (member_id, recorded_at)
);
CREATE INDEX position_household_recorded ON position (household_id, recorded_at);

-- One row per member who ever set a sharing state. Status, off reason: wire names of SharingStatus / OffReason.
-- last_*: the newest stored fix by recorded_at (kept while paused or off; cleared by retention).
CREATE TABLE location_state (
  member_id uuid PRIMARY KEY REFERENCES member(id),
  household_id uuid NOT NULL REFERENCES household(id),
  status text NOT NULL,
  paused_until timestamptz NULL,
  off_reason text NULL,
  status_since timestamptz NOT NULL,
  -- The one session that shares (LOC-019); uploads from other sessions are told to stop.
  sharing_session_id uuid NULL,
  last_recorded_at timestamptz NULL,
  last_latitude double precision NULL,
  last_longitude double precision NULL,
  last_accuracy_m real NULL,
  last_speed_mps real NULL,
  last_bearing_deg real NULL,
  last_altitude_m double precision NULL,
  last_battery smallint NULL,
  last_charging boolean NULL,
  -- «Обновить» (LOC-011): server times only.
  refresh_requested_at timestamptz NULL,
  refresh_sent_at timestamptz NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX location_state_household ON location_state (household_id);
