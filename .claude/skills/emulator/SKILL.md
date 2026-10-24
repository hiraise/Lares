---
name: emulator
description: Run and drive the Lares Android app on the local emulator against a local server — boot, install, test family, tap by text, screenshots. Use for any manual app check or when the customer asks to start the emulator or see the app. In plan sessions, hand the check to the emulator-tester agent instead of doing it yourself.
---

# Emulator and local server

Scripts in `tools/dev/` (run from the repo root; each prints its usage when called without arguments).

## Who does the check

- **Plan session (implementing code):** do not drive the emulator yourself. Start the `emulator-tester` agent with
  the Agent tool and give it: what changed, the scenarios to walk through (numbered), what to compare with
  (mockup file, spec requirement IDs), which languages / themes. It returns a short report and screenshot paths.
  After fixing bugs, send the same agent a short re-check request (SendMessage) instead of starting a new one.
- **Customer wants to look at the app:** `tools/dev/emu.sh start --window`, then the steps below.

## Setup (once per session)

```sh
tools/dev/server.sh start          # dev DB + server on :8080, own database lares_e2e; `reset` = empty server
tools/dev/seed.py family           # test family Петровы (olga / olga-password is the admin)
tools/dev/seed.py tasks            # sample tasks: routines, checklist, overdue, tag «Машина» (after family)
tools/dev/seed.py shopping         # lists «Аптека», «Дача» (tag «Дача»), «Продукты» (active; dima ticked 2) (after family)
tools/dev/seed.py tick Хлеб --as dima   # another member ticks an item now: «who is shopping» for 15 min
tools/dev/emu.sh start             # headless; run in background, boot takes about a minute
tools/dev/emu.sh install           # build + install debug APK + launch (after every code change)
```

Port 8080 busy (another server running)? Use `LARES_PORT=8081` for all scripts in this list.
In the app the server address is `http://localhost:8080` (adb reverse is set by `emu.sh start`).
First run on an empty server: setup code `DEVSETUP01`.

## Driving the app

```sh
tools/dev/ui.sh dump                # texts with tap coordinates, inputs as [field#N], flags checked/selected/disabled
tools/dev/ui.sh tap "Семья"         # waits up to 8 s for the text; "tap TEXT N" for the N-th match
tools/dev/ui.sh field 0; tools/dev/ui.sh type "olga"   # ASCII only (adb limit): use Latin test data
tools/dev/ui.sh wait "Готово" 10    # assert text appears; `has TEXT` checks the current screen
tools/dev/ui.sh back | enter | up | down | xy X Y | clear
tools/dev/ui.sh shot members_dark   # 540×1200 PNG in build/dev/shots/; Read it only when you must see the look
tools/dev/ui.sh crash               # crash buffer + ANRs since the last launch
tools/dev/emu.sh fresh              # clear app data (back to the Server screen)
tools/dev/emu.sh link "$(tools/dev/seed.py invite | sed -n 's/^payload //p')"   # open an invite deep link
tools/dev/seed.py login-code Аня    # login QR payload for a member; open it with emu.sh link
```

Chain several actions in one Bash call and end with `dump` (cheap text) to see where you are. Take a screenshot only
to check visual details (layout, colors, dark theme) or for the handoff, and do not Read the same screen twice.

## Language and theme

Switch in the app: More → Settings → Language / Theme. The test family has Russian names; UI language follows the
app setting.

## Cleanup

`tools/dev/emu.sh stop`, `tools/dev/server.sh stop`.
