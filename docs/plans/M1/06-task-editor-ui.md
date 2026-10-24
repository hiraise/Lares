---
id: M1-06
status: done
depends_on: [M1-05]
requirements: [TSK-001, TSK-002, TSK-005, TSK-010, TSK-011, TSK-016, TSK-030, TSK-032, TAG-002]
---
# M1-06: Task editor — create and edit, repeat rules, assignees, tags, reminder

## Goal

One full-screen editor for creating and editing tasks, replacing the `TaskEditor` stub from M1-05: title, notes, date and
time, repeat rule (fixed and after-completion), assignees or "anyone", tags, reminder, "tell me when it is done", and a
checklist on creation. Opened from the Tasks FAB, the task page "Edit" action and (M1-07) the Today FAB.

## Read first

- [Tasks spec](../../product/features/tasks.md) § Requirements (Basics, Routines, Reminders), § Assumptions
- [Tasks design](../../architecture/tasks.md) § Contract data, § Validation, § Schedule logic (anchor semantics)
- [Visual style](../../design/visual-style.md) § Component patterns (buttons, chips, segmented control), § People
- [Conventions § Client UI](../../process/conventions.md#client-ui) (errors, `FieldError`)
- Code: `ui/family/EditorScreens.kt` + `EditorViewModels.kt` (form pattern, `PersonForm`, args wrapper), `ui/family/TagSheet.kt`
  (bottom sheet style), `ui/components/Fields.kt`, `Pickers.kt`; M1-05 `ui/tasks/*`; M1-04 `TasksWriter`

## Scope

- `ui/tasks/TaskEditorViewModel.kt`, `TaskEditorScreen.kt`, `TaskForm` (pure, in `TasksLogic.kt` or its own file),
  `RepeatSheet.kt`, `AssigneesSheet.kt`, `TagsSheet.kt`, `ReminderSheet.kt`.
- Route `TaskEditor(id: String?)` implemented (args wrapper `TaskEditorArgs(id)`).
- Strings en/ru.

Out of scope: per-occurrence date moves, end dates of routines, rotation (spec § Out of scope).

## Decisions (do not revisit)

- **Layout** (`AppPage` + `DetailTopBar(title = «Новая задача» / «Задача»)`, `PrimaryButton` «Сохранить» at the bottom, enabled
  when the title is not blank):
  1. Title `FormField` (autofocus on create, `TASK_TITLE_MAX`), notes `FormField` (multi-line, optional).
  2. `ListGroup` of rows opening pickers: «Дата» (Material 3 `DatePickerDialog`; "Clear" action), «Время» (`TimePicker` dialog,
     24 h by locale; visible only with a date; "Clear"), «Повтор» → `RepeatSheet`, «Исполнители» → `AssigneesSheet`,
     «Метки» → `TagsSheet`, «Напоминание» → `ReminderSheet` (visible only with a date).
  3. Switch row «Сообщить мне, когда выполнят» / "Tell me when it's done" (`notifyCreatorOnDone`); shown only when the viewer is
     (or will be) the creator.
  4. On create only: «Чек-лист» — list of text fields with «+ Добавить пункт»; items are created with the task (`TasksWriter.create(draft, checklist)`).
     On edit the checklist is managed on the task page (M1-05).
- **Defaults on create:** no date; assignees = **me** when opened from "Mine" scope or Today, else none ("anyone");
  `notifyCreatorOnDone = false`; tag pre-selected when opened from a tag-filtered list.
- **RepeatSheet:** presets as single-choice rows: «Не повторять», «Каждый день», «Каждую неделю (по <weekday of date>)»,
  «Каждый месяц (<day>-го числа)», «Каждый год», «Настроить…». "Настроить" shows: segmented control «По расписанию» / «После
  выполнения»; interval stepper 1–999 with unit dropdown (days/weeks/months/years; plural labels); for fixed + weeks — 7 weekday
  toggles (Monday first; the date's weekday pre-selected). Choosing a repeat without a date sets the date to today. A summary
  line under the controls uses the M1-05 rule summary text. For an existing routine the option «Не повторять» is disabled with the
  hint «Чтобы остановить повтор, удалите серию» (ADR-0017).
- **Date semantics for routines:** the row is labelled «Начало» / "Starts" and the editor writes `dueDate` **only** if the user
  changed it (never rewrite the anchor implicitly — monthly rules would drift). Below the rows, for routines, a supporting text
  «Следующий раз: <date>» computed with `TaskSchedule` from the form and the task's completions.
- **AssigneesSheet:** first row «Кто угодно» (exclusive), then members with `tasks.view` (avatar + name, checkboxes); "Done".
  Members without `tasks.view` are not offered.
- **TagsSheet:** active tags as selectable `TagChip`s in a flow row; archived tags that are already on the task stay selected and
  shown; if the viewer has `tags.manage`, a «+ Новая метка» chip opens the existing tag sheet from `ui/family/TagSheet.kt`
  (reuse, do not copy) and selects the new tag.
- **ReminderSheet:** with time — «Нет», «В срок», «За 5 минут», «За 15 минут», «За 30 минут», «За 1 час», «За 1 день»; without time —
  «Нет», «В день срока в 9:00», «За 1 день в 9:00», «За 2 дня в 9:00». Stored as minutes before (`0, 5, 15, 30, 60, 1440, 2880`).
  Changing time ↔ no time resets an incompatible choice to «В срок» / «В день срока».
- **Save:** create → `TasksWriter.create`, then navigate to the task page (replace the editor in the back stack); edit →
  `TasksWriter.update(id, data)` and go back. Local validation errors map to fields with `FieldError` (title, dueDate); other
  errors → `ErrorText`. Unsaved changes + back → `ConfirmDialog` «Отменить изменения?».
- **Permissions:** editing requires `canEdit` (the route shows a read-only message otherwise — should not normally be reachable).

## Steps

1. `TaskForm` (state ↔ `TaskData`, change detection, validation mirroring `Validators.task`) + tests.
2. Sheets and editor screen; ViewModel (`create`/`edit` modes, loads task + completions for the "next time" line).
3. Wire FAB (M1-05) and task page "Edit" to the editor.
4. Tests:
   - jvmTest `TaskFormTest`: presets → `Recurrence` values; custom rules; weekday default from date; repeat sets date to today;
     time without date impossible; reminder reset on time removal; unchanged anchor not written on edit; routine cannot be set to
     «Не повторять».
   - jvmTest `TaskEditorViewModelTest`: create with checklist → outbox has task + items; edit sends only changed fields;
     default assignee = me from Mine scope; "anyone" exclusivity; Kid without `tasks.create` cannot open create (FAB hidden — VM
     reports `canCreate = false`).
   - Compose UI test: editor shows «Начало» for routines and the "next time" line; reminder options differ with/without time.
5. Emulator check via `emulator-tester`: create each scenario of the spec (music school task for Дмитрий with tag; trash Mon/Thu
   20:00 for Аня and Тимур with reminder 15 min; filter every 3 months after completion; weekly cleaning with a checklist); edit
   a task; ru/en, light/dark. Screenshots: editor (ru light), custom repeat sheet (ru dark), assignees sheet.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest` while iterating; full gate at the end.
- Emulator walkthrough done (or reported as skipped with the reason), screenshot paths in the handoff.

## Docs to update

- Tasks spec: TSK-001, TSK-002, TSK-010, TSK-011, TSK-016, TSK-032 → `done` if fully covered; TSK-030 stays `doing` until M1-07
  (taps, settings); TAG-002 note "Tasks done".
- STATUS.md; plan status.

## Stop and report if

- Material 3 date/time pickers are not available in the Compose Multiplatform version from `libs.versions.toml`.

## Handoff

Russian summary with screenshots: how to create a task, a routine by weekdays or "after completion", assign it to someone or
to anyone, add tags, a reminder and a checklist. Next M1-07.
