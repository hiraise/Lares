# Project history

Log of completed work, oldest first. One short entry per plan or work session.
Current state and next steps: [STATUS.md](STATUS.md).

- 2026-10-22 Kickoff with the customer: modules, priorities, license, distribution, docs language.
- 2026-10-22 Kickoff discussion, round 2: stack re-reviewed (Kotlin kept, ADR-0013), group-based permissions (ADR-0014),
  all open questions answered; access model clarified (administrators see all data).
- 2026-10-22 First commit `30e9207` (kickoff documentation).
- 2026-10-22 M0 planning: [sync protocol](architecture/sync.md), [auth design](architecture/auth.md),
  [API](architecture/api.md), verified [versions](plans/M0/versions.md), plans M0-01…M0-11.
- 2026-10-22 Plans reviewed by a fresh agent from the executor's point of view; 17 findings fixed (pull snapshot, sync epoch
  for restores, refresh grace window, lenient enums, shared write rules, safe tombstone purge, cross-household id checks,
  ADR-0015 for the M0 load test scope).
- 2026-10-22 M0-01: dev environment (JDK 21, Android SDK 37, AOSP emulator) and Gradle monorepo skeleton
  (`contract`, `server`, `client:shared`, `client:android`); `./gradlew check :client:android:assembleDebug` green,
  `/health/live` answers, emulator shows the placeholder screen; Spotless/ktlint and CI workflow added.
- 2026-10-22 M0-02: server foundation — env config, HikariCP + Flyway (`V1__foundation`: `household`,
  `household_sync_state`), Exposed DSL, `dbQuery`/`recordChange` with after-commit `HouseholdChangeBus`, JSON errors
  (`ApiException` → `ErrorCode`), protocol header check, request ids in logs (plain/JSON), `/health/live|ready`,
  `/api/v1/instance`; contract: `ErrorCode`/`FieldErrorCode` with lenient decoding, `InstanceInfo`, `LaresJson`.
  Test harness on Testcontainers PostgreSQL; dev database in `deploy/dev/docker-compose.yml`.
- 2026-10-22 M0-03: identity — permission catalogue, evaluator, validators, codes, entity data and auth DTOs in `contract/`;
  migration `V2__identity` (`user_account`, `person`, `member`, `group_entity` + permissions/memberships, `session`);
  Argon2id (PHC, checked against the reference implementation and OpenSSL), opaque tokens with refresh rotation, 60 s grace
  and reuse detection, `RequireSession` plugin, failed-attempt limiter, setup code in the log; endpoints setup, login,
  refresh, logout, me, password, sessions, delete account. 71 server tests; manual curl walkthrough done.
- 2026-10-23 Visual style chosen with the customer (two option rounds, then 10 screen mockups):
  [visual-style.md](design/visual-style.md), [ADR-0016](adr/0016-visual-design-system.md), mockups in
  [design/mockups/](design/mockups/README.md). New requirements FND-034 (personal color), FND-063 (Today screen),
  FND-064 (bottom bar, Q-013 answered), FND-065 (visual style). Not-yet-executed plans amended: M0-05 (`PaletteColor`
  shared by tags and persons, `PersonData.color`), M0-06 (color on create), M0-07 (theme, fonts, icon pipeline, Today,
  More, app icon), M0-08 (personal colors, entry from More). Mockups and bottom bar approved. File storage for photos:
  new milestone M5b after Budget ([files spec](product/features/files.md)).
- 2026-10-23 M0-04: household administration (server) — household settings, groups (built-in and last-admin rules,
  `AccessVersionService`), members created directly (login/password optional, linking a relative), password reset,
  removal (shared with account deletion), invites (create/list/revoke/preview/accept, single use under row lock),
  QR login codes; migration `V3__admin`; contract `api/admin` DTOs and `DeepLinks`. 99 server tests incl. permission
  matrix and cross-household isolation; manual curl walkthrough done.
