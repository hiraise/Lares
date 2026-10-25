# Calendar

The family's shared calendar: events, relatives' birthdays and memorable dates. Everything works offline; changes
reach the other phones as soon as there is a connection. Times are always in the family's time zone.

## Where to find events

- **Today** (the first tab) shows **Coming up**: events of today and the next 7 days, birthdays and memorable dates,
  at most five rows. "Grandma Valya, 78 years — Monday · in 3 days". **Calendar** opens the tab, **N more** opens it in
  the "List" view.
- **Calendar** (the tab) has three views, switched at the top; the last one is remembered:
  - **Month** — the month grid. Dots in the participants' colors show who has something on which day. Tap a day to see
    its list under the grid.
  - **Week** — seven days by hours. Events are colored blocks (overlapping ones stand side by side), all-day events and
    birthdays are a strip on top. Tap a free hour to create an event at that time.
  - **List** — days with events from today on. Scroll down; **Show earlier** at the top loads past days.
- The ‹ › arrows page through months or weeks, **Today** goes back to the current day.
- **Filter** (the button at the top): **Whose calendar** — one person's events (and the whole family's), their
  birthday and their tasks; **Tag** — events and tasks with one tag; **Show tasks**. Active filters show above the
  calendar; the cross removes a filter.
- A **tag page** (More › Tags) lists the next events with that tag under **Events**.
- A **person page** (More › Family) says under the birthday when it is and how old they turn ("Turns 78 · in 3 days");
  **Dates** lists their memorable dates.

## Creating an event

Tap **+** on the Calendar tab (or a free hour in "Week"). Only the title is required:

- **All day** — an event without a time, such as a holiday or a vacation.
- **Starts** and **Ends** — date and time. An event can last several days: a vacation from July 10 to 20 is one event
  and shows on every day. Changing the start keeps the duration.
- **Repeat** and **Until** — see below.
- **Participants** ("Who it concerns") — family members and relatives. **Whole family** — an event for everybody (no
  participants chosen). The first participant's color is the event's color in the calendar.
- **Place**, **Reminder**, **Tags**, **Notes**.

If Lares notifications are off on the phone, the app offers to allow them when you choose a reminder.

## Repeating events

Repeat: every day, on chosen weekdays, every month on the same date, every year. **Until** is the last day of the
repeats; without it the event repeats forever.

When you change or delete a repeating event, Lares asks what it applies to:

- **Only this** — one occurrence. For example, there is no dance class this Wednesday: delete only this occurrence. A
  changed occurrence says "Only this occurrence was changed".
- **This and following** — from this day on. For example, from November the class starts at 18:00. Lares splits the
  event in two: the old one ends the day before, the new one starts on the chosen day.
- **All** — the whole event.

Keep in mind:

- Changing the schedule of **all** occurrences (the start date, "all day" or the repeat) resets single changed
  occurrences. Lares warns before saving.
- Your own reminder ("Remind me") is not carried over to the new part of the event after "This and following". Set it
  again if you need it.

## Reminders

- The event's **Reminder**: at the start or minutes, hours or days before. For an all-day event: on the day or days
  before, at 9:00. It goes to the participants who use the app; for an event without participants ("Whole family") —
  to everybody who sees the calendar.
- **Remind me** on the event page is your own reminder: another time or "None". You can set it even when you are not a
  participant. "As in the event" goes back to the event's reminder.
- Reminders are set on the phone itself and fire on time even without internet. Tap a reminder to open the event.

## Birthdays

- Birthdays come from the persons' profiles: More › Family › the person › **Birthday**. They are not changed in the
  calendar: tapping a birthday opens the person.
- When the birth year is known, the age shows: "Grandma Valya, 78 years". Without a year: "Birthday: …".
- A birthday on February 29 shows on February 28 in non-leap years.
- Birthday reminders go to everybody who sees the calendar, at 9:00 every day from a few days before the birthday
  until the day itself. Nobody is reminded of their own birthday.
- How many days ahead is everybody's own choice: **More › Settings › Notifications › Birthdays**: "Don't remind",
  "On the day only", "1 day before", "3 days before" (the default), "A week before", "2 weeks before".
- Tap a birthday reminder to open the person.

## Memorable dates

A wedding anniversary, a memorial day — a yearly date for one or more people. Create it in the event editor
(**Memorable date** at the top) or on the person's page (**Add a memorable date**). With the year and **Count the
years** on, the count shows: "Wedding anniversary · 16 years". The year may be unknown ("Year unknown").

Memorable dates are reminded by the same setting as birthdays. A memorable date is changed and deleted as a whole.

## Tasks in the calendar

Tasks with a due date show in "List" and in the day's list under the month; you can tick them off right there. A
routine shows only its current occurrence; overdue tasks show on today. To hide them: **Filter** › **Show tasks**. The
month grid and "Week" do not show tasks. Only members with access to Tasks see them.

## Notifications

- The family server sends **Calendar events** to the event's participants who use the app:
  - you were added to an event (a new one or an existing one);
  - the event's date, time or place changed;
  - the event or one of its occurrences was cancelled.

  There are none for your own changes, for past events, for memorable dates, and when only the title, notes or tags
  change.
- Instant notifications need the **ntfy** app on the phone, see [Instant notifications](instant-notifications.md).
  Without ntfy the app checks for news about every 15 minutes.
- Tap a notification to open the event. If the event was deleted, Lares opens the calendar and says so.
- Each kind can be turned off: **More › Settings › Notifications**: "Calendar events", "Event reminders", "Birthdays".

## Who may do what

The family administrator decides this with groups (More › Family). By default:

| | Adults | Kids |
| --- | --- | --- |
| See all events, birthdays and memorable dates | yes | yes |
| Create events and memorable dates | yes | yes |
| Change and delete their own events | yes | yes |
| Change and delete others' events | yes | no |
| Set "Remind me" for themselves | yes | yes |

Birthdays are changed by whoever may change persons' profiles (usually adults); everybody changes their own profile.
Members without access to Calendar do not see the Calendar tab and receive no event data.

## Not yet

Private events seen only by their participants, events in another time zone, public holidays, sync with Google or
Apple calendars. These are planned for later versions.
