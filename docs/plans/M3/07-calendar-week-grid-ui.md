---
id: M3-07
status: ready
depends_on: [M3-06]
requirements: [CAL-008, CAL-013]
---
# M3-07: Calendar week view — the grid by hours

## Goal

«Неделя» (Q-024) replaces its placeholder: seven day columns with a grid by hours, all-day events and birthdays in a
strip on top, timed events as blocks in the first participant's color, overlapping events side by side. A tap opens an
event; a tap on an empty hour creates one at that hour.

## Read first

- [Calendar spec](../../product/features/calendar.md) CAL-008, CAL-013, § Assumptions
- [Calendar design](../../architecture/calendar.md) § UI (Week), § Models and views (`week`)
- [Visual style](../../design/visual-style.md) § Colors, § Palette (`tones()`), § Typography, § Shapes
- Code: M3-05 `ui/calendar/CalendarScreen.kt`, `CalendarViewModel.kt`, `CalendarComponents.kt`; M3-04
  `data/calendar/CalendarViews.kt` (`WeekDay`, `TimedBlock`); `ui/theme/*`

## Scope

- `ui/calendar/WeekGrid.kt` (`WeekPager`, `WeekHeader`, `AllDayStrip`, `HoursGrid`, `EventBlock`); the view model's week
  state (`observeWeek(monday, filter)`); the header title for weeks.
- Strings en/ru.

Out of scope: drag to move or resize events, pinch zoom (not in the spec).

## Decisions (do not revisit)

- `HorizontalPager` of weeks (Monday first; initial page = the week of today at index 6000); ‹ › page by one week;
  `today` returns to the current week. Header title: «19–25 октября», across months «28 сент. – 4 окт.», across years
  with years.
- **Week header** under the segmented control: 40 dp left gutter, 7 equal columns: weekday short name (`labelSmall`) over
  the day number (`titleSmall` 600; today in a 28 dp `primary` circle). A tap on a day switches to «Месяц» with that day
  selected.
- **All-day strip:** rows of chips 20 dp high, 2 dp apart, spanning the days they cover inside the week (multi-day
  events as one chip across columns; birthdays and memorable dates as chips in the person's `tones().container` with
  `cake`/`celebration` 12 dp); at most 3 rows, then «+N» in the column (tap → month with that day). Chip text
  `labelSmall`, one line, ellipsis.
- **Hours grid:** vertical scroll; 48 dp per hour, 24 hours; hour labels «07:00» `labelSmall` in the gutter; thin
  `divider` lines per hour; a 2 dp `primary` line for "now" on today's column (moves with the minute ticks). On open and
  on page change scroll to 07:00, or to the earliest block of that week if it is earlier (minus 30 minutes).
- **Blocks:** x from `column/columns` of the column width, 1 dp gap; y and height from start/end minutes (minimum 24 dp);
  shape radius 6; fill `tones().container` of the first participant (whole family: `primaryContainer`), a 3 dp left bar
  in `tones().base`; text title `labelSmall` 600 (+ start time on a second line when the block is ≥ 40 dp high),
  `onSurface`-like content color from `tones().onContainer`. A tap → `EventDestination(id, occurrence)`.
- A tap on an empty spot (`calendar.create`) → editor with that day and hour (`EventEditorDestination(date, hour)`).
- Accessibility: every block has a content description «Танцы, понедельник 17:30–18:45, Аня».
- Performance: the grid for a week is built once per `WeekDay` list change; use `Layout`/`Canvas` for lines and labels,
  composables only for blocks and chips.

## Steps

1. Pager, header, strip, grid, blocks; wiring in `CalendarScreen` and the view model.
2. Tests: `WeekGridLogicTest` (pure: block geometry from `TimedBlock` — x, width, y, min height; strip row assignment for
   chips spanning days; header titles across months/years); `CalendarViewModelTest` additions (week paging, today);
   `CalendarUiTest` additions (compose: blocks for the seed week appear with their titles; a tap opens the event; an
   all-day chip spans; the «Неделя» choice persists).

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest --tests '*Calendar*' --tests '*WeekGrid*'` while iterating; full gate at
  the end.
- Emulator walkthrough (`emulator-tester`): spec scenario 7 (week with «Танцы», «Стоматолог», the parents' meeting, the
  holiday strip; create an event by tapping a free evening hour); overlapping events side by side; ru/en, light/dark;
  scrolling stays smooth on the slow emulator (note any jank with the main-thread check from STATUS § Risks).

## Docs to update

- Spec progress note under CAL-008 (*UI: week grid …*); calendar design § UI if a detail changed.
- STATUS.md; plan status.

## Stop and report if

- The grid cannot scroll smoothly on the emulator with the seed data after moving lines and labels to `Canvas`.

## Handoff

Russian summary: the week view shows the whole family's week by hours — who is busy when, holidays and birthdays on top;
tapping a free hour creates an event there. Next M3-08.
