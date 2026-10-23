# Instant notifications with ntfy

Lares does not use Google services. Instant notifications (for example "a task was assigned to you") reach the
phone through **ntfy**, a free open-source app that keeps one connection to your family's own ntfy server.

Without ntfy the app still works: it checks for news about every 15 minutes in the background, and reminders you set
in the app always fire on time, even without internet.

## What you need

- The address of your family's ntfy server, e.g. `https://ntfy.family.example.com`. The family administrator gets it
  when installing the server (the `push` option of the Docker Compose setup).
- Android 8 or newer.

## Set it up on the phone

1. Install **ntfy** from [F-Droid](https://f-droid.org/packages/io.heckel.ntfy/) or Google Play.
2. Open ntfy → **Settings** → **Default server** and enter your family's ntfy address.
3. Open Lares → **More** → **Settings** → **Notifications**. Within a few seconds the status changes to
   "Connected via ntfy". If several such apps are installed, choose ntfy in the list.
4. Tap **Send test notification**. A "Test notification" should appear within a few seconds.

On Android 13 and newer Lares asks for permission to show notifications after you log in. If you said "Not now",
allow it in Settings › Notifications › On this phone.

## Reminders on time

Android 12 and newer may delay reminders by a few minutes unless you allow exact alarms. In Settings › Notifications ›
On this phone tap **Allow** next to "Reminders on time" and switch the permission on. Use **Test reminder in 1 minute**
under Diagnostics to check: it works even in airplane mode.

## Turning notification types off

Settings › Notifications › What to notify about: switch off what you do not want. The choice is stored on the family
server, so it applies to all your devices, and muted notifications are not sent at all.

## Troubleshooting

- **"Not set up"**: ntfy is not installed, or Lares has not seen it yet — reopen the Notifications screen.
- **Test says the connection expired**: open ntfy once (it may have been stopped) and try again.
- **Nothing arrives**: in ntfy check that the default server is your family's address; on some phones (Xiaomi, Huawei,
  Samsung) allow ntfy to run in the background in the battery settings.