- 2026-10-23 M0-05: sync server — contract sync DTOs, `TagData`, `PaletteColor`/`PaletteColors` (personal colors, FND-034),
  `PersonData.color`, tag/person validators, shared `WriteRules`; migration `V4__sync` (`tag`, `sync_mutation_log`,
  `person.color`); `SyncEntityHandler` registry (household, member, group read-only; person, tag writable); pull in one
  REPEATABLE READ snapshot with reset rules 1–4, push with idempotency log, strict field-level merge and per-mutation
  transactions, WebSocket events (hello/changed, 500 ms debounce), daily tombstone purge and log cleanup. Also: password
  changes now give the member a new seq (`hasPassword` is synced); persons referenced by invites are not purged.
  121 server tests (invariants 1–7, 9, 10 at server level); manual walkthrough on the real server incl. WebSocket.
- 2026-10-23 M0-06: client core in `client/shared` — SQLDelight `LaresDb` (typed tables, sync tables, `local_session`),
  entity adapters and materializer, `LocalWriter` with shared `WriteRules` and validators, Ktor API client for all M0
  endpoints with single-flight token refresh, `SyncEngine` (push → pull → full resync, backoff, realtime WebSocket,
  `SyncStatus`, rejections), repositories and writers for tags and persons, `SessionManager`, Koin modules; Android
  platform: Keystore-encrypted tokens in DataStore, OkHttp; JVM: in-memory. New module `integration-tests/` (server test
  harness moved to `server/src/testFixtures`): 15 multi-device tests with the real server and three client cores —
  invariants 1–8 and 10, offline work, realtime within 2 s, resets keeping the outbox, revocation, offline permissions,
  randomized convergence. 23 client unit tests. sync.md and ADR-0008 accepted.
- 2026-10-23 M0-07: Android app shell — design assets (`tools/design-assets/build.py`: Manrope 400–800, 61 Material
  Symbols icons at weight 250, outlined + filled, pinned to commits), theme with all visual-style tokens (light/dark,
  extra roles, palette tones, type scale, shapes, spacing), shared components; screens Server, Setup, Login (password,
  QR, invitation code), Invite accept, link handling, QR scanner (CameraX + ZXing), main scaffold with the filtered
  bottom bar, Today (empty state), section placeholders, More, sync indicator / sheet / rejection snackbar, Settings
  (profile name offline, change password, login QR with countdown, devices with revoke, theme, language, about and
  licenses, logout, delete account). Android shell: AppCompat activity with per-app language, deep links
  `lares://invite|login`, session restore on start, foreground toggle, connectivity trigger, debug-only cleartext to
  local hosts, adaptive icon. `SessionManager.deleteAccount`. 23 new client tests (ViewModels on a mock server, Compose UI
  tests incl. bottom-bar filtering, icon resources). Emulator walkthrough of all flows except live camera scanning.
