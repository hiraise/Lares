---
status: approved  # customer, 2026-10-24 (Q-021…Q-024 answered the same day)
milestone: M3
updated: 2026-10-25
---
# Calendar (CAL)

## Summary

The family calendar: events, birthdays and memorable dates. Each event says whom it concerns — members and relatives
(persons) — and is marked with their personal colors. Repeating events can be changed or cancelled one occurrence at a
time. Reminders come from the event to its participants, and everybody can set their own (Q-021). Birthdays come from the
persons' profiles and are reminded several days ahead (Q-022). Tasks with a date appear in the calendar too (Q-023).
Everybody with access to Calendar sees all events of the family.
Technical design: [architecture/calendar.md](../../architecture/calendar.md).

## Scenarios

1. Mom creates «Стоматолог» on Tuesday 16:00–17:00 for Тимур, place «Клиника на Ленина», reminder 1 hour before. Тимур
   gets «Новое событие: Стоматолог, вт 16:00»; at 15:00 his phone reminds him. Mom drives him, so on the event page she
   sets «Напомнить мне» 2 hours before for herself.
2. Аня's «Танцы» repeat on Monday and Wednesday 17:30–18:45 until 31 May. One Wednesday the class is cancelled: Mom
   deletes only that occurrence. From November the class starts at 18:00: Mom edits «Это и следующие».
3. Бабушка Валя's profile has her birthday, 26 October 1948. The calendar shows «Бабушка Валя, 78 лет» every year; three
   days before, and then every morning until the day, everybody gets a reminder (Q-022). Dad wants a week's notice and
   changes his own setting.
4. Mom and Dad's wedding anniversary (since 2010) is a memorable date: once a year, «Годовщина свадьбы · 16 лет».
5. The family holiday 10–20 July is one all-day event for all four; it spans every day of the period.
6. Dad opens the month: dots in personal colors show who has something on which day; he taps a day and sees its list.
   He filters by Тимур and sees only Тимур's events and birthday.
7. In the week grid Mom sees when everybody is busy and finds a free evening for the parents' meeting.
8. In «Список» the paid-internet task due Friday sits between Friday's events; Dad ticks it off right there.
9. Today shows «Скоро»: «Бабушка Валя, 78 лет — понедельник, через 3 дня» and «Родительское собрание — среда, 18:30».

## Requirements

### Events

- **CAL-001** `MVP` `todo` — Event: title (required), all-day or with a start and end time, place (text), notes, tags.
  A new timed event lasts one hour by default; the end is after the start.
  *Server: storage, sync, validation and permissions done (M3-02).*
  *Client core: local tables, adapters with the server's checks, `CalendarWriter.create/update/delete` (M3-04).*
  *UI: the event editor — title, «Весь день», start and end with date and time (a new event lasts an hour, changing the start keeps the length, an end before the start becomes start + 1 h), place, notes, tags (M3-06).*
- **CAL-013** `MVP` `todo` — Several days: an all-day event runs from a start date to an end date (holiday, trip); a timed
  event may end on a later day (overnight train). It shows on every day it covers.
  *Client core: multi-day occurrences on every day with `continuesBefore/After`, week blocks clipped to the day (M3-04).*
  *UI: the end date in the editor; all-day events over several days, timed ones ending on a later day; more than 366 days is refused with «Слишком длинное событие» (M3-06).*
- **CAL-002** `MVP` `todo` — Participants ("who it concerns"): any persons — members and relatives. An event without
  participants concerns the whole family. ASSUMPTION: deleting a relative removes them from events; the events stay.
  *Client core: participants (unknown ids dropped), person filter incl. whole-family events (M3-04).*
  *UI: «Участники» in the editor — the sheet «Кого касается» with «Вся семья» and every person, members first (M3-06).*
- **CAL-014** `MVP` `todo` — Event page: title, date and time with the repeat rule, place, participants, tags, notes, the
  reminder for me, who created it; edit and delete for those who may.
  *UI: the event page — title, «Изменено только это повторение», when, repeat with its end, place, participants, tags, notes, «Напомнить мне», «Создал(а)»; edit and delete for who may (M3-05; the editor comes in M3-06).*
- **CAL-017** `MVP` `todo` — Works offline: events are seen, created, changed and deleted without network and sync later
  (FND-040).
  *Client core: everything through `LocalWriter`, works offline; multi-device tests for offline edits of one occurrence, split, delete vs edit, stale permissions (M3-04).*
  *UI: the tab and the event page read local data and write through the local writer, so they work offline (M3-05).*

### Repeating events

- **CAL-003** `MVP` `todo` — Repeat every N days, every N weeks on chosen weekdays, every N months on the day of the start
  date, every N years; optionally until an end date. Day 31 in shorter months falls on the last day; 29 February falls
  on 28 February in other years (as in tasks, TSK-010).
  *Server: repeat rule and `repeatUntil` stored and validated; changed occurrences as derived-id rows (M3-02).*
  *Client core: occurrences from `EventSchedule` in the views (M3-04).*
  *UI: the repeat sheet of tasks without "after completion", plus «До» («Без конца» or a date, not before the start) (M3-06).*
