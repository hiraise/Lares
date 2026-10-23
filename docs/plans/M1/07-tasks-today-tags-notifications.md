---
id: M1-07
status: ready
depends_on: [M1-06, M1-03]
requirements: [TSK-023, TSK-030, TSK-031, TSK-032, TSK-033, TSK-034, TAG-003, TAG-004, FND-051, FND-052, FND-063]
---
# M1-07: Tasks everywhere — Today block, tag page, notification settings and taps, M1 docs

## Goal

Finish M1: the "Today" home screen shows my overdue and today's tasks; a tag page lists the tag's tasks; Settings ›
Notifications gets the task switches; tapping a task notification or reminder opens the task; user manual and changelog.
After this plan M1 is ready for acceptance with the customer.

## Read first

- [Tasks spec](../../product/features/tasks.md) § Views, § Reminders and notifications; [tags spec](../../product/features/tags.md)
  TAG-003, TAG-004; [foundation spec](../../product/features/foundation.md) FND-052, FND-063
- Mockup `docs/design/mockups/Main.dc.html` — "Задачи на сегодня" card (count badge, «Все» link, rows, done row) and the FAB
- [Tasks design](../../architecture/tasks.md) § Client › Reminders, § Notification taps
- Code: `ui/main/Sections.kt` (`TodayScreen`), `ui/main/MainViewModel.kt`, `ui/family/` (Tags tab), `ui/settings/NotificationSettings.kt`
  (`SwitchableNotificationTypes`), `notifications/Notifications.kt` (`PushMessageHandler`), `notifications/Reminders.kt` (`fire`),
  Android `notifications/AndroidNotifications.kt` (poster, PendingIntent), `MainActivity`, `ui/Navigation.kt`
- M1-05 components (`TaskRow`, `TagChip`, …)

## Scope

- Today block (TSK-023, FND-063), Today FAB → editor.
- Tag page (TAG-004, tasks section) reached from Family › Tags and from a `TagChip` tap on the task page.
- Notification settings switches: `task.assigned`, `task.completed`, `task.overdue`, `task.reminder` (TSK-034, FND-052).
- Deep links from notifications and reminders to the task page.
- User manual pages "Tasks" (en, ru) in `docs/user/`; CHANGELOG entry; M1 acceptance checklist in the handoff.

Out of scope: Today blocks of other modules (later milestones); tag page sections of other modules.

## Decisions (do not revisit)

- **Today block** (only with `tasks.view`): `SectionHeader` «Задачи на сегодня» with a count badge (open overdue + today, Mine
  scope) and a «Все» link that switches the main tab to Tasks with scope Mine. `ListGroup` with `TaskRow`s: overdue first, then
  today, then done-today rows, at most 6 rows + «Ещё N» row (opens Tasks). Ticking works as in M1-05 (snackbar with undo).
  No tasks → a single row «На сегодня задач нет» with `task_alt` icon; the M0 Today empty state remains only when the member sees
  no section with content. `TodayViewModel` (new, in `ui/main/`) reads `TasksRepository.observeViews()`; `MainViewModel` stays as is.
- **Today FAB:** `GradientFab(add, «Добавить»)` — in M1 it opens the task editor directly (assignee = me); later modules turn it
  into a chooser. Hidden without `tasks.create`.
- **Tag page** (route `TagPage(id)`, `AppPage` + `DetailTopBar(tag name, color dot)`): section «Задачи · N» with open tasks of the
  tag (`observeForTag`), sorted like "All"; empty «С этой меткой нет задач». Family › Tags rows open the tag page; editing the tag
  moves to the page's top-bar `edit` action (opens the existing tag sheet). Sections of other modules are added by their milestones.
- **Settings:** `SwitchableNotificationTypes = listOf(TASK_ASSIGNED, TASK_COMPLETED, TASK_OVERDUE, TASK_REMINDER, SYSTEM_TEST)`;
  label for `TASK_REMINDER` «Напоминания о задачах» / "Task reminders" (others exist). Toggling `TASK_REMINDER` triggers
  `TaskReminderPlanner.reconcile()`.
- **Deep links:** the Android poster puts `entityType`/`entityId` (pushes) or `taskId` (reminder ids `task:<id>`) into the
  content intent extras of `MainActivity` (`FLAG_ACTIVITY_SINGLE_TOP`); `MainActivity` forwards them to a shared
  `PendingNavigation` (Koin single, `StateFlow<NavTarget?>`), which `App()` consumes after login to navigate to `TaskDetail(id)`
  (back returns to Today). A deleted or invisible task → open Tasks tab and show «Задача удалена». Reminder text when fired is the
  stored one; the tap target is the task id.
- **Reminder texts** (`TaskReminderTexts` resource-backed implementation bound in `uiModule()`): body «Срок: сегодня в 20:00» /
  "Due today at 20:00", «Срок: завтра», «Срок: 31 октября» ; title = task title.
- **User manual:** `docs/user/en/tasks.md`, `docs/user/ru/tasks.md` — create a task, routines (both kinds with examples), assign
  and "anyone", checklist, reminders and notifications (what ntfy is needed for), what kids can do by default. Link from the
  user docs index if there is one.

## Steps

1. Today block + Today FAB; update `ScreensUiTest` (Today placeholder text changes).
2. Tag page; Family › Tags navigation change.
3. Settings switches; reminder reconcile on toggle.
4. Deep links (Android + shared `PendingNavigation`).
5. Docs: user manual, CHANGELOG `[Unreleased]` → Added: tasks (plain language, one paragraph).
6. Tests:
   - jvmTest `TodayViewModelTest`: block contents and order, count, max rows + "more", hidden without `tasks.view`.
   - jvmTest tag page ViewModel: tasks with the tag only, archived tag still shows its tasks.
   - jvmTest `PushMessageHandler`/poster routing: task notification carries the task id; `PendingNavigation` → `TaskDetail`.
   - jvmTest settings: the four task switches present and persisted through the existing settings repository.
   - Compose UI test: Today block renders the done row struck through with «<name> · в 9:12».
7. Emulator check via `emulator-tester` (local server with `seed.py family` + `seed.py tasks`): Today block (ru light and dark),
   tick from Today and undo, tag page from Family › Tags, settings switches; schedule a task reminder 2 minutes ahead and check it
   fires and the tap opens the task; push end to end only if a distributor is available (otherwise report "needs a phone with
   ntfy" as in M0-09). Screenshots: Today (ru light), Today (ru dark), tag page, notification settings, fired reminder.

## Acceptance

- Full gate `tools/dev/verify.sh` passes.
- Emulator walkthrough done (or reported as skipped with the reason), screenshot paths in the handoff.
- Every MVP requirement of the tasks spec is `done`, except TSK-022 (calendar, M3) and items explicitly noted as needing a
  real phone; spec status → `implemented` only after the customer's M1 acceptance (leave `approved`).

## Docs to update

- Tasks spec (states), tags spec TAG-002/003/004 (tasks part; TAG-004 stays `doing` until other modules add sections),
  foundation spec FND-052 (task switches), FND-063 (tasks block done, keep `doing`), FND-051 note.
- Roadmap: M1 state; STATUS.md (M1 acceptance next); plan status.

## Stop and report if

- Notification taps cannot carry extras with the current `NotificationPoster` interface without changing M0 behavior (then
  extend the interface with an optional target and note it).

## Handoff

Russian summary with screenshots and an M1 acceptance checklist for the customer: install the new APK, create the spec
scenarios, check Today, tags, reminders and notifications on real phones; known limits (calendar view of tasks comes in M3).
