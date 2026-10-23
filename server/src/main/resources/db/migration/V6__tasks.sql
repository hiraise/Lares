-- Tasks module (M1-02, architecture/tasks.md § Server › Tables): tasks and routines, checklist items, completions.

-- A task or routine (TSK-001…TSK-011). Dates and times are household wall-clock values.
CREATE TABLE task (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  title text NOT NULL,
  notes text NULL,
  created_by uuid NOT NULL REFERENCES member(id),
  due_date date NULL,
  due_time time NULL,
  -- JSON arrays of member and tag ids (like person.contacts); empty assignees = "anyone".
  assignee_ids text NOT NULL DEFAULT '[]',
  tag_ids text NOT NULL DEFAULT '[]',
  -- JSON of Recurrence; NULL = one-off task.
  recurrence text NULL,
  reminder_minutes_before int NULL,
  notify_creator_on_done boolean NOT NULL DEFAULT false,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false,
  -- Server-only, never synced: the overdue job (M1-03).
  overdue_check_at timestamptz NULL,
  overdue_notified_occurrence date NULL
);
CREATE INDEX task_household_seq ON task (household_id, seq);
CREATE INDEX task_overdue_check ON task (overdue_check_at) WHERE deleted = false AND overdue_check_at IS NOT NULL;

-- A checklist item of a task (TSK-004).
CREATE TABLE checklist_item (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  task_id uuid NOT NULL REFERENCES task(id) ON DELETE CASCADE,
  text text NOT NULL,
  position int NOT NULL,
  done boolean NOT NULL DEFAULT false,
  done_occurrence date NULL,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX checklist_item_household_seq ON checklist_item (household_id, seq);
CREATE INDEX checklist_item_task ON checklist_item (task_id);

-- A completed or skipped occurrence (TSK-003, ADR-0017). Immutable: undo deletes it.
CREATE TABLE task_completion (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES household(id),
  task_id uuid NOT NULL REFERENCES task(id) ON DELETE CASCADE,
  completed_by uuid NOT NULL REFERENCES member(id),
  completed_at timestamptz NOT NULL,
  occurrence date NULL,
  skipped boolean NOT NULL DEFAULT false,
  seq bigint NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX task_completion_household_seq ON task_completion (household_id, seq);
CREATE INDEX task_completion_task ON task_completion (task_id);