- **CAL-015** `MVP` `todo` — Changing or deleting a repeating event asks: «Только это», «Это и следующие», «Все».
  «Только это» changes one occurrence (another time, day, place, title, participants) or cancels it; «Это и следующие»
  splits the series at that date; «Все» changes the whole series.
  ASSUMPTION: when the repeat rule or the start of the whole series changes, earlier changes of single occurrences are
  reset (the app warns before saving).
  *Server: occurrence rows, `splitFrom`, cascade on delete (M3-02).*
  *Client core: `editOccurrence`, `cancelOccurrence`, `editFollowing`, `deleteFollowing`, resets when the schedule changes; only changed fields are sent, so offline edits of one occurrence merge (M3-04).*
  *UI: «Только это · Это и следующие · Все» before editing or deleting a repeating event; «Только это» closes the page with «Повторение удалено» (M3-05).*
  *UI: the editor for «Только это» (times, title, place, participants, notes of one occurrence; earlier changes of it stay), «Это и следующие» (from the occurrence, a split from a later date opens the new series) and «Все» (asks before resetting changed occurrences) (M3-06).*

### Birthdays and memorable dates

- **CAL-004** `MVP` `todo` — Birthdays come from the persons' profiles (members and relatives with a day and month of birth).
  The age shows when the year is known («Бабушка Валя, 78 лет»); 29 February birthdays show on 28 February in non-leap
  years. They are not edited in the calendar: a tap opens the person.
  *Client core: birthdays with ages in the day lists, month dots, «Скоро», person dates (M3-04).*
  *UI: birthday rows «Бабушка Валя, 78 лет» / «День рождения: …» with the relation; a tap opens the person (M3-05).*
- **CAL-005** `MVP` `todo` — Memorable dates: a yearly date for one or more persons (wedding anniversary, memorial day),
  with the year it began, so the count shows («Годовщина свадьбы · 16 лет»). Created in the calendar («Памятная дата» in
  the editor) or from a person's page; the person's page lists their birthday and memorable dates.
  *Client core: memorable dates with years, `forPerson` (M3-04).*
  *UI: memorable rows «Годовщина свадьбы · 16 лет»; the event page shows «12 июня · каждый год» and the years; edit and delete apply to the whole date, without the scope dialog (M3-05).*
  *UI: «Памятная дата» in the editor — day, month and year («Год неизвестен»), «Считать годы» with «16 лет в этом году», participants, tags; reminded like birthdays (M3-06).*

### Reminders and notifications

- **CAL-006** `MVP` `todo` — An event can have a reminder (at the start, or minutes, hours, days before; all-day events: on the
  day or days before, at 09:00). It fires on the phones of the participants who are members; for an event without
  participants — on the phones of everybody with access to Calendar (Q-021). Everybody who sees the event can set their
  own reminder for it («Напомнить мне»: another time or none), also when they are not a participant. Reminders work
  offline (FND-050).
  *Server: personal reminder rows, synced only to their member (M3-02).*
  *Client core: `myReminder` in the event detail, `setMyReminder` / `clearMyReminder`, local event reminders (14 days ahead, at most 100) (M3-04).*
  *UI: «Напомнить мне» on the event page — «Как в событии» (when the event reminds me), «Нет» and the editor's options; asks for notifications like the task editor (M3-05).*
  *UI: «Напоминание» in the editor with the timed or all-day choices, «· участникам» / «· всем»; asks for notifications when they are off (M3-06).*
- **CAL-016** `MVP` `todo` — Birthday and memorable-date reminders (Q-022): for everybody with access to Calendar, at 09:00
  every day starting N days before and including the day itself. N = 3 by default; each member chooses in Settings ›
  Notifications: off, on the day only, 1, 3, 7 or 14 days before. A person does not get reminders about their own
  birthday. ASSUMPTION: memorable dates follow the same setting as birthdays.
  *Server: `birthdayDaysBefore` in `/me/notification-settings` (M3-02).*
  *Client core: local reminders at 09:00 from N days before, none for one's own birthday, `birthdayDaysBefore` mirrored and saved (M3-04).*
- **CAL-007** `MVP` `todo` — Participants who are members get a notification when they are added to an event (a new event
  or added later), when its date, time or place changes, and when it is cancelled. Not the person who made the change,
  not for past events. ASSUMPTION: changes of the title, notes or tags only do not notify; a change of a whole series
  sends one notification.
  *Server: added / changed / cancelled notices as `event.changed` (M3-03); end to end on a real phone: pending, FND-051.*
- **CAL-018** `MVP` `todo` — Settings › Notifications has switches for event notifications (CAL-007) and event reminders
  (CAL-006), and the birthday reminder choice (CAL-016) (FND-052).

### Views

