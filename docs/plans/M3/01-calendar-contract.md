---
id: M3-01
status: done
depends_on: [M2-06]
requirements: [CAL-001, CAL-002, CAL-003, CAL-004, CAL-005, CAL-006, CAL-013, CAL-015, CAL-016, TAG-002]
---
# M3-01: Calendar contract — data, derived ids, schedule, birthdays, write rules, validators

## Goal

All shared code of the calendar in `:contract`: entity data classes, derived row ids, occurrence computation with
overrides, birthday and memorable-date arithmetic, permission rules, validators and the new notification types and
settings field. Pure Kotlin, no I/O, fully unit-tested; used unchanged by the server (M3-02, M3-03) and the client (M3-04).

## Read first

- [Calendar design](../../architecture/calendar.md) — **binding**; § Overview, § Contract data (with § Derived ids),
  § Schedule logic, § Write rules, § Validation, § Notifications (only the new types)
- [ADR-0022](../../adr/0022-event-series-with-occurrence-rows.md), [ADR-0023](../../adr/0023-owner-only-sync-rows.md)
- [Calendar spec](../../product/features/calendar.md) § Requirements, § Permissions and privacy
- [Tasks design § Schedule logic](../../architecture/tasks.md#schedule-logic-contracttasks) (the `Recurrences` rules reused here)
- Existing contract code: `entities/TaskEntities.kt` (`Recurrence`, `LenientRecurrenceSerializer`),
  `entities/Entities.kt` (`ContactKind` lenient-with-fallback pattern, `PersonData` birth fields), `Ids.kt`,
  `tasks/Recurrences.kt`, `tasks/TaskSchedule.kt` (`DAY_START`), `permissions/WriteRules.kt`, `permissions/TaskRules.kt`,
  `validation/Validators.kt` (task validators, `ValidationErrors`, `birthDate`), `sync/SyncDtos.kt` (`EntityTypes`),
  `api/notifications/NotificationDtos.kt`; tests `commonTest/…/tasks/RecurrencesTest.kt`, `permissions/TaskRulesTest.kt`,
  `validation/TaskValidatorsTest.kt`

## Scope

- `entities/CalendarEntities.kt`: `EventData`, `EventOccurrenceData`, `EventReminderData`, `EventKind` exactly as in the
  design (field order as listed — new fields only ever appended).
- `sync/SyncDtos.kt`: `EntityTypes.EVENT`, `EVENT_OCCURRENCE`, `EVENT_REMINDER`.
- `Ids.kt`: `occurrenceRowId`, `reminderRowId`.
- Package `app.lares.contract.calendar`: `EventSchedule.kt` (with `EventOccurrence`), `Birthdays.kt`.
- `permissions/EventRules.kt`; `WriteRules.canMutate` gates (and KDoc).
- `validation/Validators.kt`: `event`, `eventOccurrence(data, event)`, `eventReminder`, `normalizeEvent`,
  `normalizeEventOccurrence` + constants.
- `api/notifications/NotificationDtos.kt`: `NotificationType.EVENT_CHANGED` (`event.changed`, `FAMILY_ACTIVITY`),
  `EVENT_REMINDER` (`event.reminder`, `REMINDERS`), `BIRTHDAY_REMINDER` (`birthday.reminder`, `REMINDERS`);
  `NotificationSettings.birthdayDaysBefore: Int = 3` (appended) and `BIRTHDAY_DAYS_CHOICES = listOf(0, 1, 3, 7, 14)`.
- [Conventions § Data and values](../../process/conventions.md#data-and-values): the time line becomes "UTC instants; task
  and event dates and times are wall-clock values in the household time zone (ADR-0022); birthdays are local dates".

Out of scope: server tables and handlers (M3-02), notification sending (M3-03), client (M3-04), UI strings.

## Decisions (do not revisit)

- `EventKind` wire names `event`, `memorable_date`; `@KeepGeneratedSerializer` + `LenientEnumSerializer` with fallback
  `EVENT`; `key` + `fromKey` like `ShoppingItemStatus`.
- `EventData.recurrence` reuses `LenientRecurrenceSerializer`; `EventOccurrenceData.participantIds` is a nullable
  `Set<String>`; `LocalDate`/`LocalTime` default ISO serializers.
- Derived ids exactly as design § Derived ids, with `kotlin.uuid.Uuid` (`@OptIn(ExperimentalUuidApi::class)`,
  `toByteArray`/`fromByteArray`). Salt for occurrences: `occurrence.toEpochDays().toLong()`; for reminders: bytes 8..15
  of `Uuid.parse(memberId)` read big-endian. The marker `0x5A` on byte 7 only for reminder ids. Invalid input UUIDs
  throw `IllegalArgumentException` (callers validate ids first).
- `EventSchedule` API, rules and sort order exactly as the design table. `scheduledStarts` uses
  `Recurrences.firstOnOrAfter` only (no day-by-day loops) and stops at `MAX_PER_CALL`. An applicable row is
  "non-empty" if `cancelled` or any override field is non-null. A timed row whose `startTime`/`endTime` are missing, or an
  all-day row with times, contributes no times (title/place/notes/participants still apply).
- `EventSchedule.next(event, rows, now, zone)`: the first occurrence from `occurrences(today − span, today + 400)` whose
  `endMoment > now`.
- `Birthdays` exactly as in the design; `age` = `on.year − birthYear` (no month check needed: callers pass the birthday
  occurrence date); `years` = `occ.year − event.startDate.year` for memorable dates with `countYears`, null if < 1.
- `EventRules` exactly as in the design; `myReminder` takes the effective occurrence's `participantIds`.
- `WriteRules.canMutate`: `EVENT` → `CALENDAR_VIEW`; `EVENT_OCCURRENCE`, `EVENT_REMINDER` → `op == UPSERT &&
  CALENDAR_VIEW`.
- Validator constants: `EVENT_TITLE_MAX = 200`, `EVENT_PLACE_MAX = 200`, `EVENT_MAX_PARTICIPANTS = 30`,
  `EVENT_MAX_TAGS = 20`; notes use `NOTES_MAX`, minutes `REMINDER_MINUTES_MAX`, spans `EventSchedule.MAX_SPAN_DAYS`.
  Codes as the design table. The memorable shape check reports each offending field once.
- `normalizeEvent`: trim title; blank place/notes → null; all-day → times null. `normalizeEventOccurrence`: trim title,
  blank → null; place/notes trimmed, but `""` kept (it means "none for this occurrence").

## Steps

1. Data classes, enum, entity types, derived ids.
2. `EventSchedule`, `Birthdays`.
3. `EventRules`, `WriteRules` gates; validators and normalizers.
4. Notification types and the settings field; conventions line.
5. Tests (`contract/src/commonTest`, names start with requirement ids):
   - `CalendarEntitiesTest`: round trips of the three data classes; missing optional fields → defaults; unknown kind →
     `EVENT`; unknown recurrence unit → `null`.
   - `IdsTest`: derived ids are stable (golden values for a fixed event id, date and member id — write them into the
     test and copy them into the M3-04 `seed.py` check), version 8, RFC variant, lowercase canonical; different dates →
     different ids; occurrence id ≠ reminder id for every salt in a loop of 1000 dates.
   - `EventScheduleTest` (CAL-003, CAL-013, CAL-015): one-off; weekly Mon/Wed with `repeatUntil`; monthly on the 31st;
     yearly 29 Feb; a 3-day all-day event shows in a range that starts on its second day; a timed event 23:00–01:00
     covers two days, one ending at 00:00 covers one; a moved occurrence leaves its date and appears in the new range;
     a cancelled one disappears; an empty row changes nothing; a row for an unscheduled date is ignored; place `""` →
     null; participants override; `MAX_PER_CALL` cap on a daily rule over 20 years; `next` skips an ended occurrence;
     `reminderAt` for timed and all-day; sort order.
   - `BirthdaysTest` (CAL-004, CAL-005, CAL-016): 29 Feb in leap and non-leap years, `onOrAfter` across New Year,
     `age` known/unknown/zero, `years`, `reminderDays(…, 0)` and `(…, 3)`.
   - `EventRulesTest`: Adults / Kids / view-only / admin × create (own and foreign `createdBy`), edit own and others',
     reminder for me and for another member; `myReminder` for participant, non-participant, whole family, own row with
     null, memorable date.
   - `WriteRulesTest`: the three gates incl. delete false for the child types.
   - `EventValidatorsTest`: every row of the design validation table; normalizers.
   - `NotificationDtosTest` (or the existing settings test): unknown `birthdayDaysBefore` missing → 3; the new keys.

## Acceptance

- `tools/dev/verify.sh :contract:check`; then the full gate `tools/dev/verify.sh` (server and client still compile; the
  client's exhaustive `when` over `NotificationType` in `ui/settings/NotificationSettings.kt` needs labels — add
  `notif_type_event_changed`, `notif_type_event_reminder`, `notif_type_birthday_reminder` strings en/ru now:
  «События календаря», «Напоминания о событиях», «Дни рождения» / "Calendar events", "Event reminders", "Birthdays";
  do **not** add them to `SwitchableNotificationTypes` yet — M3-08 does).

## Docs to update

- Calendar design only if a detail had to change (explain in the handoff); conventions line above.
- STATUS.md; plan status.

## Stop and report if

- `kotlin.uuid.Uuid` byte conversion is not available in the Kotlin version of `gradle/libs.versions.toml`.
- A design rule of `EventSchedule` contradicts a test case listed above.

## Handoff

Russian summary: the shared calendar rules are ready — how repeating events produce their dates, how a single changed
or cancelled occurrence is applied, birthdays with ages and 29 February, memorable dates with year counts, who may do
what. Next plan M3-02.
