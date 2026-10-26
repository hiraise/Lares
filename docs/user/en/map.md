# Map

Where the family is right now, the places that matter (home, school, work) and a notification when someone arrives at
or leaves a place. Positions go only to your family's own server and are seen only by those the administrator
allows.

## Where to find the map

- **More › Map** — the map with everybody you may see, your own sharing row and the list of the others under it:
  "Anya · School No. 12 · 3 min ago · 38 %".
- **Today** shows **Family now**: up to four members with their place or street address. **Map** opens the map, a tap
  on a member opens it at them.
- A member's page (More › Family) shows **Where now**; a tap opens the map at them.
- Tap a pin or a row to open the member's card: status, accuracy, battery, **Refresh**, **History** and **Notify me:
  arrive and leave**. The `my_location` button switches between **Show everybody** and **My location**.
- Without a connection the map shows the last known positions with "No connection".

## Turning on sharing

Each member shares from their own phone; only one phone per member shares at a time.

1. Open the map (or **More › Settings › Location**) and tap **Turn on location sharing**.
2. Allow **precise** location, then choose **Allow all the time** in the phone's settings (the app shows the exact
   wording of your phone). Without it the family does not see you while the app is closed.
3. Allow notifications: while sharing, the notification "You are sharing your location" is always shown.
4. Turn off battery saving for Lares when the app suggests it. Some brands need more:
   - **Xiaomi**: turn on "Autostart" and choose "No restrictions" in "Battery saver".
   - **Huawei / Honor**: App launch → Lares → Manage manually, turn on all three switches.
   - **Samsung**: Battery → Unrestricted; remove Lares from "Sleeping apps".
   - **OnePlus / Oppo / Realme**: Battery → Don't optimize; allow background activity.
   - Other phones: find Lares in the battery settings and allow it to run in the background without limits.

Sharing continues after a restart of the phone. **Settings › Location** shows this phone's state and the battery row.

## What others see

- Your pin in your personal color and a status line: the place you are in ("At home · since 15:10"), "On the way", or
  the street address with how long ago ("Lenina St, 12 · now").
- Your battery level.
- Grey pin: sharing is paused, turned off on the phone, or there is no fresh position for 30 minutes ("No data since
  18:05").
- When a kid turns location off on the phone, the map says so and administrators get "Location sharing turned off".

## Pause

Those allowed to pause (adults by default) tap **Pause** on the map, in **Settings › Location** or in the notification:
**For 1 hour**, **Until morning** (08:00) or **Until I turn it on**. Nothing is sent during a pause; the family sees your
last position in grey with "Paused until 15:30". **Resume** turns sharing back on; a timed pause ends by itself.
Kids cannot pause: their phone shows "Sharing can't be turned off in the app."

## Refresh

**Refresh** on a member's card asks their phone for a fresh position right away. Usually it arrives within a minute;
after 90 seconds without an answer the card says "The phone did not answer". Everybody who sees a member may refresh them;
a paused member is not asked.

## Places

**Places** (top of the map) lists the places with who is there now. A place is a named circle with an icon.

- Create: **Add place**, a long press on the map, or in the editor **I'm here** or a search by address (type, tap
  **Find**, pick a result). Set the circle size (50–1000 m) with the slider and save. This works offline too.
- A tap on a place shows its address and who is there; **Edit** and **Delete place** for those allowed to manage
  places (adults by default).

## Arrive and leave notifications

Turn them on for yourself: on a member's card (or their page) tap **Notify me: arrive and leave** and tick the places,
or on a place tap **Notify me** and tick the members. Then **Save**. Nothing is on by default.

- The notification looks like "Anya · School No. 12 — Arrived at 08:52" or "… — Left at 14:20".
- Arrivals and departures are decided by the server from the positions: it needs two agreeing positions, ignores
  inaccurate ones and someone standing at the edge of the circle.
- You get them only while you may see the member. Positions sent late after a network gap (older than 15 minutes)
  update the map silently.
- Tap the notification to open the map at the member. Turn them off in **More › Settings › Notifications › Places:
  arrive and leave**; administrators also have **Location sharing turned off** there.

## History

**History** on a member's card shows a day: the track on the map and the stops under it ("School No. 12 · 8:25–14:40",
"At home · since 16:50", a street address for other stops). Pick the day from the strip at the top. History needs a
rule "with history" (administrators see everybody's; everybody sees their own) and a connection; it is never stored on
the phone.

## Who sees whom (administrators)

**More › Family › Family settings › Location: who sees whom**:

- Rules as sentences: "Adults see everybody · with history", "Kids see: Adults · where now", "Granny sees: Anya ·
  where now". Tap a rule to change **Who**, **Sees whom** and **What is visible** (**Where now** or **With
  history**), or delete it; **Add rule** adds one. **Save** applies the whole list.
- Several rules can match: the widest wins. Rules only grant visibility; there are no "deny" rules. Without a rule a
  member sees nobody but themselves; administrators always see everybody with history.
- **Keep history**: 7, 14, 30 (default), 60 or 90 days. Older positions are deleted within an hour.
- This screen needs a connection.

Everybody sees in **Settings › Location** who can see them (**Who can see me**, with "where now" or "with history")
and who gets arrive / leave notifications about them (**Notified about you**: "Mom — School No. 12, Home").

## Addresses and privacy

- Street addresses come through your family server: it sends the coordinates of shown positions (rounded to about
  10 m) or the text you search for to the address service the administrator configured — by default OpenStreetMap's
  public Nominatim. Phones never contact it directly.
- The server administrator can turn addresses off (`LARES_GEOCODER_URL=` in `.env`, see
  [Installing the server](install-server.md)); the map then shows the distance to the nearest place.
- The map itself also comes through your family server, which fetches it from an OpenStreetMap source (VersaTiles by
  default) and keeps it for a week; the source learns which map areas are viewed, not who views them or who is where.
- Positions are deleted after the history period; removing a member deletes their positions at once.

## Who may do what

The family administrator decides this with groups (More › Family). By default:

| | Adults | Kids |
| --- | --- | --- |
| Open the map | yes | yes |
| See others | everybody, with history | Adults, where now |
| Share own location | yes | yes |
| Pause or turn off own sharing | yes | no |
| Refresh a member they see | yes | yes |
| Create, change and delete places | yes | no |
| Arrive / leave notifications for themselves | yes | yes |
| Rules and history period | administrators only | no |

## Not yet

GPS trackers (for a car, a pet or a kid without a phone), schedule-aware alerts ("not at school by 8:30"), offline
maps. These are planned for later versions.
