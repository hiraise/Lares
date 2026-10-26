---
status: draft
updated: 2026-10-23
---
# Domain model

High-level entities and relations. Field-level schemas live with the code (migrations, contract module)
once they exist; this page stays conceptual.

## Common to every syncable entity

`id` (UUIDv7, client-generated), `household_id`, `seq` (last change), `created_at`, `updated_at`, `deleted` (tombstone).
See ADR-0008, ADR-0010.

## Core

```mermaid
erDiagram
    USER ||--o{ MEMBER : "is"
    HOUSEHOLD ||--o{ MEMBER : "has"
    HOUSEHOLD ||--o{ GROUP : "has"
    GROUP ||--o{ GROUP_MEMBERSHIP : ""
    MEMBER ||--o{ GROUP_MEMBERSHIP : "belongs to"
    GROUP ||--o{ GROUP_PERMISSION : "grants"
    HOUSEHOLD ||--o{ PERSON : "has"
    MEMBER |o--|| PERSON : "profile"
    HOUSEHOLD ||--o{ INVITE : "issues"
    USER ||--o{ SESSION : "has"
    HOUSEHOLD ||--o{ TAG : "has"
```

- Taggable entities carry `tagIds` (a set of tag ids) in their own data; there is no link entity (ADR-0018).
- Access rules (location visibility, money account access) have a subject and a target, each a group or a member (ADR-0014).

## Tasks and shopping (M1, M2)

```mermaid
erDiagram
    MEMBER ||--o{ TASK : "creates"
    TASK ||--o{ CHECKLIST_ITEM : "contains"
    TASK ||--o{ TASK_COMPLETION : "history"
    MEMBER ||--o{ SHOPPING_LIST : "creates"
    SHOPPING_LIST ||--o{ SHOPPING_ITEM : "contains"
```

- A routine is a task with a recurrence rule. Occurrences are computed from the task and its completions, not stored
  (ADR-0017); assignees and tags are id sets inside the task. Design: [architecture/tasks.md](../architecture/tasks.md).
- A shopping item is one product in one list with a status (`needed`, `bought`, `cleared`) and a department; it is kept
  after it is bought as the list's memory for suggestions (ADR-0021). Lists and items carry tag id sets.
  Design: [architecture/shopping.md](../architecture/shopping.md).

## Calendar (M3)

```mermaid
erDiagram
    EVENT ||--o{ EVENT_PARTICIPANT : "concerns"
    PERSON ||--o{ EVENT_PARTICIPANT : ""
    EVENT ||--o{ REMINDER : "per user"
```

- Birthdays are derived from `PERSON.birth_date`, not stored as events.

## Location (M4; trackers later)

```mermaid
erDiagram
    MEMBER ||--o{ DEVICE : "owns"
    DEVICE ||--o{ POSITION : "reports"
    TRACKABLE ||--o{ DEVICE : "carries"
    HOUSEHOLD ||--o{ PLACE : "defines"
    MEMBER ||--o{ PLACE_ALERT : "subscribes (per watched member)"
    PLACE_ALERT }o--o{ PLACE : "lists"
    MEMBER ||--o| LOCATION_STATE : "sharing status, latest fix"
    HOUSEHOLD ||--o{ LOCATION_VISIBILITY_RULE : "defines"
```

- `POSITION` is append-only, not synced through the general mechanism, deleted after retention (ADR-0024).
- `PLACE` and `PLACE_ALERT` are sync data; a `PLACE_ALERT` row is seen only by its subscriber, its id is derived from
  the watched member and the subscriber (`Ids.placeAlertRowId`).
- `LOCATION_STATE` (server only) holds the member's sharing status, pause, the sharing session and the newest fix.

## Budget (M5)

```mermaid
erDiagram
    MONEY_ACCOUNT ||--o{ TRANSACTION : "holds"
    CATEGORY ||--o{ TRANSACTION : "classifies"
    INCOME_SOURCE ||--o{ ALLOCATION_RULE : "ordered rules"
    ALLOCATION_RULE }o--|| CATEGORY : "targets"
    CATEGORY ||--o{ ALLOCATION : "receives"
    CATEGORY ||--o{ CATEGORY_LOAN : "lends / borrows"
    DEBT }o--|| CATEGORY : "paid from"
    DEBT ||--o{ DEBT_PAYMENT : "schedule"
    DEBT }o--o| PERSON : "counterparty"
    MONEY_ACCOUNT ||--o{ MONEY_ACCOUNT_ACCESS : "shared with"
```

- Amounts: integer minor units + ISO 4217 currency.

## Value conventions

| Kind | Representation |
| --- | --- |
| Point in time | UTC instant |
| Event time | instant + IANA time zone id |
| All-day date, birthday | local date (no zone); year may be unknown |
| Money | `Long` minor units + currency code |
| Coordinates | WGS84 latitude/longitude as doubles |
