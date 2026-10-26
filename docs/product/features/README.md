# Feature specs

One file per module. A spec says **what** the product does; architecture and ADRs say **how**.

## Index

| Area | Spec | Milestone | Status |
| --- | --- | --- | --- |
| FND | [foundation.md](foundation.md) | M0 | approved |
| TAG | [tags.md](tags.md) | M0 (core), each module | approved |
| TSK | [tasks.md](tasks.md) | M1 | approved |
| SHP | [shopping.md](shopping.md) | M2 | approved |
| CAL | [calendar.md](calendar.md) | M3 | approved |
| LOC, TRK | [location.md](location.md) | M4; trackers later | approved |
| BUD | [budget.md](budget.md) | M5 | outline |

## Spec status

`outline` → `draft` → `approved` (customer accepted scope) → `implemented` (all MVP requirements done).
Detail a spec fully right before its milestone starts, not earlier.

## Requirement format

```
- **TSK-001** `MVP` `todo` — A member can create a task with a title.
```

- Priority: `MVP` (in the milestone) or `later`.
- State: `todo`, `doing`, `done`, `dropped`.
- Tests reference requirement IDs (see [testing](../../process/testing.md)).

## Spec template

```markdown
---
status: outline | draft | approved | implemented
milestone: M<N>
updated: YYYY-MM-DD
---
# <Module> (<AREA>)

## Summary
## Scenarios
## Requirements
## Permissions and privacy
## Data
## Notifications
## Assumptions
## Out of scope
```
