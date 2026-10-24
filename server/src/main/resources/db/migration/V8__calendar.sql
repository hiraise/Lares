-- Calendar module (M3-02, architecture/calendar.md § Server › Tables): events, changed occurrences, personal reminders,
-- and the member's birthday reminder lead time.

-- An event or a memorable date (CAL-001…CAL-005, CAL-015, ADR-0022). Dates and times are household wall-clock values.
CREATE TABLE event (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  created_by uuid NOT NULL REFERENCES member(id),
  title text NOT NULL,
  start_date date NOT NULL,
  end_date date NOT NULL,
  all_day boolean NOT NULL DEFAULT false,
  start_time time NULL,
  end_time time NULL,
  place text NULL,
  notes text NULL,
  -- JSON arrays of person and tag ids (like task.assignee_ids); empty participants = the whole family.
  participant_ids text NOT NULL DEFAULT '[]',
  tag_ids text NOT NULL DEFAULT '[]',
  -- JSON of Recurrence (mode fixed); NULL = one-off event.
  recurrence text NULL,
  repeat_until date NULL,
  reminder_minutes_before int NULL,
  -- Wire name: EventKind.key.
  kind text NOT NULL DEFAULT 'event',
  count_years boolean NOT NULL DEFAULT false,
  -- "This and following": the series this one was cut from. No foreign key: the origin may be purged later.
  split_from uuid NULL,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX event_household_seq ON event (household_id, seq);

-- A changed or cancelled occurrence of a repeating event (CAL-003). The id is derived from event id and occurrence.
CREATE TABLE event_occurrence (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  event_id uuid NOT NULL REFERENCES event(id) ON DELETE CASCADE,
  occurrence date NOT NULL,
  cancelled boolean NOT NULL DEFAULT false,
  -- NULL = as the series.
  start_date date NULL,
  end_date date NULL,
  start_time time NULL,
  end_time time NULL,
  title text NULL,
  place text NULL,
  notes text NULL,
  participant_ids text NULL,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX event_occurrence_household_seq ON event_occurrence (household_id, seq);
CREATE INDEX event_occurrence_event ON event_occurrence (event_id);

-- One member's own reminder for an event (CAL-006, ADR-0023): synced only to that member.
CREATE TABLE event_reminder (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  event_id uuid NOT NULL REFERENCES event(id) ON DELETE CASCADE,
  member_id uuid NOT NULL REFERENCES member(id),
  -- NULL = no reminder for this member.
  minutes_before int NULL,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX event_reminder_household_seq ON event_reminder (household_id, seq);
CREATE INDEX event_reminder_event ON event_reminder (event_id);

-- Per-member notification preferences that are not a mute switch (CAL-016). Missing row = defaults.
CREATE TABLE notification_preference (
  member_id uuid PRIMARY KEY REFERENCES member(id) ON DELETE CASCADE,
  birthday_days_before int NOT NULL DEFAULT 3
);
