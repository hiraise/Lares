---
status: approved  # customer, 2026-10-25 (Q-025…Q-029 answered the same day)
milestone: M4 (phones, map, history, places); GPS trackers later
updated: 2026-10-25
---
# Location (LOC, TRK)

## Summary

See where family members are on a map. A member turns on location sharing once on their phone; the phone then sends
positions in the background — often while moving, rarely while staying in one place — and always shows that sharing is
on. Family members who may see them find them on the map with the time of the position, its accuracy and the battery
level; those allowed see the movement history of each day. Places (Home, School) are circles on the map; everybody can
ask to be told when someone arrives at or leaves a place. Who sees whom is set by administrators with rules by groups and
members. Positions go only to the family's own server and are deleted after 30 days (configurable).
GPS trackers come later. Technical design: [architecture/location.md](../../architecture/location.md).

## Scenarios

1. Mom opens Map for the first time: «Включите передачу геопозиции». She taps it, allows location «Всегда» and
   notifications, and turns off battery optimization when the app suggests it. Her phone shows «Lares · Вы делитесь
   геопозицией». Dad and the kids do the same on their phones.
2. Dad opens Map: the pins of Mom, Аня and Тимур in their personal colors; under the map «Тимур · Дома · с 15:10 · 64 %»,
   «Аня · Школа №12 · 3 мин назад · 38 %», «Мама · ул. Ленина, 12 · сейчас · 81 %».
3. Mom wants to know where Тимур is right now: she taps him and «Обновить». A minute later his pin moves and shows
   «сейчас».
4. Mom creates the place «Школа №12»: she finds it by address, sets the radius to 150 m and saves. On Аня's card on the map she turns
   on «Сообщать: прибытие и уход · Школа №12». At 08:52 Mom gets «Аня · Школа №12 — Прибытие в 08:52»; at 14:20 «Аня ·
   Школа №12 — Уход в 14:20».
   In Settings › Геопозиция Аня sees «О вас сообщают: Мама — Школа №12».
5. Dad looks at Тимур's Tuesday: the track on the map and the stops under it — «Дома 07:00–08:10», «Школа №12
   08:25–14:40», «ДК «Юность» 15:00–16:30», «Дома с 16:50».
6. Dad goes to a meeting and pauses sharing «На 1 час». The family sees «Папа · на паузе до 15:30» with his last position
   in grey. After an hour sharing resumes by itself.
7. Тимур (Kids, no right to pause) turns off location in his phone's settings. The map shows «Тимур · геопозиция
   выключена на телефоне · последнее место в 14:20», and Mom and Dad (administrators) get «Геопозиция
   выключена — Тимур · геопозиция выключена на телефоне». Later his phone runs out of battery: the map shows «нет данных с 18:05 · 3 %», without a notification.
8. Тимур opens Map: he sees Mom and Dad where they are now, but not their history, and not Аня (the default rules).
9. Mom, an administrator, opens Family › Геопозиция and adds a rule «Бабушка видит: Дети · где сейчас». She sets history
   to keep 14 days.
10. Today shows «Семья сейчас»: «Папа · Магазин у дома», «Аня · Школа №12», «Тимур · Дома»; a tap opens the map.

## Requirements

### Sharing from the phone

- **LOC-001** `MVP` `todo` — A member turns on location sharing on their phone (Map or Settings › Геопозиция). The app
  asks for precise location «Всегда» (background) and notifications; without background permission sharing is not
  started and the app explains why. After that the phone sends positions in the background, also after a restart of the
  phone.
- **LOC-002** `MVP` `todo` — While sharing is on, the phone shows a persistent notification «Вы делитесь геопозицией»
  (with «Пауза» for those who may pause). Inside the app Map shows «Вы делитесь местоположением · Пауза».
- **LOC-007** `MVP` `todo` — Battery-aware tracking: while moving, a position about every 30 seconds (or every 50 m);
  while staying in one place, one position every 15 minutes with the battery level. Accuracy, speed and battery are sent
  with each position.
- **LOC-008** `MVP` `todo` — The app guides the member to turn off battery optimization (with brand-specific hints for
  Xiaomi, Huawei, Samsung and others) and shows a warning on the phone when it could not send positions for a long time.
- **LOC-010** `MVP` `todo` — Pause (Q-025): a member with `location.pause_own` pauses sharing «На 1 час», «До утра»
  (until 08:00) or «Пока не включу». Nothing is sent during the pause; it ends by itself at the chosen time.
  Others see «на паузе до 15:30» (or «на паузе») and the last position before the pause in grey. A member without the
  permission has neither «Пауза» nor a way to turn sharing off in the app.
- **LOC-019** `MVP` `todo` — One phone per member shares. ASSUMPTION: turning sharing on on another phone of the same
  member moves sharing there; the first phone stops and says so.
- **LOC-027** `MVP` `todo` — Without network the phone keeps its positions (up to 24 hours) and sends them when the network
  is back, so the history has no gap; the map then shows the newest one.

### Map

