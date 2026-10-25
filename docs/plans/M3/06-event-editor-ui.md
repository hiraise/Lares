---
id: M3-06
status: done
depends_on: [M3-05]
requirements: [CAL-001, CAL-002, CAL-003, CAL-005, CAL-006, CAL-013, CAL-015, TAG-002]
---
# M3-06: Event editor — times, several days, repeat with end, participants, memorable dates

## Goal

Create and edit events and memorable dates: title, all-day or times (also over several days), repeat with an end date,
participants («Вся семья» when none), place, reminder, tags, notes. Editing a repeating event follows the scope chosen
on the event page: only this occurrence, this and following, or all — with a warning when changing the schedule of
all would reset changed occurrences.

## Read first

- [Calendar spec](../../product/features/calendar.md) CAL-001, CAL-002, CAL-003, CAL-005, CAL-006, CAL-013, CAL-015 and
  § Assumptions
- [Calendar design](../../architecture/calendar.md) § Contract data, § Writer, § UI (Editor)
- [Tasks design § Editor (M1-06)](../../architecture/tasks.md#editor-m1-06) — the pattern (pure form, changed fields only)
- Code: `ui/tasks/TaskEditorScreen.kt` (`DateDialog`, `TimeDialog`, `EditorPicker`, `TaskEditorActions`, discard
  confirm, notification permission ask, `TagSheetDialog`), `TaskEditorViewModel.kt`, `TaskForm.kt`, `RepeatSheet.kt`
  (`RepeatOptions`, `CustomRule`, `IntervalStepper`, `WeekdayToggles`), `ReminderSheet.kt`, `AssigneesSheet.kt`,
  `TagsSheet.kt`; `ui/family/EditorScreens.kt` (`BirthdayDialog`, `PickerColumn`); tests `TaskEditorViewModelTest.kt`,
  `TaskEditorUiTest.kt`, `TaskFormTest.kt`
- M3-05: `ui/calendar/*` (`EventEditorDestination`, `RepeatScope`, `EventReminderOptions`)

## Scope

- `ui/calendar/EventForm.kt` (pure), `EventEditorViewModel.kt`, `EventEditorScreen.kt` (`EventEditorRoute` replaces
  the M3-05 stub), `ParticipantsSheet.kt`.
- Move `DateDialog` and `TimeDialog` from `TaskEditorScreen.kt` to `ui/components/DateTimeDialogs.kt` (internal → shared,
  same behavior); `TaskEditorScreen` uses them from there.
- Generalize the repeat sheet: `RepeatOptions`/`RepeatSheet` take `(rule: Recurrence?, start: LocalDate, allowAfterCompletion:
  Boolean, onPreset, onRule)` instead of `TaskForm`; the task editor passes its values, events pass
  `allowAfterCompletion = false` (the mode switch is hidden). `RepeatPreset` and preset logic move to `ui/common/Repeat.kt`
  used by both forms.
- Strings en/ru.

Out of scope: week grid (M3-07); Today, tag page, person page (M3-08).

## Decisions (do not revisit)

- **Args:** `EventEditorArgs(id, occurrence: LocalDate?, scope: RepeatScope, date: LocalDate?, hour: Int?, memorable,
  personId, tagId)` from the destination. New event: start = `date` (default today) at `hour` (default the next full
  hour from now, max 23), end = start + 1 h (crossing midnight moves `endDate`); `personId` preselects a participant,
  `tagId` a tag; `memorable` preselects the type.
- **Layout** (`AppPage` + `DetailTopBar(«Новое событие» / «Изменить событие» / «Памятная дата», onBack)`; trailing
  `LinkButton` «Сохранить», disabled while the title is blank):
  1. On create only: `SegmentedControl` «Событие · Памятная дата».
  2. `FormField` «Название» (hint «Например, Стоматолог» / «Например, Годовщина свадьбы»), focused on create.
  3. Event: `ListGroup` — `CheckRow`-style switch row «Весь день»; «Начало» (`calendar_month`: «Вт, 28 окт.» + time
     chip «16:00» when timed; tapping the date opens `DateDialog`, the time `TimeDialog`); «Конец» (same); «Повтор»
     (`repeat`, supporting = rule text, opens the shared repeat sheet); «До» (only when repeating: «Без конца» or
     the date; `DateDialog` with «Без конца» as clear); «Участники» (`group`: names or «Вся семья»; `ParticipantsSheet`);
     «Место» (`location_on`, inline `FormField` in the row area, single line); «Напоминание» (`alarm`; the
     `EventReminderOptions` of M3-05 in a sheet; supporting text «Участникам» or «Всем» for whole-family events);
     «Метки» (`sell`, `TagsSheet`, «+ Новая метка» via `TagSheetDialog`); `FormField` «Заметки» (multi-line).
  4. Memorable date: date row («12 июня 2010»; `BirthdayDialog`-like day/month/year pickers with «Год неизвестен»
     — move `BirthdayDialog` to `ui/components/` as `DayMonthYearDialog`); switch «Считать годы» (only with a year;
     supporting «16 лет в этом году»); participants; tags; notes. No times, repeat or reminder rows; a hint
     «Напомним, как о днях рождения» under the group.
- **Form rules** (`EventForm`, unit-tested): changing the start date/time keeps the duration; changing the end before the
  start sets end = start + 1 h (timed) or end date = start date (all-day); switching all-day on keeps the dates and drops
  the times; switching off sets 09:00–10:00 on the start date (or the remembered times of this edit session); a span
  over 366 days is refused with the field error «Слишком длинное событие»; a repeat chosen without a date uses the start
  date; `repeatUntil` before the start → error «Раньше начала»; reminder options change with all-day: the value is kept if
  it exists in the new list, otherwise reset to none.
- **Scopes:**
  - `ALL` (and one-offs): `EventForm.applyTo(loaded)` changes only fields the user changed, then `CalendarWriter.update`.
    If the change touches `startDate`, `allDay` or `recurrence` and `detail.changedCount > 0`, a `ConfirmDialog` first:
    «Изменённые отдельные повторения (N) будут сброшены» / «Сохранить».
  - `THIS`: the form starts from the effective occurrence; repeat, «До», reminder, tags and the type switch are hidden;
    a note «Изменения только для вторника, 28 октября» under the title; saving → `editOccurrence(id, occurrence,
    changes)`.
  - `FOLLOWING`: the form starts from the occurrence's values with the series rule; saving → `editFollowing(id,
    occurrence, newData)`; the note «Изменения с вторника, 28 октября».
- **ParticipantsSheet** (`EditorSheet` «Кого касается»): `CheckRow` «Вся семья» (exclusive; selected when the set is
  empty) + every person (`Avatar` 32 dp, name, relation or «член семьи» as supporting), members first then relatives by
  name; «Готово».
- After create → `EventDestination(newId)` replacing the editor (pattern `onCreated` of tasks); after edit → back.
  Unsaved changes ask before leaving (task editor pattern). Choosing a reminder while notifications are off asks for
  permission (task editor pattern).
- Kids: they can create; editing others' events is never offered (M3-05), the editor still checks `canEdit` and closes
  with «Нет прав» if it fails.

## Steps

1. Move the dialogs and generalize the repeat sheet; the task editor tests stay green unchanged.
2. `EventForm` with tests (`EventFormTest`: every rule above, applyTo changed-only, memorable shape output, THIS changes
   with null for unchanged fields).
3. View model and screen; participants sheet; strings.
4. Tests: `EventEditorViewModelTest` (create timed / all-day multi-day / repeating with until / memorable with years;
   edit ALL with reset warning; THIS → occurrence row; FOLLOWING on first and later dates; prefill from `date`, `hour`,
   `personId`, `tagId`; discard confirm); `EventEditorUiTest` (compose: switches and rows, hidden rows per scope and type,
   errors shown).

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest --tests '*Event*' --tests '*TaskEditor*'` while iterating; full gate at the end.
- Emulator walkthrough (`emulator-tester`): spec scenarios 1 (create «Стоматолог» for Тимур with place and reminder),
  2 («Это и следующие» time change of «Танцы»; «Только это» move of one Monday), 4 (memorable date with years), 5
  (holiday over 11 days); a Kid account cannot edit Mom's event; ru/en, light/dark.

## Docs to update

- Spec progress notes (*UI: …*); calendar design § UI if a detail changed; tasks design § Editor (dialogs and repeat sheet
  moved).
- STATUS.md; plan status.

## Stop and report if

- Generalizing `RepeatSheet` changes the task editor's behavior in any test (keep tasks unchanged).

## Handoff

Russian summary: events and memorable dates can be created and changed — times, several days, repeats with an end date,
who it concerns, place, reminder, tags; a repeating event can be changed for one day, from a day on, or entirely. Next M3-07.