- 2026-10-23 M0-08: Family management screens — More › Family / Tags open the Family screen with tabs Members,
  Relatives, Groups, Tags and Family settings (admins). Members: list with group chips and the Administrators badge,
  active invites with revoke, member detail (change groups, reset password, login QR for that member, remove), "Invite"
  → send an invitation (groups, 1/3/7 days, QR + share link) or create an account without password (→ login QR); both
  can link an existing relative (FND-032). Groups: editor with plain-language permissions by section (action ⇒ "See …",
  unchecking "See …" clears the section), members checklist, Administrators locked with last-member check, delete with
  confirmation. Relatives: list with "member" badge, person form (birthday pickers day/month/optional year, relation
  suggestions, color, contacts, notes), delete for non-members. Tags: list, archive section, create/edit sheet with
  palette, inline duplicate check, delete. Personal color in the own profile. Admin actions are online-only ("Needs
  internet connection") and request a sync afterwards; relatives and tags work offline. `LoginQrViewModel` takes an
  optional member id. 13 new client tests (ViewModels on a synced mock household, Compose UI for the group editor and
  members list) and 1 multi-device test (invite created by the admin, accepted on a new client, both see each other).
  Emulator walkthrough RU/EN, light/dark; fixed on the way: FAB shadow artifact, contact field focus, tag sheet behind
  the keyboard and a layout loop in it.
- 2026-10-23 Agent tooling to cut token use (analysis of 14 sessions: 55–80% of the cost is re-reading the growing
  context; the emulator walkthrough at the end of M0-07/M0-08 made up 56–72% of those sessions). Added `tools/dev/`
  (`verify.sh` short-output build/test, `server.sh` local server with its own database, `seed.py` test family and API
  calls, `emu.sh` emulator lifecycle, `ui.sh` tap-by-text and downscaled screenshots), project skills `plan-run`,
  `plan-finish`, `build-check`, `emulator` and the `emulator-tester` agent (walkthroughs in their own context).
  STATUS.md shortened; the log of past work moved here.
- 2026-10-23 M0-09 Notifications. Contract: `NotificationType` (stable wire names, channel per type), `PushPayload`,
  push registration, test result and notification settings DTOs. Server: migration V5 (`push_registration` per session,
  `instance_secret` with the VAPID keys, `notification_setting`), `WebPushSender` on `com.interaso:webpush` (own HTTP
  call so any 2xx counts; 404/410 deletes the registration), SSRF guard with `LARES_PUSH_ALLOWED_HOSTS`,
  `NotificationService` (mutes respected, revoked sessions lose their registration), sync tickles throttled to one per
  30 s per household with a trailing send, endpoints `/me/push`, `/me/push/test`, `/me/notification-settings`.
  Client: local `reminder`, `notification_mute`, `push_endpoint` tables (SQLDelight migration 1 → 2), `Reminders`,
  `PushMessageHandler`, `PushEndpoints` (re-upload when offline or the UI language changed), `NotificationsCoordinator`
  (login/logout lifecycle); Android: channels, `AlarmManager` reminders with boot/update re-registration, `SyncWorker`
  (periodic 15 min + expedited on tickles), UnifiedPush `PushService` and distributor picker, post-login permission
  dialog, Settings › Notifications. Tests: server +11 (fake push service decrypts RFC 8291 messages), client +5,
  contract +5. Emulator (API 37): permission flow, offline test reminder, WorkManager job and its cancellation on
  logout, exact-alarm settings, en/ru; four small UI fixes after it. End-to-end UnifiedPush needs a phone with ntfy.
- 2026-10-23 **M0-10 Deploy and operations.** `server/Dockerfile` (temurin 21 JRE noble, copies `installDist`, non-root
  uid 10001, small-heap `JAVA_OPTS`, curl healthcheck); `deploy/docker-compose.yml` (postgres 18, server 384 MiB,
  Caddy 64 MiB with `Caddyfile`/`Caddyfile.push`, ntfy behind profile `push`, deny-all + anonymous `up*`), documented
  `.env.example`, `docker-compose.build.yml` override for local images; `backup.sh` (pg_dump -Fc, keeps 14),
  `restore.sh` (validates dump, recreates DB, new sync epoch), `smoke.sh` (HTTPS on localhost via Caddy's local CA,
  setup, backup, tag via sync, ntfy access check, restore, tag gone + epoch changed). Env-based Android release
  signing; `release.yml` (multi-arch GHCR image, APK on a GitHub Release, tag/version check) and CI job
  `deploy-smoke`; actionlint and shellcheck clean. Smoke passes locally; server 160 MiB of 384 MiB after start.
  User manual `install-server.md` (en/ru).
- 2026-10-23 **M0-11 Load simulation.** New module `tools/loadsim` (Kotlin CLI, Ktor CIO client, contract DTOs):
  sets up a household through the public API, 10 devices (5 members × 2, login codes), WebSocket events, pushes every
  60 s ± 20 %, pulls on events, token refresh, admin REST calls, burst phase; `latency.csv`, `summary.json`, fails above
  1 % errors. `deploy/scripts/loadtest.sh` runs the production stack, cold start, memory sampling (`docker stats`,
  `/proc/1/status`), summary. 30-minute baseline with M0-10 defaults: RSS max 260 MiB (over 256). Native memory
  tracking showed an oversized heap and code cache; final `-Xmx128m -XX:ReservedCodeCacheSize=64m`,
  `MALLOC_ARENA_MAX=2`: RSS avg 232 / max 237 MiB, p95 52 ms, cold start 1.9–2.7 s, stack ~320 MiB; server
  `mem_limit` 384m → 300m. Results: `docs/architecture/perf/M0-baseline.md`. loadsim tests: 5.
- 2026-10-23 **Rename to Lares** (Q-001, customer's choice). Working name FamilyHub replaced everywhere: Kotlin packages
  `app.familyhub` → `app.lares` (also Android application id), classes `FamilyHub*` → `Lares*`, env vars
  `FAMILYHUB_*` → `LARES_*`, database, volume and image names `familyhub*` → `lares*`, protocol header
  `Lares-Protocol`, UI strings. Image default `ghcr.io/hiraise/lares-server`. Remote: `github.com/hiraise/lares`.
- 2026-10-23 M1 planning: tasks spec detailed and approved (Q-014…Q-017 answered), [tasks design](architecture/tasks.md),
  ADR-0017 (occurrences derived from completions), ADR-0018 (tags embedded in entities), plans M1-01…M1-07.
- 2026-10-23 Deploy docs gaps: release → image dependency and GHCR visibility (`deploy/README.md#releases`, manual
  step 5 en/ru), `COMPOSE_PROFILES=push` in `.env.example` and the ntfy section of the manual.
- 2026-10-23 Release signing and flow (ADR-0019): new Lares-only release key (not shared with babylog), stored in GitHub
  secrets and `~/.android/lares-release/`; release workflow requires it and verifies the certificate; releases per
  milestone plus patch releases, no betas (customer); `tools/release/` scripts; `v0.1.1` was a debug APK.
- 2026-10-23 M1-01 Tasks contract: `TaskData`, `Recurrence`, `ChecklistItemData`, `TaskCompletionData` (ISO dates,
  lenient enums; an unknown repeat rule decodes to `null` = one-off), entity types, `Recurrences` and `TaskSchedule`
  (current occurrence, overdue, groups, reminder and notice times, checklist reset, next overdue check), `TaskRules` +
  `tasks.view` write gate, task validators and normalizers, `NotificationType.TASK_REMINDER` (label en/ru). Contract
  tests 103 (all task rules from the spec examples, Moscow and Berlin DST). Fix during the work: a week rule's anchor is
  scheduled only on a chosen weekday (design clarified; M1-02 plan gets the unknown-rule rejection).
- 2026-10-23 **M1-02** Tasks on the server: `V6__tasks.sql` (`task`, `checklist_item`, `task_completion`, server-only
  `overdue_*` columns), package `app.lares.server.tasks` (tables, `TaskRepository`, three `WritableSyncHandler`s), new
  `WritableSyncHandler.authorize` hook for entity-level `TaskRules` checks, reference checks for added assignees and tags,
  routine → one-off and `createdBy`/`taskId` changes rejected, immutable completions, cascade tombstones with one seq per
  row, `tasks.view` visibility, housekeeping purge of the three tables, test helpers `testing/TaskFixtures.kt`.
  Server tests +13 (`TaskSyncTest`, `TaskPermissionsTest`); M0 tests unchanged.

- 2026-10-23 **M1-03** Task notifications on the server. `JdbcTransaction.afterCommit` (actions kept per attempt in
  `PendingChanges`, run by `dbQuery` after the change events, failures logged). `TaskNotifier`: events `Assigned`,
  `Completed`, `Overdue` in an unbounded channel, consumer started in `Application.module`; recipients are non-deleted
  members with `tasks.view`, never the actor; texts en/ru in `NotificationTexts`, titles cut to 100 characters.
  Handler hooks: added assignees on create/update; first non-skipped completion of an occurrence when the creator opted
  in. `TaskOverdueTracker.refresh` keeps `overdue_check_at` (no seq) after every task and completion write;
  `TaskOverdueJob` (30 s after start, then every 60 s, 500 tasks per run, household lock, `updated_at` and 24 h guards).
  Tests: `AfterCommitTest` (4), `TaskNotificationsTest` (14); deterministic via `TaskNotifier.awaitIdle()` and
  `FakePushService.drain()`.

- 2026-10-24 **M1-04 Tasks in the client core.** Tables `task`, `checklist_item`, `task_completion` (migration `2.sqm`,
  `DATA_SCHEMA_VERSION = 2`); `EntityAdapter.authorize`/`checkDelete` hooks called by `LocalWriter` (person
  `PERSON_IS_MEMBER` moved into `checkDelete`); three task adapters with the server's `TaskRules` and immutability checks.
  `data/tasks/`: `TaskViews` (pure build, filter, sections, counts, "done today" rows), `TasksRepository` (minute ticker,
  household zone), `TasksWriter` (every action incl. complete/skip/undo/reopen and checklist), `TaskReminderPlanner`
  (`task:<id>` reminders, collector on login, `SyncWorker` reconcile; en/ru body texts). `seed.py tasks` (sample data;
  values must be canonical — the server rejects fields that change in a decode round trip, e.g. missing `weekdays: []`).
  Tests: 31 client unit tests (views 11, writer 10, repository 4, reminders 5, migration 1), 5 multi-device `TaskSyncTest`.

- 2026-10-24 **M1-05 Tasks UI.** `ui/tasks/`: Tasks tab (`TasksViewModel`/`TasksScreen`: chips «Все · N», «Мои · N»
  (default), «Поручено мной», «Метка» with a tag picker, search, filters sheet with assignee and open/done, sections with
  counts, ticking off with a 5 s undo snackbar, "not allowed" snackbar, gradient FAB), task page
  (`TaskDetailViewModel`/`TaskDetailScreen`: status, «Выполнить» / «Не выполнено», facts, checklist with inline add and
  long-press rename/delete, routine history with undo of the latest entry, skip, delete with "this occurrence / whole
  series"), stub `TaskEditor` route for M1-06. Shared `TaskComponents` (row, checkbox, tag chip, avatars) for M1-07.
  Pure formatting in `TasksLogic` returning `UiText` (resource descriptions resolved in Compose or with `getString`), so
  texts follow the app language and are testable in en and ru. Icons `checklist`, `edit`, `skip_next`, `alarm`, `undo`,
  `notes`; arrays `weekdays_short`, `months_short`. Tests: `TasksLogicTest` 8, `TasksViewModelTest` 4,
  `TaskDetailViewModelTest` 4, `TasksUiTest` 3. Emulator walkthrough (ru/en, light/dark, admin and kid) passed; fixed
  caret jumps in the search field (local text state) and the rename dialog (caret at the end). The `lares` AVD was
  created (only the pre-rename `familyhub` AVD existed).

**2026-10-24 — M1-06 Task editor.** One full-screen editor for new and existing tasks replaces the M1-05 stub: title,
notes, date («Начало» for routines) and time pickers (Material 3), repeat sheet (presets and custom: fixed or after
completion, interval, unit, weekdays, summary), assignees («Кто угодно» exclusive, members with `tasks.view`), tags
(archived ones on the task kept; «+ Новая метка» reuses the Family tag sheet through the new shared `TagEditor`),
reminder (choices with and without a time), «Сообщить мне, когда выполнят» for the creator, checklist on create,
«Отменить изменения?» on Back. Pure `TaskForm` writes on edit only the fields the form changed (no implicit anchor
rewrite). New task from «Мои» is mine, from a tag filter has that tag; after creating, the task page replaces the
editor. Reminder labels aligned with the plan («В срок», «В день срока в 9:00», «За 1 день в 9:00»). New shared
`SegmentedControl`. Dependency `navigationevent-compose` 1.1.0 declared (already shipped by navigation, Apache-2.0).
Tests: `TaskFormTest` 9, `TaskEditorViewModelTest` 7, `TaskEditorUiTest` 3; emulator walkthrough of the spec scenarios
(ru/en, light/dark) passed; the selected segment was made lighter in the dark theme after it.

### 2026-10-24 M1-07 Today block, tag page, notification switches and taps

Today shows «Задачи на сегодня» (my overdue, today's and done-today tasks, open count, «Все», at most 6 rows +
«Ещё N», ticking with undo) and the «Добавить» button (editor with me as assignee). New tag page (`TagDestination`,
tasks section, archived tags too, edit from the top bar) opened from Family › Tags rows and from tag chips on the
task page. Settings › Notifications got the four task switches (muting reminders already cancels them through the
planner). Notification taps: `NotificationPoster` got an optional `NotificationTarget`, Android puts it into the
`MainActivity` intent, `PendingNavigation` hands it to the logged-in UI, which opens the task (after one sync if it is
not there yet) or the Tasks tab with «Задача удалена». Row building and ticking were extracted from `TasksViewModel`
into `TaskRows` / `TaskTicker`. User manual "Tasks" (en, ru), CHANGELOG. Tests: `TodayViewModelTest` 3,
`TagViewModelTest` 3, notification targets and `PendingNavigation` 2, settings switches 1, Today UI 2.

### 2026-10-24 Reminder asks for the notification permission

Customer decision after M1-07: choosing a reminder in the task editor while notifications are off shows «Разрешить
уведомления?»; «Разрешить» runs the system request, or opens the app's notification settings when the system no
longer asks (`NotificationPermissions.openNotificationSettings`). Checked on the emulator.
- 2026-10-24 Release branches (ADR-0020, customer request): direct pushes to `main` closed by GitHub rulesets; work
  lands on `release/X.Y.Z`; the release pull request is checked by CI (`release-pr`) and its merge tags and publishes the
  release (`release.yml` on push to `main`). The eleven unpushed M1 commits became `release/0.2.0`.
- 2026-10-24 Release v0.2.0 (M0 + M1), the first through release branches: PR #1, signed APK, public multi-arch server
  image. Notification switch race fixed on the way (found as a CI flake).
- 2026-10-24 M2 planning: shopping spec detailed (SHP-001…SHP-018) and approved, customer answered Q-018 (all lists visible to
  everyone with Shopping), Q-019 (automatic store departments, corrections remembered), Q-020 (no shopping
  notifications; "who is shopping" from recent ticks). Design in [architecture/shopping.md](architecture/shopping.md),
  ADR-0021 (an item is "this product in this list", kept after purchase as the list's memory), six plans M2-01…M2-06.
- 2026-10-24 M2-01 Shopping contract: `ShoppingListData`, `ShoppingItemData`, lenient `ShoppingItemStatus` (→ `cleared`) and
  `ProductCategory` (→ `other`, store order), entity types `shopping_list`/`shopping_item`; `ShoppingNames` (normalize,
  match key, display name), `QuickAdd` parser (splitting, trailing quantities with units, `x2`), department dictionary
  (≥ 20 Russian and ≥ 15 English stems per department, phrases, modifiers) with `ShoppingCategories.guess`, `ShoppingRules`
  and `WriteRules` gates, validators and normalizers; 25 contract tests, all rows of the design's department table pass.
- 2026-10-24 M2-02 Shopping on the server: `V7__shopping.sql`, `ShoppingListSyncHandler` (`shopping.manage_lists`, creator
  is the writer and never changes, delete tombstones the list's items each with its own seq) and `ShoppingItemSyncHandler`
  (live parent list, `shopping.edit`, `addedBy`/`boughtBy` set by a write must be the writer, `listId` immutable, times
  more than a day ahead rejected), added tag ids checked, `shopping.view` visibility, housekeeping purges items then
  lists; `invalidField`/`requireAddedTags` moved to `sync/ReferenceChecks.kt`; `ShoppingFixtures`; 13 server tests.
- 2026-10-24 M2-03 Shopping in the client core: `shopping_list` / `shopping_item` tables (`3.sqm`, `DATA_SCHEMA_VERSION`
  3), adapters mirroring the M2-02 handlers, `ShoppingViews` (sections, bought, suggestions, autocomplete, active list,
  shopper, tag page), `ShoppingRepository`, `ShoppingWriter` (quick add with name-key matching and department memory,
  best-effort department correction across lists); `minuteTicks` and the viewer flow moved to `data/`; `seed.py shopping`
  and `seed.py tick`; 36 unit tests, 7 multi-device tests (offline ticks, realtime within 2 s, cascade, stale rights).
- 2026-10-24 M2-04 Shopping UI: the Shopping tab (`ShoppingListsScreen`: list cards with «6 из 9», progress, remaining
  names, who is shopping; «Список» FAB and «Новый список» dialog; empty state) and the list page
  (`ShoppingListScreen`, route `ShoppingListDestination`: offline banner from the sync state, progress, quick-add field,
  items by department, tap to tick, collapsed «Куплено · N», clear with undo, menu with rename, grouping, delete);
  `ShoppingLogic` texts via `UiText`; icons `category`, `remove_shopping_cart`, `add_shopping_cart`, `remove_done`;
  21 new tests (6 logic, 10 view model, 5 Compose UI).
- 2026-10-24 M2-05 Item sheet, suggestions, autocomplete and tags: `ShoppingItemSheet` on a long press of a row to buy
  or bought (name, quantity, note, «Отдел» with `ChoiceDialog` and «Запомним для всех списков», «Метки» via the shared
  `TagsSheet` and `TagEditor`, who added / bought, «Покупали N раз»; «Сохранить» sends only changed fields, then
  `setCategory`; «Убрать из списка» with undo; «Не сохранять изменения?» through a new `canClose` of `EditorSheet`);
  «+ Яйца» suggestion chips under the empty field with «Больше не предлагать»; autocomplete (debounced 150 ms, at most
  5 rows) under the field; «Метки списка» menu item, tags on cards and rows, tag filter chips (adding and clearing follow
  the filter; resets when the tag is no longer in use); `ChoiceDialog` scrolls long lists; 13 new tests (9 view model,
  4 Compose UI).

- 2026-10-24 **M2-06** Today shopping card, tag page section, manual: `TodayViewModel` combines task inputs with
  `ShoppingRepository.observeActive()` (`TodayUiState.shopping: TodayShoppingUi`, only with `shopping.view`;
  `empty` when neither block shows); `TodayShoppingBlock` — lake gradient hero card (radius 24, padding 18, count pill,
  white progress, shopper avatar with a white ring, white «Открыть» pill), all text 100 % white for contrast, «Ещё N
  списка» → Shopping tab; `TagViewModel` adds `observeForTag` — «Покупки · N» with item rows (→ list page filtered by
  the tag through the new `tagId` of `ShoppingListDestination`/`ShoppingListArgs`) and list rows, the tasks empty state
  hidden when shopping rows exist; manuals `docs/user/{en,ru}/shopping.md`; all shopping MVP requirements `done`;
  9 new tests (5 Today and 2 tag view model, 2 Compose UI); emulator walkthrough (spec scenarios 1–7, ru/en,
  light/dark) without bugs. Fixed on the way: CI flake in `ShoppingListViewModelTest` (quick add writes each entry
  separately; the test now waits for both).

- 2026-10-24 Release **v0.3.0** (M2 Shopping lists): pull request #2 merged, signed APK, image `ghcr.io/hiraise/lares-server:0.3.0`,
  GitHub release; next branch `release/0.4.0`.
