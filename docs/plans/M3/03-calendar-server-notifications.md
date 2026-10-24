---
id: M3-03
status: done
depends_on: [M3-02]
requirements: [CAL-007]
---
# M3-03: Event notifications on the server — added, changed, cancelled

## Goal

Member participants learn about events that concern them: added to an event, its date, time or place changed, an
occurrence or the event cancelled — never for their own actions, never for past events, muteable as one type
`event.changed`.

## Read first

- [Calendar design](../../architecture/calendar.md) § Notifications (the table is the specification), § Schedule logic
  (`EventSchedule.next`, `occurrence`)
- [Calendar spec](../../product/features/calendar.md) CAL-007 and its assumptions
- Code: `server/…/tasks/TaskNotifier.kt` (channel, `enqueue`, `start`, `awaitIdle`, recipient filter),
  `notifications/NotificationService.kt` (`notify`), `notifications/NotificationTexts.kt`, `platform/db/AppDatabase.kt`
  (`afterCommit`), `Application.kt` (`AppDeps`, `module()` start of `taskNotifier`), the M3-02 calendar handlers;
  tests `server/src/test/…/tasks/TaskNotificationsTest.kt`, `notifications/FakePushService.kt`

## Scope

- `calendar/EventNotifier.kt`: events `Added(householdId, eventId, occurrence: LocalDate?, actor, memberIds)`,
  `Changed(...)`, `Cancelled(..., fromDate: Boolean)`; consumer like `TaskNotifier`.
- Change detection in `EventSyncHandler` (create, update, tombstone) and `EventOccurrenceSyncHandler` (upsert): compute
  before/after inside the transaction, enqueue through `afterCommit`.
- `NotificationTexts.eventAdded/eventChanged/eventCancelled(language, title, whenText, fromDate)` and
  `calendar/EventWhenText.kt` (`format(language, occ: EventOccurrence): String`).
- `AppDeps.eventNotifier`, started in `module()` next to `taskNotifier`.

Out of scope: reminders (local, M3-04), notification taps (M3-08), the settings switch (M3-08).

## Decisions (do not revisit)

- Rules exactly as the design table. Helpers in `calendar/EventChanges.kt` (pure, unit-tested):
  - `memberParticipants(participantIds, membersByPerson)`: person ids → live member ids.
  - `scheduleChanged(old, new)`: any of `startDate`, `endDate`, `allDay`, `startTime`, `endTime`, `place`,
    `recurrence`, `repeatUntil` differ.
  - `onlyUntilEarlier(old, new)`: the only differing field is `repeatUntil` and the new one is earlier (or set from null).
  - For occurrence rows: effective `EventOccurrence` before (from the stored row, or none) and after, via
    `EventSchedule.occurrence(event, rows, date)`; `cancelled` false → true; times/place differ; added participants.
- Upcoming checks use the household zone (`TaskRepository.householdTimeZone`) and `ctx.now`:
  event-level → `EventSchedule.next(event, rows, now, zone) != null` (for delete: computed on the stored event before the
  tombstone); occurrence-level → `endMoment(occ) > now` for the old or new effective occurrence.
- The occurrence named in the text: rows → the affected occurrence (new one if moved); event create/update → `next`;
  "series cut" → the first date ≥ new `repeatUntil + 1` that was scheduled before (`firstOnOrAfter` on the old event) and
  `fromDate = true`; delete → `next` of the stored event.
- Split detection: an update with `onlyUntilEarlier` while `CalendarRepository.liveSplitOf(householdId, id)` exists → no
  notice (the create with `splitFrom`, pushed first by the client, already sent one).
- Memorable dates (`kind = MEMORABLE_DATE`) never enqueue anything.
- Recipients filtered in the consumer: not the actor, member not deleted, `calendar.view` (as `TaskNotifier`); one
  `notify` call per kind with `NotificationType.EVENT_CHANGED`, `entityType = "event"`, `entityId = eventId`.
- `EventWhenText`: weekday and month abbreviations as constant lists — ru «пн вт ср чт пт сб вс», «янв. февр. марта апр.
  мая июня июля авг. сент. окт. нояб. дек.»; en «Mon … Sun», «Jan … Dec»; timed «вт, 28 окт., 16:00» / «Tue, Oct 28,
  16:00»; all-day «вт, 28 окт.» / «Tue, Oct 28»; multi-day all-day «10–20 июля» is **not** needed (start day only).
  `fromDate` prefixes «с » / «from ».

## Steps

1. `EventWhenText`, texts, `EventChanges` helpers with unit tests.
2. `EventNotifier`, wiring, handler hooks.
3. Tests `server/src/test/…/calendar/CalendarNotificationsTest.kt` (`FakePushService`, `awaitIdle`), names start with
   CAL-007, one per design table row: added on create to the participant member with a session but not to the actor nor
   a relative; nothing for a past one-off event; added for a newly added participant on update; changed on a time
   change, none on a title-only change; split = one changed notice (create with `splitFrom` + `repeatUntil` update);
   series cut → cancelled «from»; delete → cancelled; occurrence cancelled → cancelled; occurrence moved → changed with
   the new date in the body; reset row → nothing; muted `event.changed` → nothing; member without `calendar.view` →
   nothing; memorable date → nothing; ru and en texts.

## Acceptance

- `tools/dev/verify.sh :server:test --tests '*Calendar*'` while iterating; full gate at the end.

## Docs to update

- Calendar design § Notifications only if a rule had to change; spec progress note under CAL-007 (*Server: …*; end to end
  on a real phone: pending, FND-051).
- STATUS.md; plan status.

## Stop and report if

- A row of the design table cannot be decided inside the handler's transaction (e.g. needs data the server does not have).

## Handoff

Russian summary: family members get a notification when they are added to an event, when its time or place changes and
when it is cancelled; not for their own changes and not for past events. Next M3-04.
