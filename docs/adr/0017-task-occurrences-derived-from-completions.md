---
status: accepted
date: 2026-10-23
---
# ADR-0017: Routine occurrences are derived from completions, not stored

## Context

Tasks (M1) include routines: fixed schedules (TSK-010) and "N days after the last completion" (TSK-011). Only the current
occurrence is open (TSK-012); missed ones must not pile up (TSK-013); completion history is kept (TSK-015). The client is
offline-first with field-level last-writer-wins sync (ADR-0008, [sync.md](../architecture/sync.md)).

Typical conflict: two children complete the same "Take out trash" occurrence offline, or one completes Monday's
occurrence while another, offline since Sunday, completes it later. If completing a routine **moved the task's due date**,
the later arrival could move it back (LWW on `dueDate`) and an already completed occurrence would reappear. Fixing that
would need domain operations with server-side side effects and an optimistic client overlay — new sync machinery.

## Decision

1. A routine is a task with a `recurrence` and a start date (`dueDate` is the anchor). Occurrences are not rows.
2. Completing or skipping an occurrence **creates** an immutable `task_completion` (task id, author, time, occurrence date,
   `skipped`). Undo / reopen **deletes** it. Completing never writes to the task row.
3. The current occurrence and every derived state (done, overdue, list group, reminder time, checklist reset) are computed
   by one pure function in `contract/` (`TaskSchedule`), identical on phones and server, from the task, its completions
   and the current date. Rules: [tasks design § schedule logic](../architecture/tasks.md#schedule-logic-contracttasks).
4. Dates and times of tasks are local dates and wall-clock times in the **household time zone**. Instants are used only for
   completion times.
5. A one-off task is done when any non-skipped completion exists. A routine cannot be turned into a one-off (delete the
   series instead); a one-off can become a routine.
6. Per-occurrence edits (moving one occurrence to another day) are not supported in M1; skipping and moving the start date
   are.

## Consequences

- Concurrent completions converge without conflict: both are kept in the history, the current occurrence is the same on
  every device. No new sync operations; the protocol stays as accepted.
- Undo works offline and immediately (delete the completion).
- The server needs the same logic for the overdue notification (TSK-033); it keeps a server-only `overdue_check_at`
  column so the job does not scan every task.
- Views are computed in Kotlin on the phone (households have hundreds of tasks at most); a minute ticker refreshes overdue
  states.
- Duplicate completions (two people tapping the same occurrence) both appear in the history; "done by" shows the first.

## Alternatives considered

- **Task row holds the current due date; completion advances it** — needs conditional server-side updates (domain ops) and
  an optimistic overlay on the client; LWW otherwise loses or resurrects occurrences.
- **One row per occurrence** (pre-generated or created on completion) — duplicates when two offline devices generate the
  next occurrence; needs dedup keys and cleanup; edits of the series must be copied to the open occurrence.
- **Store the state on the task and let a server job roll routines** — the phone would show wrong state while offline.