- **LOC-003** `MVP` `todo` — Map (More › Карта) with the latest position of each member the viewer may see: a pin with
  the avatar in the personal color, the accuracy circle, the places. Under the map, a list of these members: place or
  address, how long ago («сейчас», «3 мин назад», «с 15:10» when in a place), battery level. A tap on a member centres
  the map on them and opens their card. «Моё местоположение» centres on the viewer.
- **LOC-009** `MVP` `todo` — Map tiles from an OpenStreetMap-based source configured on the server (ADR-0006); the map
  follows the light and dark theme. The map needs a network connection; offline it shows the last known positions with
  «Нет связи».
- **LOC-011** `MVP` `todo` — «Обновить» (Q-027): everybody who sees a member can ask their phone for a fresh precise
  position; it arrives within about a minute if the phone is online. Not available while the member is paused. At most
  once a minute per member.
- **LOC-012** `MVP` `todo` — Sharing turned off (Q-026): when a member without `location.pause_own` turns off location
  in the phone's settings, takes the app's location permission away or stops its sharing, and the phone can still tell
  the server, the map shows «геопозиция выключена на телефоне · последнее место в 14:20» and administrators get a
  notification «Геопозиция выключена — Тимур · геопозиция выключена на телефоне» (or «нет доступа к геопозиции»,
  «передача остановлена», «выход из приложения»). When it is back on, they get «Геопозиция снова передаётся — Тимур».
- **LOC-013** `MVP` `todo` — Stale positions: when nothing arrived for 30 minutes from a member who shares, the map shows
  «нет данных с 18:05» and the last battery level; no notification (Q-026). A member who never turned sharing on shows as
  «не делится геопозицией».
- **LOC-014** `MVP` `todo` — Addresses (Q-029): when a member is not in a place, the list shows the street address of
  their position («ул. Ленина, 12»), found by the server through an OpenStreetMap address service. While moving it shows
  «В пути». The server administrator can turn addresses off or use another address service; then the list shows the
  distance to the nearest place («2 км от Дома»).

### History

- **LOC-004** `MVP` `todo` — Movement history per member and day: the track on the map and the list of stops under it
  («Школа №12 08:25–14:40», addresses for stops outside places); a tap on a stop shows it on the map. Days are picked
  from a strip of the retained days. ASSUMPTION: a stop is a stay of at least 10 minutes within about 100 m.
- **LOC-005** `MVP` `todo` — History is deleted after the retention period: 30 days by default; an administrator chooses
  7, 14, 30, 60 or 90 days (Family › Геопозиция). A shorter period deletes older positions within an hour.

### Who sees whom

- **LOC-006** `MVP` `todo` — Visibility rules by groups and members: `<who> sees <whom>: where now | with history`; «who»
  is a group or a member, «whom» is everybody, a group or a member; set by administrators in Family › Геопозиция. When
  several rules apply, the widest wins. Administrators see everyone with history; everybody sees their own history. The
  Map needs `location.view`. Defaults (foundation): Adults see everyone with history; Kids see Adults, where now only.
- **LOC-015** `MVP` `todo` — Transparency: in Settings › Геопозиция every member sees who can see them (and whether
  with history) and who gets arrive / leave notifications about them (Q-028).

### Places and notifications

- **LOC-020** `MVP` `todo` — Places: a named circle (centre + radius 50–1000 m, 100 m by default) with an icon, e.g.
  «Дом», «Школа №12». Created by a tap on the map, «Я здесь» or a search by address (LOC-024); shown on the map as
  labelled circles and in the list behind «Места»; a tap on a place shows it and who is there. Created, changed and deleted by
  members with `location.manage_places`; seen by everybody with `location.view`.
