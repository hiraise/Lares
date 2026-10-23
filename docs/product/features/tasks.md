---
status: draft
milestone: M1
updated: 2026-10-22
---
# Tasks (TSK)

## Summary

Shared to-dos and recurring chores. Anyone can create a task, assign it, and close it. Recurring tasks
(routines) generate the next occurrence automatically.

## Scenarios

1. Mom creates "Pay for music school" due Friday, assigns Dad, tags "Kids".
2. A routine "Take out trash" repeats Mon/Thu, assigned to the two kids.
3. "Replace water filter" repeats 3 months **after the last completion**, not on a fixed date.
4. Dad opens "My tasks" and sees today's and overdue tasks; completes one; Mom sees it done immediately.

## Requirements

### Basics

- **TSK-001** `MVP` `todo` — Create a task: title (required), notes, due date (optional time), assignees, tags.
- **TSK-002** `MVP` `todo` — Assignee options: specific members, or "anyone" (first to complete closes it).
- **TSK-003** `MVP` `todo` — Task states: open, done. Completion records who and when. Reopen is possible.
- **TSK-004** `MVP` `todo` — Checklist inside a task (sub-items with their own done flag).
- **TSK-005** `MVP` `todo` — Edit and delete tasks; deleting a routine asks "this occurrence or the whole series".
- **TSK-006** `later` `todo` — Comments and photo attachments on a task (photos in M5b, [files](files.md); comments later).

### Recurring tasks (routines)

- **TSK-010** `MVP` `todo` — Fixed schedules: every N days / weeks on chosen weekdays / months on a day / years.
- **TSK-011** `MVP` `todo` — "After completion" schedules: next occurrence N days/weeks/months after the last completion.
- **TSK-012** `MVP` `todo` — Only the current occurrence is open; the next one is created when it is completed or its date passes.
- **TSK-013** `MVP` `todo` — Missed occurrences are visible as overdue and do not pile up endlessly (at most one overdue per routine).
- **TSK-014** `later` `todo` — Rotation: assignees of a routine take turns in a set order.
- **TSK-015** `MVP` `todo` — Completion history per routine (who did it and when).

### Views

- **TSK-020** `MVP` `todo` — "My tasks": assigned to me or "anyone", grouped overdue / today / upcoming / no date.
- **TSK-021** `MVP` `todo` — "All tasks" with filters: assignee, tag, state.
- **TSK-022** `MVP` `todo` — Tasks with a due date also appear in the calendar agenda (after M3).

### Reminders and notifications

- **TSK-030** `MVP` `todo` — A task can have a reminder (at due time or N minutes/hours/days before).
- **TSK-031** `MVP` `todo` — Assignees get a notification when a task is assigned to them.
- **TSK-032** `MVP` `todo` — The creator can opt in to a notification when the task is completed.
- **TSK-033** `MVP` `todo` — When a task becomes overdue (due time passed, not done), its creator gets a notification.
  Detected on the server, so it works while the assignee's phone is offline.

## Permissions and privacy

All members see all tasks in the household. Permissions ([catalogue](foundation.md#permission-catalogue)):
`tasks.create`, `tasks.edit_any`. Ownership rules: the creator edits own tasks; assignees (and everyone, for "anyone" tasks)
can complete them.

## Data

Task, TaskOccurrence or recurrence fields, TaskAssignee, ChecklistItem, TaskCompletion, TagLink.
Exact shape decided in M1 design.

## Out of scope (M1)

- Points / rewards (declined).
- Parent approval of completed tasks (not requested, 2026-10-22).
- Rotation (TSK-014, later).
- Private tasks visible only to the creator.
- Time tracking, task dependencies.
