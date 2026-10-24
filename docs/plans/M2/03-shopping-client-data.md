---
id: M2-03
status: ready
depends_on: [M2-02]
requirements: [SHP-001, SHP-002, SHP-003, SHP-004, SHP-005, SHP-006, SHP-008, SHP-009, SHP-010, SHP-012, SHP-013, SHP-014, TAG-002, TAG-003, FND-040]
---
# M2-03: Shopping in the client core — tables, adapters, views, repository, writer

## Goal

Everything below the UI on the phone: lists and items sync and work offline; pure view functions compute the list page
(departments, bought section, suggestions), autocomplete, "who is shopping", the active list for Today and the tag page
section; one writer covers every user action, including quick add with matching and department memory. Proven by unit
and multi-device tests.

## Read first

- [Shopping design](../../architecture/shopping.md) § Overview, § Client (all), § Names and quick add, § Departments
  (only the API), § Write rules
- [ADR-0021](../../adr/0021-shopping-items-kept-as-list-memory.md)
- [Sync protocol § client data model](../../architecture/sync.md#client-data-model-sqlite) and client implementation notes
- [Conventions § Client](../../process/conventions.md#client) — "New syncable type on the client"
- Code — the tasks client core is the template: `client/…/sync/EntityAdapter.kt`, `sync/Adapters.kt` (`TaskAdapter`,
  `ChecklistItemAdapter`: parent lookup, `authorize`, `changedFields`), `sync/LocalWriter.kt`, `sync/SyncState.kt`
  (`DATA_SCHEMA_VERSION`), `db/LocalDb.kt` (`clearAll`), `sqldelight/…/task.sq`, `checklist_item.sq`,
  `migrations/2.sqm`, `data/tasks/TasksRepository.kt` (`minuteTicks`, input combining), `TaskViews.kt`,
  `TasksWriter.kt` (`update` with changed fields only), `data/Repositories.kt` (`MembersRepository`, `TagsRepository`,
  `PermissionsRepository`), `data/Models.kt` (`MemberView`, `Tag`), `di/Modules.kt`;
  tests `jvmTest/…/data/tasks/TaskFixture.kt`, `TasksWriterTest.kt`, `TaskViewsTest.kt`,
  `integration-tests/…/Harness.kt`, `TaskSyncTest.kt`; `tools/dev/seed.py` (`tasks()`)

## Scope

- SQLDelight `shopping_list.sq`, `shopping_item.sq` (columns per design) with queries `selectAll`, `selectById`,
  `selectByList`, `selectByListAndKey(list_id, name_key)`, `selectByKey(name_key)`, `upsert`, `deleteById`, `deleteAll`;
  `migrations/3.sqm` with the same DDL; `LocalDb.clearAll`; `DATA_SCHEMA_VERSION = 3`.
- `ShoppingListAdapter`, `ShoppingItemAdapter` in `Adapters.kt`, registered in `defaultEntityAdapters`.
- Package `data/shopping/`: `ShoppingModels.kt`, `ShoppingViews.kt` (pure), `ShoppingRepository.kt`, `ShoppingWriter.kt`;
  Koin `single`s in `sharedModule`.
- Move `minuteTicks` from `data/tasks/TasksRepository.kt` to `data/Ticks.kt` (same signature; tasks imports it) and the
  viewer flow as described below.
- `tools/dev/seed.py shopping` and `seed.py tick NAME [--as LOGIN]` (marks the first item with that name bought, as
  that member — to watch another phone's ticks arrive), with their lines in the `emulator` skill's command list.
- `integration-tests`: `TestClient` gets `shopping: ShoppingRepository` and `shoppingWriter: ShoppingWriter`; the
  convergence snapshot includes both shopping tables.

Out of scope: screens (M2-04…M2-06).

## Decisions (do not revisit)

- Models exactly as in design § Models and views; `ShoppingInputs.viewer` is the logged-in `Viewer` (permissions
  included), `now` from the minute ticker. `ShoppingListDetail.canEdit` = `ShoppingRules.canEditItems`, `canManage` =
  `canManageLists`.
- `ShoppingViews` functions and rules exactly as in the design table (`lists`, `detail`, `autocomplete`, `active`,
  `shopper`, `forTag`), plus constants `SUGGESTIONS_MAX = 8`, `AUTOCOMPLETE_MAX = 5`, `REMAINING_MAX = 3`,
  `SHOPPER_WINDOW = 15.minutes`. Items whose list is not in the `shopping_list` table are ignored everywhere.
- `ShoppingRepository(localDb, members: MembersRepository, tags: TagsRepository, permissions: PermissionsRepository,
  clock: Clock, ticks: Flow<Instant> = minuteTicks(clock))` — shaped like `TasksRepository`; Koin
  `single { ShoppingRepository(get(), get(), get(), get(), clock()) }` with the clock helper the tasks binding uses.
  Functions: `observeInputs()`, `observeLists()`, `observeDetail(listId, tagId)`, `observeActive()`,
  `observeForTag(tagId)`; inputs combine both tables, `members.observe…`, `tags.observe…`, the viewer and the ticks;
  `distinctUntilChanged()` on every output. The viewer flow is today a private `TasksRepository.viewer()`: move it next
  to `minuteTicks` (e.g. `data/Viewers.kt`, `fun observeViewer(localDb, permissions): Flow<Viewer?>`) and use it in both.
- `ShoppingWriter(writer: LocalWriter, localDb: LocalDb, clock: Clock)` with the functions and write rules of design
  § Writer, exact names. Times: `clock.now()` truncated to whole milliseconds. Lookups (`addOne`, `categoryFor`,
  `setCategory`) read the local tables in one `localDb.read { }`; each write is one `LocalWriter.upsert/delete`
  (one mutation per item; the sync engine coalesces).
- Adapters: as design § Adapters. `ShoppingItemAdapter.upsertTyped` writes `name_key = ShoppingNames.key(data.name)`.
  `authorize` mirrors the server handlers of M2-02 exactly (same rule calls, same error codes; a changed `createdBy` /
  `listId` → `LocalWriteException(VALIDATION_FAILED)`).
- `seed.py shopping` (after `family`; idempotent through `state["shopping"]` like `tasks()`), as olga:
  - tag «Дача» (created if missing);
  - list «Аптека» (created first) with «Парацетамол», «Пластырь», `addedAt` 3 days ago;
  - list «Дача» tagged «Дача» with «Уголь» and «Перчатки» (the latter tagged «Дача»), `addedAt` 2 days ago;
  - list «Продукты» (created last) with items to buy, `addedAt` within the last hour, one minute apart: Молоко 2,5%
    («2 л», note «Без лактозы, если будет»), Хлеб, Яблоки («1 кг»), Курица, Гречка, Шоколад, Туалетная бумага, Сметана;
    and 6 cleared items with `boughtCount` 2…9 and `boughtAt` in the past weeks: Яйца, Сыр, Бананы, Творог, Кефир, Огурцы.
  - then as dima (`dima`/`dima-password`) tick «Гречка» and «Туалетная бумага» with `boughtAt` = now − 1 min.

  So «Продукты» is the active list for Today. Walkthroughs that check "who is shopping" run `seed.py tick … --as dima`
  right before (the line lasts 15 minutes). Categories are set explicitly in the seed (matching what `guess` returns).
  Instants in canonical form `YYYY-MM-DDTHH:MM:SSZ` (whole seconds — the server rejects values that change in a decode
  round trip).
- `seed.py tick NAME [--as LOGIN]` (default olga): pulls `/sync/changes`, finds the first live `shopping_item` whose
  name equals NAME case-insensitively, and upserts `status = bought`, `boughtBy` = that member, `boughtAt` = now,
  `boughtCount + 1`; exits with a message if none is found.

## Steps

1. Tables, migration, clear-all, schema version; extend the existing `jvmTest/…/db/MigrationTest.kt` (its assertion
   `LaresDb.Schema.version == 3L` becomes `4L`; migrate a version-3 file to 4 and check the new tables).
2. Adapters; `minuteTicks` move.
3. Models, `ShoppingViews`, repository, writer; Koin.
4. `seed.py shopping`, `seed.py tick`; skill command list.
5. Tests:
   - jvmTest `ShoppingViewsTest` (pure, names start with requirement ids): grouping on/off and the "≥ 2 departments" rule,
     section and in-section order, bought order, suggestions ranking and limit, tag filter and `tagsInUse`, autocomplete
     (min length, sources, order, distinct, excludes names to buy), `active` (latest activity, ties, none), `shopper`
     (window edges at 15 min, not me, latest wins, unknown member → null), `forTag`, unknown member/tag ids dropped.
   - jvmTest `ShoppingWriterTest` (in-memory DB like `TasksWriterTest`): outbox contents for each function;
     `add("Молоко 2 л, хлеб")` creates two items with guessed departments; `add` with a `tagId` tags new and
     brought-back rows; adding an existing `needed` name → no new row,
     `alreadyThere`, quantity updated; adding a `cleared` name brings it back with its note and category and the new
     quantity; duplicates inside one text merged; department from another list's row wins over the dictionary;
     `setCategory` rewrites same-key rows in other lists only where different, and skips rows of a list deleted locally
     (the user's own row still saves); `setBought` true/false counts;
     `clearBought` (all, and with a `tagId` only the tagged ones) + `restore`; `remove` + `restore`; `forget` deletes; Kid cannot create a list locally
     (`LocalForbiddenException`, nothing enqueued); view-only member cannot add.
   - jvmTest `ShoppingRepositoryTest`: with a test clock, "who is shopping" disappears when the clock passes 15 minutes
     (ticker) without data changes.
   - integration-tests `ShoppingSyncTest` (`familyTest { }`): two clients tick different items offline → both bought after
     sync, converged (SHP-004); client A ticks online and client B, online in the foreground, sees it within 2 s
     (SHP-005, same technique as the existing realtime test); two clients bring back the same cleared item offline → one
     row, `needed`, converged; one client deletes the list while another ticks items offline → list and items gone
     everywhere; a forged Kid list create through a stale permission → rejected and reverted (invariant 3); a member
     without `shopping.view` (custom group) has no shopping rows after reset; `setCategory` on one client changes the
     same-name item of another list on the other clients.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest` and `:integration-tests:test` while iterating; full gate at the end.
- `tools/dev/server.sh reset && tools/dev/seed.py family && tools/dev/seed.py shopping && tools/dev/seed.py tick Хлеб --as dima`
  runs without errors.

## Docs to update

- sync.md § client data model: typed tables list.
- Shopping design if a detail changed; spec progress notes (*Client core: …*) under the requirements covered.
- `emulator` skill command list (`seed.py shopping`).
- STATUS.md; plan status.

## Stop and report if

- The writer needs a multi-row atomic local write (one transaction, several mutations) to keep data consistent — the
  design assumes it does not.

## Handoff

Russian summary: the phone now keeps shopping lists offline, recognizes items already in a list, remembers departments,
computes suggestions and "who is shopping"; tested with simulated phones (two people ticking in one store offline,
another seeing ticks within 2 seconds). Next M2-04.
