---
status: approved  # customer, 2026-10-23 (Q-014…Q-017 answered the same day)
milestone: M1
updated: 2026-10-23
---
# Tasks (TSK)

## Summary

Shared to-dos and recurring chores. Anyone with the right can create a task, assign it, and close it. Recurring tasks
(routines) show only their current occurrence; the next one appears when it is completed or its date passes.
Technical design: [architecture/tasks.md](../../architecture/tasks.md).

## Scenarios

1. Mom creates "Pay for music school" due Friday, assigns Dad, tags "Kids". Dad gets a notification.
2. A routine "Take out trash" repeats Mon/Thu at 20:00, assigned to the two kids. Whoever does it first ticks it off;
   the next occurrence appears for Thursday.
3. "Replace water filter" repeats 3 months **after the last completion**, not on a fixed date.
4. Dad opens "My tasks" and sees today's and overdue tasks; completes one; Mom sees it done immediately.
5. Nobody took out the trash on Monday: Mom (the creator) gets "Task overdue" in the evening. On Thursday Monday's
   occurrence disappears and Thursday's is current — the list never shows a pile of missed ones.
6. "Weekly cleaning" has a checklist (vacuum, dust, mop); the kids tick items during the week; next week the checklist
   starts unticked again.

## Requirements

### Basics

- **TSK-001** `MVP` `todo` — Create a task: title (required), notes, due date (optional time), assignees, tags.
  *Server: storage, sync, validation and permissions done (M1-02).*
- **TSK-002** `MVP` `todo` — Assignee options: specific members, or "anyone" (no assignees). One completion closes the
  task (or the occurrence) for everybody, also when several members are assigned (Q-014).
  *Server: assignees checked against household members (M1-02).*
- **TSK-003** `MVP` `todo` — Task states: open, done. Completion records who and when. Reopen is possible
  (by the person who completed it, the creator, or a member with "Edit others' tasks").
  *Server: completions (immutable, undo deletes) and their permissions done (M1-02).*
- **TSK-004** `MVP` `todo` — Checklist inside a task (sub-items with their own done flag). In a routine the checklist starts
  unticked for every occurrence.
  *Server: checklist items; ticking needs completion rights, other changes edit rights (M1-02).*
- **TSK-005** `MVP` `todo` — Edit and delete tasks; deleting a routine asks "this occurrence or the whole series"
  ("this occurrence" = skip it, the next one becomes current).
  *Server: edit, delete with its checklist and completions, skip as a completion (M1-02).*
- **TSK-006** `later` `todo` — Comments and photo attachments on a task (photos in M5b, [files](files.md); comments later).
- **TSK-007** `MVP` `todo` — Undo right after completing (snackbar), and "Mark as not done" later from the task.

### Recurring tasks (routines)

- **TSK-010** `MVP` `todo` — Fixed schedules: every N days / every N weeks on chosen weekdays / every N months on the day of
  the start date / every N years. Day 31 in shorter months falls on the last day of the month.
- **TSK-011** `MVP` `todo` — "After completion" schedules: next occurrence N days/weeks/months after the last completion.
- **TSK-012** `MVP` `todo` — Only the current occurrence is open; the next one appears when it is completed (or skipped) or,
  for fixed schedules, when its date comes.
- **TSK-013** `MVP` `todo` — Missed occurrences are visible as overdue and do not pile up: at most one overdue per routine;
  when the next scheduled date comes, the missed one is dropped.
- **TSK-014** `later` `todo` — Rotation: assignees of a routine take turns in a set order.
- **TSK-015** `MVP` `todo` — Completion history per routine (who did it and when; skipped occurrences marked).
  *Server: completions with author, time, occurrence and skip flag are kept and synced (M1-02).*
- **TSK-016** `MVP` `todo` — A routine can be completed early (before its date); for "after completion" schedules the next
  date counts from the actual completion.

### Views

- **TSK-020** `MVP` `todo` — "My tasks": assigned to me or "anyone", grouped overdue / today / upcoming / no date.
  Tasks completed today stay visible, ticked, at the end of "Today".
