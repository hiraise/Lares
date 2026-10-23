---
status: accepted
date: 2026-10-23
---
# ADR-0018: Tags are stored as id sets inside taggable entities

## Context

Tags attach to items of every module (TAG-002): tasks (M1), shopping lists and items, events, transactions, debts.
The domain model sketched a generic `TAG_LINK(tag_id, entity_type, entity_id)` row. "Attaching a tag follows the edit
permission of the item" (tags spec). Sync merges field by field (ADR-0008).

## Decision

1. Every taggable entity has a field `tagIds: Set<String>` in its contract data class. There is no separate tag link entity.
2. Attaching or detaching a tag is an edit of the item: same permission check, same mutation, same seq.
3. The server validates only ids **added** by a mutation (they must be non-deleted tags of the household). Ids of tags
   deleted later stay in items; clients ignore ids they do not know or that are deleted. Tag tombstones may be purged
   while items still reference them.
4. Tag pages (TAG-004) and tag filters (TAG-003) query each module's local table on the phone; the server does not need
   a cross-module tag index for now. If it does later (e.g. server-side reports for TAG-005), it can maintain a derived
   index table without changing the wire format.
5. Server and client store the set as a JSON text column, like other small collections.

## Consequences

- Creating a tagged item is one mutation; it is atomic and offline-safe.
- Concurrent tag edits of one item resolve by last writer (whole set). Acceptable: tag edits are rare and visible.
- Deleting a tag does not touch items; no cascade writes.
- [Domain model](../domain/model.md) shows `tagIds` instead of `TAG_LINK`.

## Alternatives considered

- **`tag_link` sync entity** — one more type, N + 1 mutations per tagged item, orphans when the item or tag is deleted,
  and a separate permission rule that must mirror each item's edit rule.
- **Server-side join table, embedded on the wire** — same wire format as chosen; adds a table without a current use.
