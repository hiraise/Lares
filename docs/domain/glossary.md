# Glossary

Use the **Code term** in code, APIs and technical docs. The **RU** column is what the customer and the Russian UI say.
Add terms here before using them in code.

## Core

| Code term | RU | Definition |
| --- | --- | --- |
| Instance | Сервер, экземпляр | One running server installation. May host several households. |
| Operator | Администратор сервера | Person who runs the instance (usually the admin parent). |
| Household | Семья | Tenant: a family and all its data (ADR-0010). |
| User | Пользователь | Login identity (credentials, sessions). Global to the instance. |
| Member | Член семьи; in the UI «Участник» (Family › Участники) | A user's membership in a household. Children are ordinary members. |
| Group | Группа | Set of members that receives permissions (ADR-0014). A member can be in several groups. |
| Administrators | Администраторы | Built-in group: all permissions, sees all data, manages the household. Cannot be deleted; at least one member. |
| Group template | Шаблон группы | Group created at household setup with default permissions: "Adults" (Взрослые), "Kids" (Дети). Editable. |
| Permission | Право | Named capability `<module>.<action>`, e.g. `tasks.edit_any`. Effective permissions = union of groups. |
| Section access | Доступ к разделу | Permission `<module>.view`: whether the member sees a section at all. |
| Access rule | Правило видимости | Data visibility with subject and target (group or member): location visibility, money account access. |
| Ownership rule | Правило владельца | Rights independent of groups: creator edits own items, assignee completes own tasks. |
| Person | Человек, родственник; UI tab «Родные» | Anyone relevant to the family. Every member has one; relatives without accounts are persons only. |
| Relation | Кем приходится | Free-text or preset label on a person: grandmother, uncle, friend. |
| Invite | Приглашение | One-time link/QR to join a household with a given role. |
| Device | Устройство | A logged-in app installation (phone) or a GPS tracker. |
| Session | Сеанс, вход на устройстве | One logged-in app installation of a user: an access token (1 h) and a rotating refresh token (90 days). Revoked, never deleted. |
| Setup code | Код первой настройки | One-time code from the server log that protects the first-run setup of an instance. |
| Login code | Код входа | One-time QR code (5 min) that logs a member in on a new device. |
| Tag | Метка (UI; formerly «Тег») | Household-wide label attachable to items in any module. |
| Tag page | Страница тега | Aggregated view of everything linked to a tag. |
| Palette color | Цвет | One of 10 fixed colors (`PaletteColor`) used by tags and personal colors (ADR-0016). |
| Personal color | Личный цвет | A person's palette color: avatar, events, map pin, assignments (FND-034). |
| Today | Сегодня | Home screen summarizing the day across sections (FND-063). |
| More | Ещё | Last bottom-bar item: profile, sections outside the bar, settings (FND-064). |

## Tasks and lists

| Code term | RU | Definition |
| --- | --- | --- |
| Task | Задача | Something to do, optionally with due date and assignees. |
| Routine | Регулярная задача | Task with a recurrence rule. |
| Occurrence | Повторение | One instance of a routine, identified by its date. Computed, not stored (ADR-0017). |
| Current occurrence | Текущее повторение | The one open occurrence of a routine; for a one-off task, the task itself. |
| Anyone task | Задача для всех («Кто угодно») | Task without assignees: any member with access can complete it (TSK-002). |
| Fixed schedule | По расписанию | Routine repeating on calendar dates (every N days/weeks on weekdays/months/years). |
| After-completion schedule | После выполнения | Routine whose next occurrence counts from the last completion (TSK-011). |
| Skip | Пропустить | Close the current occurrence of a routine without doing it; recorded in history (TSK-005). |
| Assignee | Исполнитель | Member responsible for a task. |
| Rotation | Очерёдность | Assignees of a routine take turns. |
| Completion | Выполнение | Record of who completed (or skipped) an occurrence and when. Immutable; undo deletes it. |
| Checklist item | Пункт чек-листа | Sub-item inside a task. |
| Shopping list | Список покупок | Shared list of things to buy (SHP-001). |
| Shopping item | Товар | One product in one list. Kept after it is bought or removed: the list's memory (ADR-0021). |
| Item status | Нужно купить / Куплено / Убрано | `needed`, `bought` (ticked, in «Куплено»), `cleared` (out of the list, offered as a suggestion). |
| Department | Отдел | Store department of an item (`ProductCategory`), chosen automatically, correctable (SHP-006). |
| Quick add | Быстрое добавление | One input for one or several items with quantities (SHP-003). |
| Suggestion | Подсказка | A cleared item of the list offered for one-tap re-adding; also names while typing (SHP-008). |
| Clear bought | Убрать купленные | Move all bought items of a list out of it (they stay as suggestions) (SHP-004). |
| Shopper | «Сейчас в магазине» | Another member who ticked an item of the list in the last 15 minutes (SHP-012). |
| Active list | Активный список | The list with items to buy changed most recently; the hero card on Today (SHP-013). |