- **CAL-008** `MVP` `todo` — Three views: «Месяц» (grid with dots in the participants' personal colors and the list of the
  chosen day under it), «Неделя» (a grid by hours: seven columns, all-day events in a strip on top; Q-024), «Список»
  (days with events from today onwards, scrolling forward and back). Previous/next and «Сегодня»; the last view is
  remembered. Filters by person (events they take part in and their birthdays and dates) and by tag.
  *Client core: `days`, `month` (42 cells, dots), `week` (column layout), person and tag filters (M3-04).*
  *UI: the Calendar tab — month pager with dots and the day's list, «Список» growing forward and back, ‹ › and «Сегодня», filter sheet with removable chips, the view remembered on the device; «Неделя» is a placeholder until M3-07 (M3-05).*
- **CAL-009** `MVP` `todo` — Tasks with a date appear in «Список» and in the day list under the month (Q-023): for
  repeating tasks only the current occurrence; they can be ticked off there. The filter has «Показывать задачи», on by
  default; the person filter applies to assignees, the tag filter to task tags. Only for members with access to Tasks.
  ASSUMPTION: tasks are not drawn as dots in the month grid nor in the week grid.
  *Client core: open tasks on their date, overdue and done-today ones on today, «Показывать задачи» filter (M3-04).*
  *UI: task rows «Задача · Дмитрий» («Просрочено») ticked off in the day list and «Список», «Показывать задачи» in the filter (M3-05).*
- **CAL-019** `MVP` `todo` — Today block «Скоро» (FND-063): events of today and the next 7 days, birthdays and memorable dates,
  at most 5 rows with a date tile and «через 3 дня»; «Календарь» opens the tab.
  *Client core: `upcoming` (M3-04).*

### Tags

- TAG-002 (events), TAG-003 (tag filter in Calendar) and TAG-004 (the «События» section of the tag page: upcoming
  events with the tag) are delivered in M3.

### Later

- **CAL-010** `later` `todo` — iCal subscription feed to see the family calendar in Google/Apple calendars.
- **CAL-011** `later` `todo` — CalDAV two-way sync.
- **CAL-012** `later` `todo` — Public holidays by country.
- **CAL-020** `later` `todo` — Private events seen only by their participants.
- **CAL-021** `later` `todo` — Monthly repeat by weekday ("every second Tuesday").
- **CAL-022** `later` `todo` — Events in another time zone (travel).

## Permissions and privacy

Everybody with access to Calendar (`calendar.view`) sees all events, birthdays and memorable dates of the family.
Members without it receive no event data. Persons' birth dates are visible to all members already (foundation).
Permissions ([catalogue](foundation.md#permission-catalogue)): `calendar.view`, `calendar.create`, `calendar.edit_any`.

| Action | Who |
| --- | --- |
| See events, birthdays and memorable dates | `calendar.view` |
| Create an event or a memorable date | `calendar.create` |
| Edit, move, cancel or delete an event (one occurrence or the series) | the creator, or `calendar.edit_any` |
| Set "my reminder" on an event | everybody with `calendar.view`, for themselves only |
| Change birthdays | `persons.manage` (in the person's profile, foundation) |

Templates: Adults have all three; Kids have `calendar.view` and `calendar.create` (they create events and change their
own, not others'). Administrators have everything. Notification texts contain the event title; they travel encrypted
through the push service (ADR-0006).

## Data

Event (with participants, tags, repeat rule and changed or cancelled occurrences), the member's own reminder for an event.
Birthdays are computed from persons and not stored. Times are wall-clock times in the family's time zone, like tasks
(ASSUMPTION; another zone per event is CAL-022). Design: [architecture/calendar.md](../../architecture/calendar.md).

## Customer decisions (2026-10-24)

- Q-021 — event reminders go to the member participants (everybody with Calendar if there are none); everybody can set
  their own reminder for any event they see.
- Q-022 — birthday reminders are configurable; by default every day at 09:00 starting 3 days before.
- Q-023 — all tasks with a date show in the calendar list (current occurrence only), switchable, on by default.
- Q-024 — the week view is a grid by hours.

## Assumptions

Agent defaults, easy to change after feedback:

- An event without participants concerns the whole family; it is drawn in the brand color without avatars.
- The month grid shows up to three dots per day (one per person, in personal colors); more shows as «+».
- «Список» starts at today; past days are reached by scrolling up.
- An all-day event's reminder is at 09:00 (same as tasks, Q-016).
- Memorable dates send no notifications when created or changed; they are reminded like birthdays.
- New events created from a day in the month or the week start on that day (timed: the next full hour).
- The person's page lists their birthday and memorable dates; «Добавить памятную дату» opens the editor with them as the
  participant.
- A memorable date entered with «Год неизвестен» counts no years; opened for editing in a later year, it shows the year
  it was entered (M3-06).

## Out of scope (M3)

- Inviting people outside the family, accept/decline (RSVP).
- Attachments to events (M5b, [files](files.md)), event colors other than personal colors.
- Travel time, "leave now" reminders, maps for the place.
- Import from other calendars (CAL-010/011 later).
