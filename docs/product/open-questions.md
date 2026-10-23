# Open questions for the customer

Product questions that agents must not decide alone. Each has a default so work is not blocked;
the default is marked `ASSUMPTION (Q-NNN)` in the related spec until the customer answers.

Status: `open` → `answered` (record the answer and date, update specs) or `dropped`.
Answered questions stay in the "Answered" section for history.

## Open

_(none)_

## Answered

Q-001…Q-012 answered by the customer on 2026-10-22 during kickoff; later answers carry their date.

| Q | Topic | Answer |
| --- | --- | --- |
| Q-001 | Project name | 2026-10-23: the project is named **Lares**. Package and application id `app.lares`; repository `github.com/hiraise/lares`. (Earlier working name: FamilyHub.) |
| Q-002 | Milestone order after Tasks | Shopping → Calendar → Location → Budget. |
| Q-003 | Google Play | App complies with Play policies from day one; APKs until MVP; whether to publish on Play is decided at M6. |
| Q-004 | Income allocation | Ordered rules: fixed amounts and percents of the remainder; remainder manual or to a chosen category; rule set per income source. |
| Q-005 | Who sees money | Per-account access for members and groups (ADR-0014). |
| Q-006 | Currencies | One household currency; data model keeps currency per account for later. |
| Q-007 | Trackers | Phones only for now; GPS trackers moved to "Later". |
| Q-008 | Who sees whom on the map | Group rules ("Adults see everyone", "Kids see Adults"); pausing own sharing is a group permission. |
| Q-009 | Location history retention | 30 days by default, configurable by admin. |
| Q-010 | Children | A child is a regular user. No child role: admin-defined groups with permissions; templates "Adults" and "Kids" (ADR-0014). |
| Q-011 | Tasks in M1 | Add overdue notification to the creator. Rotation moved to later. Parent approval not requested. |
| Q-012 | Do administrators see private accounts | Yes: administrators see all data; nothing is hidden from them. Sections can be restricted per group (ADR-0014). |
| Q-013 | Bottom bar (2026-10-23) | Today · Tasks · Shopping · Calendar · More; Map, Budget, Family, Tags under "More" (FND-064). |
| Q-014 | Several assignees (2026-10-23) | One completion closes the task (or the occurrence) for all assignees. |
| Q-015 | Who gets a task reminder (2026-10-23) | Assignees; for "anyone" tasks everybody with access to tasks. |
| Q-016 | Tasks without a time (2026-10-23) | Reminder at 09:00 on the day; overdue notice to the creator at 09:00 the next morning. |
| Q-017 | Who can tick a task off (2026-10-23) | Assignees, the creator and members with "Edit others' tasks". |
