---
status: accepted  # design of M3; binding for the M3 plans
updated: 2026-10-24
---
# Calendar module design (M3)

Technical design for the [calendar spec](../product/features/calendar.md). Decisions behind it:
[ADR-0022](../adr/0022-event-series-with-occurrence-rows.md) (series, occurrence rows, derived ids, household time),
[ADR-0023](../adr/0023-owner-only-sync-rows.md) (personal reminders visible only to their member),
[ADR-0018](../adr/0018-tags-embedded-in-entities.md) (tags). Implemented by plans [M3-01…M3-08](../plans/README.md#m3-plans).

## Overview

- Three client-writable sync types: `event`, its children `event_occurrence` (a changed or cancelled occurrence of a
  repeating event) and `event_reminder` (one member's own reminder for an event).
- A repeating event is **one row** with a fixed `Recurrence` (the tasks rule, mode `fixed` only) and an optional
  `repeatUntil`. Occurrences are computed by shared code (`EventSchedule`), the same on the phone and on the server.
- An `event_occurrence` row overrides one scheduled date. Its id is **derived** from the event id and the date, so two
  phones changing the same occurrence offline write the same row and merge field by field. Clients never delete
  occurrence or reminder rows: "reset" writes an empty override, "no reminder for me" writes `minutesBefore = null`.
  Only the event's cascade tombstones them (ADR-0022).
- `event_reminder` rows are visible only to their member (ADR-0023); their id is derived from event and member.
- Birthdays are computed from `person` rows; memorable dates are events of kind `memorable_date` (yearly, all-day).
- Dates and times are local dates and wall-clock times in the **household time zone** (`HouseholdData.timeZone`), like
  tasks. This replaces the earlier convention "event times carry an IANA zone" (conventions § Data and values).
- Server extras: cascade tombstones, push notifications to member participants (added, changed, cancelled), the
  member's birthday reminder lead time in the notification settings. All reminders are local on each phone (FND-050).

## Contract data (`contract/…/entities/CalendarEntities.kt`)

```kotlin
@Serializable
data class EventData(
    val title: String,                                  // trimmed, 1..200
    val createdBy: String,                              // member id; set on create, never changes
    val startDate: LocalDate,
    val endDate: LocalDate,                             // ≥ startDate; all-day: last day, inclusive
    val allDay: Boolean = false,
    val startTime: LocalTime? = null,                   // required iff !allDay; whole minutes
    val endTime: LocalTime? = null,                     // required iff !allDay; (endDate, endTime) > (startDate, startTime)
    val place: String? = null,                          // trimmed, blank -> null, ≤ 200
    val notes: String? = null,                          // trimmed, blank -> null, ≤ 2000
    val participantIds: Set<String> = emptySet(),       // person ids, ≤ 30; empty = the whole family
    val tagIds: Set<String> = emptySet(),               // ≤ 20 (TAG-002, ADR-0018)
    @Serializable(with = LenientRecurrenceSerializer::class)
    val recurrence: Recurrence? = null,                 // mode FIXED only; null = one-off
    val repeatUntil: LocalDate? = null,                 // needs recurrence; ≥ startDate; last date an occurrence may start
    val reminderMinutesBefore: Int? = null,             // 0..43200; null for memorable dates
    val kind: EventKind = EventKind.EVENT,              // "event" | "memorable_date"
    val countYears: Boolean = false,                    // memorable date: show the years since startDate's year
    val splitFrom: String? = null,                      // "this and following": the series this one was cut from; create only
)

@Serializable
data class EventOccurrenceData(
    val eventId: String,                                // never changes
    val occurrence: LocalDate,                          // the scheduled start date it overrides; never changes
    val cancelled: Boolean = false,
    val startDate: LocalDate? = null,                   // times: all null = as the series; otherwise startDate and
    val endDate: LocalDate? = null,                     //   endDate set, plus startTime/endTime iff the series is timed
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val title: String? = null,                          // null = as the series
    val place: String? = null,                          // null = as the series; "" = no place for this occurrence
    val notes: String? = null,                          // null = as the series; "" = no notes for this occurrence
    val participantIds: Set<String>? = null,            // null = as the series
)

@Serializable
data class EventReminderData(
    val eventId: String,                                // never changes
    val memberId: String,                               // never changes; always the writer
    val minutesBefore: Int? = null,                     // null = no reminder for me; 0..43200
)
```

- `EventKind` uses the `ContactKind` pattern (lenient, fallback `EVENT`) with `key`/`fromKey`.
- Entity types: `EntityTypes.EVENT = "event"`, `EVENT_OCCURRENCE = "event_occurrence"`, `EVENT_REMINDER = "event_reminder"`.
- A memorable date: `kind = MEMORABLE_DATE`, `allDay`, `startDate == endDate` (the original date when the year is known,
  else this year's date), `recurrence = Recurrence(FIXED, YEAR, 1)`, no `repeatUntil`, no `reminderMinutesBefore`.
- An "empty" occurrence row (`cancelled = false`, every override field null) has no effect; it is how a reset is written.

### Derived ids (`contract/…/Ids.kt`)

```kotlin
fun occurrenceRowId(eventId: String, occurrence: LocalDate): String  // salt = occurrence.toEpochDays() as Long
fun reminderRowId(eventId: String, memberId: String): String         // salt = bytes 8..15 of memberId as a big-endian Long; marker
```

Algorithm (also implemented in `tools/dev/seed.py`): `b = Uuid.parse(eventId).toByteArray()`; XOR bytes `8..15` with the
salt's 8 big-endian bytes; for reminder rows additionally `b[7] = b[7] xor 0x5A`; set the version nibble
`b[6] = (b[6] and 0x0F) or 0x80` (version 8) and the variant `b[8] = (b[8] and 0x3F) or 0x80`; return the canonical
lowercase string. Byte 7 keeps occurrence and reminder ids of one event apart; version 8 keeps them apart from v7 ids.

## Schedule logic (`contract/…/calendar/`)

Pure functions; callers pass `today`, `now`, `zone`. `Recurrences` (tasks) gives the scheduled dates.

### `EventSchedule`

```kotlin
data class EventOccurrence(
    val eventId: String,
    val occurrence: LocalDate,          // scheduled date (one-off: startDate) — the key of an occurrence everywhere
    val startDate: LocalDate, val endDate: LocalDate,
    val startTime: LocalTime?, val endTime: LocalTime?, // null for all-day
    val title: String, val place: String?, val notes: String?,
    val participantIds: Set<String>,
    val changed: Boolean,               // an override row applies
) { val allDay: Boolean get() = startTime == null }
object EventSchedule {
    const val MAX_SPAN_DAYS = 366
    const val MAX_PER_CALL = 2000                         // guard against a daily rule over a huge range
    fun spanDays(event): Int                              // endDate − startDate
    fun isScheduled(event, date): Boolean
    fun scheduledStarts(event, from, to): List<LocalDate>
    // `eventId` names the event (EventData has no id); only rows with that eventId apply
    fun occurrences(eventId, event, rows: List<EventOccurrenceData>, from, to): List<EventOccurrence>
    fun occurrence(eventId, event, rows, date): EventOccurrence?   // of a scheduled date, null if cancelled / not scheduled
    fun next(eventId, event, rows, now, zone): EventOccurrence?    // first occurrence that has not ended at `now`
    fun lastDay(occ): LocalDate                           // timed ending at 00:00 on a later day -> endDate − 1
    fun startMoment(occ, zone): Instant?                  // timed only
    fun endMoment(occ, zone): Instant                     // all-day: lastDay + 1 at 00:00
    fun reminderAt(occ, minutesBefore, zone): Instant     // timed: start − m; all-day: startDate at DAY_START (09:00) − m
    val ORDER: Comparator<EventOccurrence>                // the sort order below; for merging occurrences of many events
}
```

| Rule | Definition |
| --- | --- |
| `isScheduled(d)` | one-off: `d == startDate`; repeating: `startDate ≤ d`, `d ≤ repeatUntil` (if set) and `Recurrences.firstOnOrAfter(rule, startDate, d) == d` |
| `scheduledStarts` | one-off: `[startDate]` if in range; repeating: `d = firstOnOrAfter(rule, startDate, from)`, then `firstOnOrAfter(d + 1)` while `d ≤ to` and `d ≤ repeatUntil`; at most `MAX_PER_CALL` |
| Applicable rows | rows of the event with `isScheduled(row.occurrence)`; one-offs ignore rows |
| Effective values | times from the row when its `startDate` is set (and consistent with `allDay`: timed rows need both times), else `(d, startTime, d + span, endTime)`; title/place/notes/participants from the row when non-null; `""` → null |
| `occurrences(from, to)` | natural dates = `scheduledStarts(from − span, to)` without dates that have a non-empty applicable row; plus every non-cancelled non-empty applicable row; keep those whose `[startDate, lastDay]` intersects `[from, to]`; sort by `startDate`, all-day first, `startTime`, title, event id |

`next()` scans `occurrences(today − span, today + 400 days)`; enough for yearly rules.

### `Birthdays`

```kotlin
object Birthdays {
    fun dateIn(year: Int, month: Int, day: Int): LocalDate   // 29 Feb -> 28 Feb in non-leap years (CAL-004)
    fun onOrAfter(month: Int, day: Int, from: LocalDate): LocalDate
    fun age(birthYear: Int?, on: LocalDate): Int?           // null if the year is unknown or the result < 1
    fun years(event: EventData, occ: LocalDate): Int?        // memorable: countYears && result ≥ 1 -> occ.year − startDate.year
    fun reminderDays(date: LocalDate, daysBefore: Int): List<LocalDate>  // date − daysBefore .. date
}
```

## Write rules (`contract/…/permissions/EventRules.kt`)

`WriteRules.canMutate` coarse gates: `EVENT` → `calendar.view`; `EVENT_OCCURRENCE`, `EVENT_REMINDER` → `calendar.view`
for `UPSERT`, **false for `DELETE`** (ADR-0022, ADR-0023).

```kotlin
object EventRules {
    fun canCreate(viewer, event): Boolean    // view && calendar.create && event.createdBy == viewer.memberId
    fun canEdit(viewer, event): Boolean      // view && (createdBy == me || calendar.edit_any); also delete, split, occurrence rows
    fun canSetReminder(viewer, reminder): Boolean // view && reminder.memberId == viewer.memberId
    fun myReminder(viewer, event, occ: EventOccurrence, own: EventReminderData?): Int?
}
```

`myReminder` (CAL-006, Q-021): `own` row present → `own.minutesBefore`; else `event.reminderMinutesBefore` if the
event's effective participants are empty or contain `viewer.personId`; else null. Memorable dates → null (birthday rules).

## Validation (`Validators.event`, `.eventOccurrence(data, event)`, `.eventReminder`; normalizers)

| Field | Codes |
| --- | --- |
| `title` | `REQUIRED`, `TOO_LONG` (200) |
| `startTime` / `endTime` | `REQUIRED` when timed; `INVALID_FORMAT` when all-day and set, or seconds ≠ 0 |
| `endDate` | `OUT_OF_RANGE` if before `startDate`, end moment not after start, or span > `MAX_SPAN_DAYS` |
| `place`, `notes` | `TOO_LONG` (200, 2000) |
| `participantIds`, `tagIds` | `OUT_OF_RANGE` above 30 / 20 |
| `recurrence` | `Validators.recurrence` + `INVALID_FORMAT` for mode `after_completion` |
| `repeatUntil` | `INVALID_FORMAT` without recurrence; `OUT_OF_RANGE` before `startDate` |
| `reminderMinutesBefore`, `minutesBefore` | `OUT_OF_RANGE` outside 0..43200 |
| `kind` = memorable | `INVALID_FORMAT` on `allDay`, `endDate`, `recurrence`, `repeatUntil`, `reminderMinutesBefore` when they break the memorable shape |
| `countYears` | `INVALID_FORMAT` when true for a plain event |
| occurrence times | `INVALID_FORMAT` unless all null or the full set for the event's kind of time; `OUT_OF_RANGE` as for events |
| occurrence `title` | `TOO_LONG`; blank → null by the normalizer (place/notes keep `""`) |

## Server

### Tables (`V8__calendar.sql`)

- `event(id uuid PK, household_id, created_by uuid → member, title, start_date date, end_date date, all_day bool,
  start_time time NULL, end_time time NULL, place NULL, notes NULL, participant_ids text '[]', tag_ids text '[]',
  recurrence text NULL (JSON), repeat_until date NULL, reminder_minutes_before int NULL, kind text, count_years bool,
  split_from uuid NULL, seq, created_at, updated_at, deleted)` + index `(household_id, seq)`.
- `event_occurrence(id uuid PK, household_id, event_id → event ON DELETE CASCADE, occurrence date, cancelled bool,
  start_date, end_date, start_time, end_time NULL, title NULL, place NULL, notes NULL, participant_ids text NULL,
  seq …)` + `(household_id, seq)` + `(event_id)`.
- `event_reminder(id uuid PK, household_id, event_id → event ON DELETE CASCADE, member_id → member, minutes_before int
  NULL, seq …)` + `(household_id, seq)` + `(event_id)`.
- `notification_preference(member_id uuid PK → member ON DELETE CASCADE, birthday_days_before int NOT NULL DEFAULT 3)`.

### Handlers (package `app.lares.server.calendar`)

- `EventSyncHandler(notifier)`, `EventOccurrenceSyncHandler(notifier)`, `EventReminderSyncHandler()`, registered after the
  shopping handlers. `authorize` uses `EventRules`; occurrence rows check `canEdit` of the stored parent
  (`requireParentEvent` → `ENTITY_NOT_FOUND` / `ENTITY_DELETED`), reminder rows `canSetReminder`.
- `checkUpsert`: `createdBy`, `splitFrom`, `kind`, child `eventId`/`occurrence`/`memberId` immutable (`INVALID_FORMAT`);
  added `participantIds` must be live persons of the household, added `tagIds` live tags (`requireAddedPersons`,
  `requireAddedTags`); `splitFrom` on create must be an event of the household (may be deleted later); the mutation id
  of a child row must equal its derived id (`id: INVALID_FORMAT`).
- `canSee`: `event`, `event_occurrence` → `calendar.view`; `event_reminder` → `calendar.view` and
  `row.data.memberId == viewer.memberId` (ADR-0023).
- Cascade: tombstoning an event tombstones its live occurrence and reminder rows, each with its own seq (as tasks).
- Housekeeping purges `event_reminder`, `event_occurrence`, then `event`.
- `GET/PUT /me/notification-settings`: `NotificationSettings` gets `birthdayDaysBefore: Int = 3` (one of 0, 1, 3, 7, 14;
  else `VALIDATION_FAILED`), stored in `notification_preference`; the server never uses it.

### Notifications (`EventNotifier`, CAL-007)

New `NotificationType`s: `event.changed` (channel `family_activity`; one type for added / changed / cancelled, one
switch), `event.reminder` and `birthday.reminder` (channel `reminders`, local only, stored for muting).

Recipients: live members whose `personId` is in the event's effective participants, with `calendar.view`, never the
actor. Memorable dates never notify. "Upcoming" = the affected occurrence has not ended (`now` in household zone).

| Write | Notice |
| --- | --- |
| event create, no `splitFrom`, has an upcoming occurrence | **added** to all participant members |
| event create with `splitFrom` | **changed** to members who were participants of the origin, **added** to the others |
| event update | **added** to newly added participant members; **changed** to the remaining ones when `startDate`, `endDate`, `allDay`, times, `place`, `recurrence` or `repeatUntil` changed — except an update that only moves `repeatUntil` earlier while a live event with `splitFrom` = this id exists (the split's second half) |
| update that only moves `repeatUntil` earlier (no split) | **cancelled** «from {date}» to participant members |
| event delete | **cancelled** to participant members, if it had an upcoming occurrence |
| occurrence row: `cancelled` false → true | **cancelled** for that occurrence, if upcoming |
| occurrence row: effective times or place differ from before | **changed**, if the old or new occurrence is upcoming |
| occurrence row: effective participants gain members | **added** to them |
| occurrence row that becomes empty (reset) | nothing |

The occurrence the text names: the affected occurrence for rows; for events `EventSchedule.next`. Texts
(`NotificationTexts`, en/ru, title shortened to 100), `{when}` = `EventWhenText.format(language, occ)`:
ru «вт, 28 окт., 16:00» / «вт, 28 окт.» (all-day), en «Tue, Oct 28, 16:00» / «Tue, Oct 28» (fixed month and weekday
abbreviations in code, household zone).

| Kind | Title en / ru | Body |
| --- | --- | --- |
| added | New event / Новое событие | `{title} · {when}` |
| changed | Event changed / Событие изменено | `{title} · {when}` |
| cancelled | Event cancelled / Событие отменено | `{title} · {when}`; series cut: `{title} · from {when}` / `{title} · с {when}` |

`entityType = "event"`, `entityId = eventId`; an occurrence notice adds nothing else (the tap opens the next occurrence).

## Client

### Local tables (`4.sqm`, schema 5, `DATA_SCHEMA_VERSION = 4`)

`event(id PK, title, created_by, start_date TEXT, end_date TEXT, all_day, start_time TEXT NULL, end_time TEXT NULL,
place, notes, participant_ids TEXT (JSON), tag_ids TEXT (JSON), recurrence TEXT NULL, repeat_until TEXT NULL,
reminder_minutes_before INTEGER NULL, kind TEXT, count_years INTEGER, split_from TEXT NULL)`;
`event_occurrence(id PK, event_id, occurrence TEXT, cancelled, start_date, end_date, start_time, end_time, title, place,
notes, participant_ids TEXT NULL)` + index on `event_id`; `event_reminder(id PK, event_id, member_id, minutes_before
INTEGER NULL)` + index on `event_id`; `notification_pref(key TEXT PK, value TEXT)` (key `birthday_days_before`).

### Adapters

`EventAdapter`, `EventOccurrenceAdapter`, `EventReminderAdapter` mirror the server handlers (same `EventRules`, same
immutable fields, derived-id check, `VALIDATION_FAILED` codes; missing parent → `ENTITY_NOT_FOUND`).

### Models and views (`data/calendar/`)

`CalendarInputs(events, occurrenceRows, myReminders, persons, members, tags, tasks: List<TaskView>, zone, viewer, now)`;
tasks only when the viewer has `tasks.view` (from `TasksRepository.observeViews()`).

```kotlin
sealed interface CalendarItem {
    data class Event(val occ: EventOccurrence, val kind: EventKind, val participants: List<Person>, val tags: List<Tag>,
                     val repeating: Boolean, val years: Int?, val continuesBefore: Boolean, val continuesAfter: Boolean) : CalendarItem
    data class Birthday(val person: Person, val date: LocalDate, val age: Int?) : CalendarItem
    data class Task(val view: TaskView, val overdue: Boolean) : CalendarItem
}
data class CalendarFilter(val personId: String? = null, val tagId: String? = null, val showTasks: Boolean = true)
```

| Function (`CalendarViews`, pure) | Result |
| --- | --- |
| `days(inputs, from, to, filter)` | `List<DayView(date, items)>` for every date in range; items: birthdays, memorable dates, all-day events (by title), then timed events and timed tasks by time, then untimed tasks. Multi-day events on every day they cover with `continuesBefore/After`. Tasks: the current occurrence of open tasks on its date; overdue ones and tasks done today on today |
| `month(inputs, year, month, filter)` | 42 `MonthCell(date, inMonth, today, dots: List<PaletteColor?>, more: Boolean)` from the Monday on or before day 1; dots = distinct participant colors of events and birthdays (null = whole family, drawn `primary`), at most 3, `more` beyond; tasks give no dots |
| `week(inputs, monday, filter)` | 7 `WeekDay(date, allDay: List<CalendarItem>, blocks: List<TimedBlock(item, startMinute, endMinute, column, columns)>)`: timed events clipped to the day; overlapping blocks clustered, columns assigned greedily by start, `columns` = cluster width; tasks excluded |
| `upcoming(inputs)` | Today «Скоро»: items of today … today + 7 without tasks, timed events that have not ended, max 5 + `more` count (CAL-019) |
| `forTag(inputs, tagId)` | next occurrence of each event with the tag within 365 days, sorted |
| `forPerson(inputs, personId)` | the person's next birthday and their memorable dates (event ids, next date, years) |
| `detail(inputs, eventId, occurrence?)` | `EventDetail`: effective occurrence (given date, else `next`), event, participants, tags, creator name, rule, `myReminder`, `ownReminderSet`, `canEdit`, `changedCount` (non-empty rows) |

Filter: person → events whose effective participants contain the person **or are empty** (whole family), their
birthday, tasks assigned to their member (or "anyone"); tag → events and tasks with the tag (no birthdays).

`CalendarRepository(localDb, members, persons, tags, household, permissions, tasks: TasksRepository, clock, ticks)`:
`observeInputs()`, `observeDays(from, to, filter)`, `observeMonth(year, month, filter)`, `observeWeek(monday, filter)`,
`observeUpcoming()`, `observeForTag(tagId)`, `observeForPerson(personId)`, `observeDetail(eventId, occurrence)`.

### Writer (`CalendarWriter(writer, localDb, clock)`, all through `LocalWriter`)

| Function | Writes |
| --- | --- |
| `create(draft): String` | the event (`createdBy` = me) |
| `update(id, newData)` | changed fields only; if `startDate`, `allDay` or `recurrence` changed, every non-empty occurrence row is reset (empty upsert) after the event |
| `editOccurrence(eventId, occurrence, changes)` | the derived row with the full override (fields equal to the series written as null) |
| `cancelOccurrence(eventId, occurrence)` | the derived row with `cancelled = true` |
| `editFollowing(eventId, from, newData)` | `from` = first scheduled date → `update`; else create the new series (`newData`, `splitFrom = eventId`, new id), then update the old `repeatUntil = from − 1`, then reset its non-empty rows with `occurrence ≥ from` |
| `deleteFollowing(eventId, from)` | `from` = first date → `delete`; else `repeatUntil = from − 1` + reset rows `≥ from` |
| `delete(eventId)` | tombstone (cascade on the server) |
| `setMyReminder(eventId, minutes: Int?)` / `clearMyReminder(eventId)` | the derived reminder row (`null` = no reminder for me); «Как в событии» writes the event default if I would get it, else null |

Personal reminders are not carried to the second half of a split (ASSUMPTION; documented in the manual).

### Reminders (`CalendarReminderPlanner`)

Like `TaskReminderPlanner`: reconciles stored `reminder` rows for prefixes `event:`, `birthday:`, `memorable:` 1 s
after a change of the calendar inputs, the mute set or `birthdayDaysBefore`, and from `SyncWorker`.

- Events: for every occurrence in `[now, now + 14 days]` with `myReminder` set and `reminderAt` in the future, id
  `event:<eventId>:<occurrence>`; at most 100, nearest first. Muted by `event.reminder`.
- Birthdays (not my own person) and memorable dates: for each date in `reminderDays(date, birthdayDaysBefore)` at
  09:00, in the future and ≤ 14 days ahead: ids `birthday:<personId>:<day>` / `memorable:<eventId>:<day>`. Muted by
  `birthday.reminder`.
- Texts (`CalendarReminderTexts`, English fallback in the core, resources in `uiModule()`): event — title = event title,
  body «Сегодня в 16:00 · Клиника на Ленина» / «Завтра, весь день»; birthday — title «День рождения: Бабушка Валя»,
  body «Через 3 дня, исполнится 78» / «Сегодня, 78 лет»; memorable — title = event title, body «Через 3 дня · 16 лет».
- `Reminders.target` maps `event:` → `NotificationTarget("event", "<eventId>:<occurrence>")`, `birthday:` →
  `("person", personId)`, `memorable:` → `("event", eventId)`.

### Notification settings

`NotificationSettingsRepository` gets `birthdayDaysBefore: Flow<Int>` (local `notification_pref`, default 3) and
`setBirthdayDaysBefore(days)` (PUT with the full settings, like `setMuted`). «Не напоминать» in the UI = mute
`birthday.reminder`; any other choice unmutes it and stores the days.

## UI (M3-05…M3-08)

- **Calendar tab** (`ui/calendar/`, replaces `SectionPlaceholder`): header «Октябрь 2026» with ‹ › (mockup
  `Calendar.dc.html`), `today` action when away from the current period, `filter_list` action; `SegmentedControl`
  «Месяц · Неделя · Список» (remembered in `AppPreferences`); active filters as removable chips; `GradientFab` (+) for
  `calendar.create`.
- **Month:** `HorizontalPager` of months; 7 × 6 grid (cell 44 dp, number in a 32 dp circle — today `primary` filled,
  selected outlined; other months `onSurfaceVariant` 50 %; dots 5 dp); under it the selected day «Пятница, 23 октября»
  with its items (`DayList`).
- **Week:** `HorizontalPager` of weeks; all-day strip, then a vertically scrolling 24 h grid (48 dp per hour, starts
  scrolled to 07:00 or the first event), 7 columns; blocks in the first participant's color tone (`tones().container`),
  title only; a tap opens the event, a tap on an empty hour creates one at that hour.
- **List:** `LazyColumn` of non-empty days from today (90 days, extended when the end is near; «Показать прошлые» on top
  loads 30 days back); day headers «Сегодня», «Завтра», «Суббота, 24 октября».
- **Rows:** event — time column («17:30 / 18:45» or «Весь день»), title (+ `repeat` 14 dp if repeating), a meta line
  «Аня · ДК «Юность»», participant avatars (24 dp, up to 3); a 3 dp side bar in the first participant's color
  (`primary` for the whole family). Birthday — `cake` icon in the person's color, «Бабушка Валя, 78 лет», relation.
  Memorable — `celebration`, «Годовщина свадьбы · 16 лет». Task — `TaskCheckbox` + title + «Задача · Дмитрий».
- **Event page** (`EventDestination(id, occurrence?)`): title, facts list (when, repeat, place, participants, tags, notes,
  «Напомнить мне», «Создал(а)»), edit / delete; repeating → «Только это · Это и следующие · Все» before editing or
  deleting. Birthdays open the person.
- **Editor** (`EventEditorDestination(id?, occurrence?, scope, date?, hour?, memorable, personId?, tagId?)`): «Событие ·
  Памятная дата» on create; title; «Весь день»; start and end rows (date + time; changing the start keeps the duration);
  repeat (shared repeat options without "after completion") + «До»; participants sheet (persons, «Вся семья» = none);
  place; reminder; tags; notes. Scope «Только это» hides repeat, reminder and tags. Saving «Все» with a schedule change
  over changed occurrences asks first.
- **Today «Скоро»** after the tasks block; **tag page «События»** after shopping; **person page** «Даты» with the next
  birthday (age) and memorable dates + «Добавить памятную дату»; **Settings › Notifications** switches «События
  календаря» (`event.changed`), «Напоминания о событиях» (`event.reminder`) and the row «Дни рождения» (choice).