## Calendar

| Code term | RU | Definition |
| --- | --- | --- |
| Event | Событие | Calendar entry with time or all-day date. |
| Participant | Участник, «кого касается» | Person an event concerns. |
| Birthday | День рождения | Yearly date derived from a person's birth date. |
| Reminder | Напоминание | Per-user alert before an event or task. |

## Location

| Code term | RU | Definition |
| --- | --- | --- |
| Location sharing | Передача геопозиции | Member's phone sending positions to the server. |
| Position | Точка, координата | One location fix: coordinates, time, accuracy, battery. |
| Track | Трек | Sequence of positions over a period. |
| Place | Место | Named area on the map (circle). |
| Geofence alert | Уведомление о геозоне | Notification on arrival at / departure from a place. |
| Tracker | GPS-маячок, трекер | Hardware device reporting positions (watch, car tracker). |
| Trackable | Отслеживаемый объект | What a tracker is attached to: member, car, pet. |

## Budget

| Code term | RU | Definition |
| --- | --- | --- |
| Money account | Счёт | Cash, card or savings account. Named `MoneyAccount` in code to avoid clashing with User. |
| Transaction | Операция | Expense, income or transfer. |
| Transfer | Перевод | Movement between money accounts. |
| Category | Категория (конверт) | Envelope holding allocated money. |
| Allocation | Распределение | Assigning money to a category. |
| Allocation rule | Правило распределения | Ordered rule: fixed amount or percent of income to a category. |
| Income source | Источник дохода | E.g. a member's salary; has its own rule set. |
| Unallocated | Нераспределённое | Income not yet assigned to categories. |
| Category deficit | Перерасход категории | Negative category balance, repaid from future allocations. |
| Category loan | Займ между категориями | Money one category borrowed from another, tracked until repaid. |
| Debt | Долг, кредит | Real liability or receivable with a counterparty and schedule. |
| Payment schedule | График платежей | Planned debt payments. |

## Sync

| Code term | RU | Definition |
| --- | --- | --- |
| Mutation | Изменение | One client-side change sent to the server. |
| Outbox | Очередь отправки | Local queue of unsent mutations. |
| Change sequence (`seq`) | Номер изменения | Per-household counter assigned by the server. |
| Cursor | Курсор | Last `seq` a client has pulled. |
| Tombstone | Метка удаления | Marker that a record was deleted, so deletion syncs. |
| Sync tickle | Сигнал синхронизации | Content-free push (`kind: sync`) that makes phones in the background sync; at most one per 30 s per household. |

## Notifications

| Code term | RU | Definition |
| --- | --- | --- |
| Reminder | Напоминание | Time-based notification scheduled on the phone; fires without network (FND-050). |
| Notification type | Тип уведомления | Kind of event notification (`NotificationType`, e.g. `task.assigned`); each member can mute types (FND-052). |
| Notification channel | Канал уведомлений | Android channel a type is shown on: `reminders`, `family_activity`, `system`. |
| Distributor | Приложение для уведомлений (ntfy) | UnifiedPush app on the phone that holds the connection to a push server, e.g. ntfy (ADR-0006). |
| Push registration | Подписка на уведомления | A session's UnifiedPush endpoint and encryption keys stored on the server (`push_registration`). |
