# Developer machine setup

How the development machine (macOS, Apple Silicon, Homebrew) was prepared in M0-01. Versions:
[versions.md](../plans/M0/versions.md). Nothing here needs `sudo`, and shell profiles are not changed.

## Installed

| What | How | Why |
| --- | --- | --- |
| JDK 21 | `brew install openjdk@21` | Runs Gradle and compiles all modules (toolchain 21) |
| Gradle (system, temporary) | `brew install gradle`, removed afterwards | Only used once to generate the wrapper; use `./gradlew` |
| Android SDK | Already present from Android Studio: `~/Library/Android/sdk` | Build the Android app; shared with Android Studio |
| SDK packages | `sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "emulator" "cmdline-tools;latest"` | Android 37 platform; emulator, adb and avdmanager updated to current versions |
| Python 3 + fonttools (only for design assets) | `python3 -m venv tools/design-assets/.venv && tools/design-assets/.venv/bin/pip install fonttools` (the venv is gitignored) | Regenerate fonts and icons with `tools/design-assets/build.py` ([README](../../tools/design-assets/README.md)); not needed to build the app |
| AOSP emulator image (~1.5 GB) | `sdkmanager "system-images;android-37.0;default;arm64-v8a"` | Visual checks; image has no Google services (ADR-0006) |

SDK root: `~/Library/Android/sdk` (the Android Studio SDK; `sdkmanager` is in `cmdline-tools/latest/bin`). Do not
install a second SDK through Homebrew (`android-commandlinetools`): M0-01 did that by mistake, and it was removed.
The plan's Homebrew path `/opt/homebrew/share/android-commandlinetools` is not used on this machine.

## Configuration

- `~/.gradle/gradle.properties` (user-wide, outside the repo) contains:

  ```properties
  org.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ```

  Gradle therefore always runs on JDK 21, even if `java` on `PATH` is another version.
- `local.properties` in the repo root (gitignored): `sdk.dir=/Users/<user>/Library/Android/sdk` (absolute path).
  Create it in every fresh clone.
- For shell commands that need the JDK or SDK directly (not needed for `./gradlew`):

  ```sh
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  export PATH="$JAVA_HOME/bin:$PATH"
  export ANDROID_HOME=$HOME/Library/Android/sdk
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
  ```

## Gradle wrapper

Created with `gradle wrapper --gradle-version 9.7.0 --distribution-type bin`. `gradle/wrapper/gradle-wrapper.jar` is
committed. Do not regenerate with a different version (versions.md upgrade rule).

## Agent dev scripts (`tools/dev/`)

Wrappers that keep output short for agents; each prints its usage when run without arguments. Outputs and logs go
to `build/dev/` (ignored by git).

| Script | Does |
| --- | --- |
| `verify.sh [tasks]` | `spotlessApply` + tasks (default: `check :client:android:assembleDebug`); prints errors, failed tests, totals |
| `server.sh start\|stop\|restart\|reset\|status\|log` | Dev DB + server in background on `FAMILYHUB_PORT` (8080), own database `familyhub_e2e`, setup code `DEVSETUP01` |
| `seed.py family\|token\|invite\|login-code\|call` | Test family Петровы via the API (`olga / olga-password` admin, `dima`, kids Аня and Тимур) |
| `emu.sh start [--window]\|install\|launch\|fresh\|link URL\|stop` | Emulator lifecycle, `adb reverse`, deep links |
| `ui.sh dump\|tap\|wait\|has\|field\|type\|shot\|crash …` | Drive the app by visible text; screenshots downscaled to 540×1200 |

The dev DB port is detected from the running container (5432, or 5434 when 5432 is taken by another PostgreSQL).

## Emulator

```sh
avdmanager create avd -n familyhub -k "system-images;android-37.0;default;arm64-v8a" -d pixel_7   # once
$ANDROID_HOME/emulator/emulator -avd familyhub -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device
adb shell getprop sys.boot_completed      # repeat until it prints 1 (about a minute)
./gradlew :client:android:installDebug
adb shell am start -n app.familyhub.android.debug/app.familyhub.android.MainActivity
adb exec-out screencap -p > <scratchpad>/screen.png
adb emu kill
```

### App against the local dev server

Start the dev database and `./gradlew :server:run` (AGENTS.md "Commands"); the setup code is printed in the server log.
On this machine the emulator cannot open TCP connections to the host (`http://10.0.2.2:8080` times out although ping
works), so forward the port through adb and use `http://localhost:8080` as the server address in the app:

```sh
adb reverse tcp:8080 tcp:8080
```

Debug builds allow plain HTTP to `10.0.2.2`, `localhost` and `127.0.0.1` only (`client/android/src/debug/res/xml/
network_security_config.xml`); release builds are HTTPS-only. Typing in the app: `adb shell input text "…"` (ASCII only),
then `adb shell input keyevent 66` (Enter). A typed address without a scheme gets `https://`.

## Troubleshooting (seen in M0-01 and M0-07)

- **Old `cmdline-tools` in the Android Studio SDK** (`avdmanager` fails with "No device found matching --device pixel_7")
  and **old emulator** (32.x cannot run Android 37 images) — update with `sdkmanager "cmdline-tools;latest" "emulator"`.
- **`avdmanager` prints "Could not load devices from …/devices.xml"** — harmless; the AVD is created
  (`avdmanager list avd` shows it).
- **AGP downloads `build-tools;36.0.0` on the first build** — AGP 9.3.3 uses its own default build-tools version and
  installs it automatically (licenses are already accepted). Both 36.0.0 and 37.0.0 stay installed; no action needed.
- **"Unable to strip the following libraries … libandroidx.graphics.path.so"** — the NDK is not installed, so the native
  library is packaged unstripped. Harmless for debug builds.
- **"Type-safe project accessors is an incubating feature."** — expected; the feature is enabled on purpose.
- **Configuration cache "cannot be reused because the file system entry … has been created"** — happens after the SDK
  changes; the next build stores a new entry.
- **`timeout` command not found** — macOS has no GNU `timeout`; use plain `adb wait-for-device`.
- **macOS `java -version` shows another JDK** — expected if another JDK is on `PATH`; Gradle uses JDK 21 from
  `~/.gradle/gradle.properties`.
- **Camera preview is black on the headless emulator** — the camera opens, but `-no-window` delivers no frames, so the
  QR scanner can only be checked on a phone (or with an emulator window and a virtual scene).
- **Opening a link on the emulator** — `adb shell am start -a android.intent.action.VIEW -d "'familyhub://login?server=…&code=…'"`
  (the inner quotes keep `&` from the device shell).
