---
id: M2-02
status: done
depends_on: [M2-01]
requirements: [SHP-001, SHP-002, SHP-004, SHP-005, SHP-006, SHP-014, TAG-002]
---
# M2-02: Shopping on the server — tables, sync handlers, permissions, cascade

## Goal

The server stores and syncs shopping lists and items: migration, two `WritableSyncHandler`s with entity-level checks
(`ShoppingRules`), reference checks, visibility by `shopping.view`, cascade tombstones on list delete and housekeeping.
No notifications and no background jobs (Q-020).

## Read first

- [Shopping design](../../architecture/shopping.md) § Contract data, § Write rules, § Validation, § Server
- [Sync protocol](../../architecture/sync.md) § Push (processing order), § Housekeeping
- [Conventions § Server](../../process/conventions.md#server) — "New syncable type" checklist
- Code — the tasks module is the template, copy its shape:
  `server/…/tasks/TaskTables.kt`, `TaskRepository.kt`, `ChecklistItemSyncHandler.kt` (child with parent check),
  `TaskSyncHandler.kt` (`authorize`, `checkUpsert`, cascade in `tombstone`), `TaskChecks.kt`;
  `server/…/sync/WritableSyncHandler.kt`, `sync/SyncHousekeeping.kt`, `Application.kt` (`AppDeps.syncHandlers`);
  `db/migration/V6__tasks.sql`; tests `server/src/test/…/tasks/TaskSyncTest.kt`, `TaskPermissionsTest.kt`,
  fixtures `testFixtures/…/testing/TaskFixtures.kt`, `SyncFixtures.kt`

## Scope

- `V7__shopping.sql` exactly as in the design (comments like `V6__tasks.sql`).
- Package `app.lares.server.shopping`: `ShoppingTables.kt` (`ShoppingListTable`, `ShoppingItemTable` with the `sync`
  helper like `TaskTable`), `ShoppingRepository.kt` (row ↔ data mapping, `getList(householdId, id)`, `loadList(id)`,
  `loadItem(id)`), `ShoppingChecks.kt` (`requireParentList`), `ShoppingListSyncHandler.kt`, `ShoppingItemSyncHandler.kt`.
- Move `invalidField`, `requireAddedTags` and the private `parseAll` (now `internal`; `requireAddedMembers`, which stays
  in tasks, uses it too) from `tasks/TaskChecks.kt` to `sync/ReferenceChecks.kt` (package `app.lares.server.sync`);
  tasks imports them. No behaviour change.
- Register both handlers in `AppDeps.syncHandlers` after the task handlers.
- `SyncHousekeeping.purgeTombstones`: purge `shopping_item`, then `shopping_list`; update its KDoc.
- Test fixtures: `testFixtures/…/testing/ShoppingFixtures.kt` (`newShoppingList`, `newShoppingItem` mutation builders like
  `newTask`), shared with `integration-tests`.

Out of scope: client (M2-03), UI.

## Decisions (do not revisit)

- Columns: `status` and `category` are `text` holding wire names (`ShoppingItemStatus.key`, `ProductCategory.key`);
  reading an unknown value from the database maps through `fromKey` with the contract fallback (cannot happen in
  practice: the handler rejects unknown values). `tag_ids` JSON text like `task.tag_ids`. Instants as `timestamptz`
  (convert like `TaskCompletionTable.completedAt`).
- `ShoppingListSyncHandler`:
  - `canSee` = `SHOPPING_VIEW`; `normalize` / `validate` from the contract.
  - `authorize`: existing → `ShoppingRules.canManageLists(viewer)`; create → `canCreateList(viewer, merged)`; delete →
    `canManageLists`. Else `FORBIDDEN` "Not allowed to … this shopping list".
  - `checkUpsert`: changed `createdBy` → `invalidField("createdBy")`; `requireAddedTags(ctx, merged.tagIds − existing.tagIds)`.
  - `tombstone`: the list, then each live item of the list ordered by id, every item with its own `recordChange` seq
    (copy the task cascade).
- `ShoppingItemSyncHandler`:
  - `canSee` = `SHOPPING_VIEW`.
  - `authorize`: for upserts load the parent with `requireParentList(ctx, (existing ?: merged).listId)` (missing or other
    household → `ENTITY_NOT_FOUND`, tombstoned → `ENTITY_DELETED`); then `ShoppingRules.canEditItems` and
    `foreignAuthorFields(viewer, existing, merged)` must be empty, else `FORBIDDEN`. Deletes: `canEditItems` only
    (the coarse gate already checks it; keep the explicit call for clarity).
  - `checkUpsert`: changed `listId` → `invalidField("listId")`; added tags as for lists; `addedAt` / `boughtAt` that differ
    from the stored value and are later than `ctx.now + 1 day` → `invalidField(field, OUT_OF_RANGE)`.
- Error precedence stays as in M1 (coarse `WriteRules` gate first, then decode/normalize, `authorize`, `validate`,
  `checkUpsert`).
- `sync.md` § Entity types gets two rows (`shopping_list` — "Shopping module (M2-02); delete tombstones its items";
  `shopping_item` — "Child of a `shopping_list`; kept after it is bought (ADR-0021)") and § Housekeeping the M2 purge order.

## Steps

1. Migration and tables; repository; `ReferenceChecks` move.
2. List handler with cascade; item handler; registration; housekeeping.
3. Fixtures and tests (`server/src/test/…/shopping/`, names start with requirement ids):
   - `ShoppingSyncTest`: create a list and items, pull returns them with the right JSON (all fields, wire names);
     tick (`bought` + author fields), clear (`cleared`), re-add (`needed`, new `addedBy`/`addedAt`) round trip; an
     unchanged upsert is a no-op (`seq: null`); unknown `status` / `category` → `VALIDATION_FAILED` with `INVALID_FORMAT`;
     `status = bought` without `boughtBy` → `boughtBy: REQUIRED`; `addedAt` two days ahead → `OUT_OF_RANGE`;
     an unknown tag id added → `tagIds: INVALID_FORMAT`, a stored tag id deleted later stays valid on the next edit;
     `listId` changed → `INVALID_FORMAT`; item for a missing list → `ENTITY_NOT_FOUND`, for a list of another household →
     `ENTITY_NOT_FOUND`, for a deleted list → `ENTITY_DELETED`.
   - Cascade: deleting a list tombstones its items, each with a distinct seq greater than the list's; a pull with a small
     limit returns all of them across pages.
   - `ShoppingPermissionsTest` (SHP permissions table): Kids can add/tick/clear/forget items but get `FORBIDDEN` for
     list create/rename/delete; Adults can do everything; a custom group with only `shopping.view` can pull but cannot
     write items; a member without `shopping.view` gets no shopping rows in a pull; `addedBy` of another member on create →
     `FORBIDDEN`; `boughtBy` set to another member → `FORBIDDEN`; editing the note of an item another member added and
     bought is allowed; list `createdBy` ≠ viewer → `FORBIDDEN`, changed `createdBy` → `VALIDATION_FAILED`.
   - Housekeeping: old `shopping_item` and `shopping_list` tombstones are purged (extend the existing housekeeping test
     if there is one, else add `ShoppingHousekeepingTest`).
   - Existing task tests still pass after the `ReferenceChecks` move.

## Acceptance

- `tools/dev/verify.sh :server:test` while iterating (Docker running); full gate `tools/dev/verify.sh` at the end.

## Docs to update

- `architecture/sync.md` § Entity types and § Housekeeping (above).
- Shopping design only if a detail had to change.
- Shopping spec: progress notes under SHP-001, SHP-002, SHP-004, SHP-006, SHP-014 (*Server: …*), state `doing`.
- STATUS.md; plan status.

## Stop and report if

- The cascade or the parent check needs a change to `WritableSyncHandler` beyond what the task handlers already use.

## Handoff

Russian summary: the server now keeps the family's shopping lists, checks who may change them (kids add and tick, adults
also manage lists), and deleting a list removes its items everywhere; tested with N server tests. Next plan M2-03.