- **TSK-021** `MVP` `todo` — "All tasks" with filters: assignee, tag, state (open / done); "Assigned by me" (created by me
  for others); search by title.
- **TSK-022** `MVP` `todo` — Tasks with a due date also appear in the calendar agenda (after M3).
- **TSK-023** `MVP` `todo` — "Today" home screen block: my overdue and today's tasks, ticking them off right there (FND-063).
- **TSK-024** `MVP` `todo` — Task page: all fields, checklist, history (routines), who created it.

### Reminders and notifications

- **TSK-030** `MVP` `todo` — A task can have a reminder (at due time or N minutes/hours/days before). It fires on the phones of
  the assignees; for "anyone" tasks on the phones of everybody who sees tasks (Q-015). Tasks without a time
  remind at 09:00 on the day (or N days before at 09:00) (Q-016).
- **TSK-031** `MVP` `todo` — Assignees get a notification when a task is assigned to them (not for "anyone" tasks, not to the
  person who assigned it).
  *Server: sent after commit to added assignees with `tasks.view` (M1-03).*
- **TSK-032** `MVP` `todo` — The creator can opt in to a notification when the task is completed by someone else.
  *Server: sent for the first non-skipped completion of an occurrence (M1-03).*
- **TSK-033** `MVP` `todo` — When a task becomes overdue (due time passed, not done), its creator gets a notification.
  Detected on the server, so it works while the assignee's phone is offline. Tasks without a time become overdue after
  their day; the notice comes the next morning at 09:00 (Q-016). No notice if the date was already past when
  the task was saved.
  *Server: overdue job every 60 s, one notice per occurrence, none older than 24 h (M1-03).*
- **TSK-034** `MVP` `todo` — Each notification type can be turned off in Settings › Notifications: task assigned, task
  completed, task overdue, task reminders (FND-052).

### Tags

- TAG-002 (tasks part), TAG-003 (filter in Tasks) and TAG-004 (tag page with the tasks section) are delivered in M1.

## Permissions and privacy

Everybody with access to Tasks (`tasks.view`) sees all tasks of the family. Members without it do not receive task data at
all. Permissions ([catalogue](foundation.md#permission-catalogue)): `tasks.view`, `tasks.create`, `tasks.edit_any`.

| Action | Who |
| --- | --- |
| See tasks | `tasks.view` |
| Create a task | `tasks.create` |
| Edit, reassign, delete a task; edit its checklist items | the creator, or `tasks.edit_any` |
| Complete, skip an occurrence, tick checklist items | assignees; everybody for "anyone" tasks; the creator and `tasks.edit_any` too (Q-017) |
| Mark as not done (undo) | whoever completed it, the creator, or `tasks.edit_any` |

Templates: Adults have all three; Kids have `tasks.view` and `tasks.create` (they can create tasks and complete their own,
but not change others'). Administrators have everything.

Notification texts contain the task title; they travel encrypted through the push service (ADR-0006).

## Data

Task (with recurrence rule, assignees and tags), checklist item, completion. Occurrences are computed, not stored
([ADR-0017](../../adr/0017-task-occurrences-derived-from-completions.md)); tags are ids inside the task
([ADR-0018](../../adr/0018-tags-embedded-in-entities.md)). Dates and times are in the family's time zone.

## Customer decisions (2026-10-23)

- Q-014 — several assignees: one completion closes the task for all.
- Q-015 — reminders go to assignees; for "anyone" tasks to everyone with access to tasks.
- Q-016 — tasks without a time: reminder at 09:00, overdue notice at 09:00 the next day.
- Q-017 — besides assignees, the creator and members with "Edit others' tasks" can tick a task off.

## Assumptions
- A routine cannot be turned back into a one-off task; delete the series and create a new task instead.
- Moving a single occurrence of a routine to another day is not offered in M1 (skip it or change the start date).

## Out of scope (M1)

- Points / rewards (declined).
- Parent approval of completed tasks (not requested, 2026-10-22).
- Rotation (TSK-014, later).
- Private tasks visible only to the creator.
- Time tracking, task dependencies, subtasks beyond the checklist, reordering checklist items by drag and drop.
- End date or count for routines ("until", "10 times").