- **LOC-021** `MVP` `todo` — Arrive / leave notifications (Q-028): everybody who sees a member turns on for themselves
  «Сообщать: прибытие и уход» for chosen places (on the member's card or on the place). Nothing is on by default.
  Texts: «Аня · Школа №12 — Прибытие в 08:52», «… — Уход в 14:20» (no gendered verbs: the app does not know genders).
  A notification goes only while the subscriber may see the member. ASSUMPTION: a change found from positions older
  than 15 minutes (sent late after a network gap) updates the map silently.
- **LOC-022** `MVP` `todo` — Arrivals and departures are decided on the server from the positions, so they work for
  phones and later for trackers alike. ASSUMPTION: an inaccurate position (worse than the place's radius or 100 m, whichever is
  larger) does not decide anything; leaving needs a position clearly outside the circle, so a person sitting at the edge does not flicker in and
  out.
- **LOC-024** `MVP` `todo` — Address search in the place editor (Q-029): type an address, press search, pick a result;
  the map moves there.
- **LOC-023** `later` `todo` — Schedule-aware alerts ("not at school by 8:30 on a weekday").

### Everywhere

- **LOC-016** `MVP` `todo` — Today «Семья сейчас» (FND-063, mockup): the members the viewer sees with their place or
  address; a tap opens Map. Only with `location.view`.
- **LOC-017** `MVP` `todo` — A member's page shows «Где сейчас» (place or address, how long ago) for viewers who see them;
  a tap opens Map centred on them; «История» for viewers with history.
- **LOC-018** `MVP` `todo` — Settings › Notifications has switches for arrive / leave notifications («Места: прибытие и
  уход») and, for
  administrators, for «передача геопозиции выключена» (FND-052). Settings › Геопозиция: sharing on/off or pause, the
  battery optimization guide, «Кто меня видит».

## GPS trackers (later)

Not scheduled: the customer has no trackers yet (2026-10-22). Kept for when it is planned.

- **TRK-001** `MVP` `todo` — A tracker is registered by admin and linked to a member or to an object (car, pet).
- **TRK-002** `MVP` `todo` — Trackers speaking the OsmAnd HTTP protocol report directly to the server.
- **TRK-003** `MVP` `todo` — Other protocols (incl. kids' GPS watches) via an optional Traccar server forwarding positions (ADR-0009).
- **TRK-004** `later` `todo` — Tracker battery and connectivity alerts.

## Permissions and privacy

Location is the most sensitive data in the system.

- Positions are seen only by the visibility rules (LOC-006); never through tags, other modules, search or exports.
  Without a rule, a member sees nobody but themselves. Positions are never sent to phones that may not see them.
- The tracked person always knows: the persistent notification on their phone (LOC-002), «Кто меня видит» and «О вас
  сообщают» (LOC-015).
- Positions are deleted after the retention period (LOC-005); deleting a member deletes their positions at once.
- Addresses (LOC-014, LOC-024): the server sends the coordinates of shown positions (rounded to about 10 m) or the typed
  search text to the address service the administrator configured — by default OpenStreetMap's public Nominatim. The
  phones never contact it directly. The administrator can turn it off.
- Map tiles come from the configured tile server; it learns which map areas are viewed, not who is where.

Permissions ([catalogue](foundation.md#permission-catalogue)): `location.view`, `location.pause_own`,
`location.manage_places`; the rules are managed with `household.manage`.

| Action | Who |
| --- | --- |
| Open Map, see members | `location.view`, members per the rules |
| See a member's history and stops | `location.view` and a rule «with history» (administrators: everyone) |
| «Обновить» a member's position | everybody who sees that member |
| Share own location | every member (on their own phone) |
| Pause or turn off own sharing | `location.pause_own` |
| Create, change, delete places | `location.manage_places` |
| Arrive / leave notifications about a member | everybody who sees that member, for themselves |
| Visibility rules, history retention | administrators (`household.manage`) |

Templates: Adults have all three location permissions; Kids have `location.view` only (they share, see per the rules,
cannot pause). Administrators have everything.

## Data

Position (member, time, coordinates, accuracy, speed, battery), the member's sharing state (on, paused until, off on the
phone), place, arrive / leave subscription, visibility rule, history retention. Positions are not kept on the phones of
others: the map loads them from the server. Design: [architecture/location.md](../../architecture/location.md).

## Notifications

| Notification | Who gets it | Switch |
| --- | --- | --- |
| «Аня · Школа №12 — Прибытие в 08:52» / «… — Уход в 14:20» | subscribers who see Аня | «Места: прибытие и уход» |
| «Геопозиция выключена — Тимур · …» / «Геопозиция снова передаётся — Тимур» | administrators, not the member | «Передача геопозиции выключена» |
| «Обновить» request | the member's sharing phone, silently | — |

## Customer decisions (2026-10-25)

- Q-025 — pause «На 1 час», «До утра», «Пока не включу»; others see «на паузе» and the last position.
- Q-026 — sharing turned off on the phone: a mark on the map and a notification to administrators; no data for a while:
  a mark only.
- Q-027 — «Обновить» for everybody who sees the member.
- Q-028 — everybody who sees a member sets their own arrive / leave notifications; the member sees who gets them.
- Q-029 — street addresses through an OpenStreetMap service (via the server), also for the place search.

## Assumptions

Agent defaults, easy to change after feedback:

- «До утра» ends at 08:00 in the family's time zone (the next day if it is already past 08:00).
- A position is stale after 30 minutes without new ones (LOC-013).
- A stop in the history is at least 10 minutes within about 100 m (LOC-004).
- Battery 15 % or lower shows in red; there is no low-battery notification.
- Turning sharing on on a second phone of the same member moves it there (LOC-019).
- The tracked member is not told when somebody presses «Обновить» on them.
- Members always see their own history, also without a rule.
- A kid's phone needs sharing turned on once on that phone (a parent can do it); the app cannot turn it on remotely.

## Out of scope (M4)

- GPS trackers (TRK, later), schedule-aware alerts (LOC-023).
- Low-battery alerts, an SOS button, driving reports, speed alerts.
- Sharing with people outside the family, a link to share one's position for an hour.
- Offline maps (tiles stored on the phone), routes and directions.
- Location in other modules (a shopping list reminder near a shop, the place of an event on the map).
- The web, iOS and desktop clients (M7+).
