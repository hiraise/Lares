---
id: M3-04
status: done
depends_on: [M3-02]
requirements: [CAL-001, CAL-002, CAL-003, CAL-004, CAL-005, CAL-006, CAL-008, CAL-009, CAL-013, CAL-015, CAL-016, CAL-017, CAL-019, TAG-002, TAG-003, TAG-004, FND-040, FND-050]
---
# M3-04: Calendar in the client core — tables, adapters, views, repository, writer, reminders

## Goal

Everything below the UI on the phone: events, occurrence rows and own reminders sync and work offline; pure view
functions compute the month grid, the week grid, day lists with birthdays and tasks, Today's «Скоро», the tag page and
the person's dates; one writer covers every action (this / following / all); local reminders for events, birthdays and
memorable dates; the birthday lead-time setting. Proven by unit and multi-device tests.

## Read first

- [Calendar design](../../architecture/calendar.md) § Overview, § Client (all), § Schedule logic (API only), § Write rules
- [ADR-0022](../../adr/0022-event-series-with-occurrence-rows.md), [ADR-0023](../../adr/0023-owner-only-sync-rows.md)
- [Sync protocol § client data model](../../architecture/sync.md#client-data-model-sqlite); [conventions § Client](../../process/conventions.md#client)
- Code — tasks and shopping cores are the template: `client/…/sync/EntityAdapter.kt`, `sync/Adapters.kt`
  (`TaskAdapter`, `ChecklistItemAdapter`, `changedFields`, `defaultEntityAdapters`), `sync/LocalWriter.kt`,
  `sync/SyncState.kt`, `db/LocalDb.kt` (`clearAll`), `sqldelight/…/task.sq`, `reminder.sq`, `notification_mute.sq`,
  `migrations/3.sqm`; `data/Repositories.kt`, `data/Ticks.kt`, `data/Viewers.kt`, `data/tasks/TasksRepository.kt`
  (`householdZone`), `TaskViews.kt`, `TasksWriter.kt`, `TaskReminderPlanner.kt`; `notifications/Reminders.kt`
  (`target`), `notifications/Notifications.kt` (`NotificationSettingsRepository`), `NotificationsCoordinator.kt`,
  `NotificationsModule.kt`, the Android `SyncWorker`; `di/Modules.kt`; `ui/common/TaskReminderResourceTexts.kt`,
  `ui/UiModule.kt`; tests `jvmTest/…/data/tasks/TaskFixture.kt`, `TasksWriterTest.kt`, `TaskViewsTest.kt`,
  `TaskReminderPlannerTest.kt`, `db/MigrationTest.kt`, `integration-tests/…/Harness.kt`, `TaskSyncTest.kt`;
  `tools/dev/seed.py` (`tasks()`, `shopping()`, `push`, `uuid7`)

## Scope

- SQLDelight `event.sq`, `event_occurrence.sq`, `event_reminder.sq`, `notification_pref.sq` (columns per design;
  queries `selectAll`, `selectById`, `selectByEvent`, `upsert`, `deleteById`, `deleteAll`; `notification_pref`:
  `get(key)`, `put`, `deleteAll`); `migrations/4.sqm` with the same DDL; `LocalDb.clearAll`; `DATA_SCHEMA_VERSION = 4`.
- `EventAdapter`, `EventOccurrenceAdapter`, `EventReminderAdapter` in `Adapters.kt`, registered last.
- Package `data/calendar/`: `CalendarModels.kt`, `CalendarViews.kt` (pure), `CalendarRepository.kt`,
  `CalendarWriter.kt`, `CalendarReminderPlanner.kt` (+ `CalendarReminderTexts`, `EnglishCalendarReminderTexts`); Koin
  `single`s in `sharedModule`; `ui/common/CalendarReminderResourceTexts.kt` bound in `uiModule()` with the strings it
  needs (en/ru).
- `NotificationSettingsRepository`: `birthdayDaysBefore: Flow<Int>`, `setBirthdayDaysBefore(days)`; `refresh()` stores
  the server value.
- `NotificationsCoordinator` starts/stops the calendar planner like the task planner; `SyncWorker` calls its
  `reconcile()` after the task one. `Reminders.target` gets the `event:`, `birthday:`, `memorable:` branches.
- `tools/dev/seed.py calendar` (+ the `emulator` skill command list line).
- `integration-tests`: `TestClient.calendar`, `calendarWriter`; the snapshot includes the three tables.

Out of scope: screens (M3-05…M3-08), notification tap navigation (`PendingNavigation`, M3-08).

## Decisions (do not revisit)

- Models and `CalendarViews` functions exactly as design § Models and views; tasks enter as `TaskView`s from
  `TasksRepository.observeViews()` (empty list without `tasks.view`). A "Task" item: open tasks at their current occurrence
  date; overdue ones (`TaskGroup.OVERDUE`) and tasks done today shown on today (`overdue = true` only for the former).
- `days()` builds each day from `EventSchedule.occurrences(event, rowsOf(event), from, to)` once per event for the whole
  range (not per day). Unknown person/tag ids are dropped. Persons come from `PersonsRepository.observeAll()`; their
  birthdays need `birthMonth` and `birthDay`.
- `week()` column layout: per day, timed blocks sorted by (start, −duration); a cluster is a maximal run of transitively
  overlapping blocks; each block takes the lowest free column; `columns` = max columns used in its cluster. Blocks
  shorter than 30 minutes count as 30 for overlap and height.
- `CalendarRepository(localDb, members, persons, tags, household, permissions, tasks, clock, ticks = minuteTicks(clock))`
  shaped like `TasksRepository`; `distinctUntilChanged()` on every output; zone via `householdZone`.
- `CalendarWriter` functions and write rules exactly as design § Writer. The `changes` argument of `editOccurrence` is
  `data class OccurrenceChanges(startDate, endDate, startTime, endTime, title, place, notes, participantIds)` (all
  nullable = as the series); the writer compares each with the series and writes null where equal. Reset = upsert of
  `{eventId, occurrence, cancelled: false}` and all override fields `null`. `editFollowing` order: new event first, then
  the old event, then the resets — one `LocalWriter` call each.
- `setMyReminder(eventId, minutes)`: `minutes` is `Int?` where null = "no reminder for me"; `clearMyReminder(eventId)`
  writes `minutesBefore = event.reminderMinutesBefore` only if I would get the event default (participant / whole
  family), else null — the UI uses it for «Как в событии». No row is ever deleted.
- Adapters: as design § Adapters; `authorize` mirrors the M3-02 handlers exactly (same rule calls, same derived-id check,
  `LocalWriteException(VALIDATION_FAILED)` with the field map); `checkDelete` of the child adapters is never reached
  (gate false).
- `CalendarReminderPlanner(repository, localDb, reminders, settings, texts, clock)` exactly as design § Reminders;
  horizon 14 days, `MAX_EVENT_REMINDERS = 100`; reconciles all three prefixes in one pass under a `Mutex`; channel
  `REMINDERS`; 09:00 = `TaskSchedule.DAY_START` in the household zone.
- `seed.py calendar` (after `family`; idempotent through `state["calendar"]`), as olga, dates relative to today (UTC+3):
  - relatives (person upserts, `color` omitted): «Бабушка Валя» (relation «Бабушка», born today + 3 days, 1948),
    «Дядя Коля» (relation «Дядя», born today + 12 days, year unknown); Аня's person gets a birthday today + 40 days, 2015;
  - tag «Школа» (created if missing);
  - «Стоматолог»: tomorrow 16:00–17:00, Тимур, place «Клиника на Ленина», reminder 60;
  - «Танцы»: weekly Mon+Wed 17:30–18:45 from the last Monday, until today + 180 days, Аня, place «ДК «Юность»», with the
    next Wednesday's occurrence cancelled and the following Monday's moved to 18:00–19:15 (occurrence rows with derived
    ids — implement `occurrence_row_id` in Python exactly as design § Derived ids and assert it reproduces the golden
    value of the M3-01 `IdsTest`);
  - «Родительское собрание»: today + 4 days 18:30–19:30, Ольга and Дмитрий, place «Школа №12», tag «Школа»;
  - «Генеральная уборка»: next Saturday 11:00–13:00, no participants;
  - «Отпуск»: all-day today + 20 … today + 30, all four;
  - «Годовщина свадьбы»: memorable date on today + 10 days in 2010, `countYears`, Ольга and Дмитрий.
  Instants and dates in canonical form; `"weekdays"` ISO names; `push` once.

## Steps

1. Tables, migration, clear-all, schema version; `MigrationTest`: the two existing tests also drop the calendar tables,
   a new 4 → 5 test (`LaresDb.Schema.version == 5L`, tables and indexes `event%` like the shopping one).
2. Adapters.
3. Models, `CalendarViews`, repository, writer; Koin.
4. Settings repository field; reminder planner, texts, coordinator, `SyncWorker`, `Reminders.target`.
5. `seed.py calendar`; skill command list.
6. Tests (jvmTest, names start with requirement ids):
   - `CalendarFixture` (copy of `TaskFixture` with persons and a `putEvent/putRow/putReminder` helper).
   - `CalendarViewsTest` (pure): `days` ordering (birthdays, memorable, all-day, timed with tasks, untimed tasks), a
     multi-day event on each day with `continuesBefore/After`, moved and cancelled occurrences, overdue task on today;
     `month` 42 cells starting Monday, dots distinct, `null` for whole family, `more` after 3, birthday dot; `week`
     clipping across midnight and the column layout (three overlapping blocks → 3 columns; A–B overlap, C after → 2 and 1);
     filters (person incl. whole-family events and their birthday and assigned tasks; tag; `showTasks = false`);
     `upcoming` (ended timed event excluded, max 5 + more); `forTag`; `forPerson`; `detail` (`myReminder` cases,
     `changedCount`); unknown ids dropped; Kids see `canEdit = false` on others' events.
   - `CalendarWriterTest` (in-memory DB): outbox for each function; `update` with a recurrence change resets non-empty
     rows only; `editOccurrence` writes null for unchanged fields and the derived id; `editFollowing` on the first date =
     plain update, on a later date = new event with `splitFrom` + `repeatUntil` + resets of rows ≥ date only;
     `deleteFollowing` both cases; `setMyReminder` / `clearMyReminder`; Kid cannot edit an adult's event locally
     (`LocalForbiddenException`, nothing enqueued); deleting a child row locally is forbidden.
   - `CalendarReminderPlannerTest` (`RecordingAlarms` like the tasks test): participant gets the event reminder, a
     non-participant only with an own row; own row null cancels; whole-family event; 14-day horizon and the 100 cap;
     birthday reminders on D−3…D at 09:00 with the default, none for my own birthday, `birthdayDaysBefore = 0` → only D;
     muted `birthday.reminder` / `event.reminder` cancel theirs only; memorable dates follow the birthday setting;
     task reminders (`task:` prefix) untouched.
   - integration-tests `CalendarSyncTest` (`familyTest { }`): two clients change different fields of the same occurrence
     offline → one row, both changes (ADR-0022); one client edits the series time while another cancels an occurrence
     offline → both survive; split on one client while the other is offline → converged; an event deleted while another
     client edits an occurrence offline → gone everywhere; boris's own reminder never reaches anna's client; a Kid's
     edit of an adult's event through stale permissions → rejected and reverted (invariant 3); a member without
     `calendar.view` has no event rows after reset.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest` and `:integration-tests:test` while iterating; full gate at the end.
- `tools/dev/server.sh reset && tools/dev/seed.py family && tools/dev/seed.py tasks && tools/dev/seed.py calendar` runs
  without errors and `seed.py call GET /sync/changes` lists the occurrence rows with the derived ids.

## Docs to update

- sync.md § client data model (typed tables list); calendar design if a detail changed; spec progress notes
  (*Client core: …*) under the requirements covered; `emulator` skill command list.
- STATUS.md; plan status.

## Stop and report if

- A view needs the occurrences of more than ~2 years per event to answer a design function (performance assumption).
- The writer needs a multi-row atomic local write to keep data consistent (the design assumes it does not).

## Handoff

Russian summary: the phone now keeps the calendar offline — repeating events with single changes and cancellations,
"this and following", birthdays with ages, memorable dates, tasks in the day lists, personal reminders and birthday
reminders by the chosen lead time; tested with simulated phones editing the same occurrence offline. Next M3-05.
