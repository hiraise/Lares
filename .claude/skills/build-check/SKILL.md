---
name: build-check
description: Format, compile, lint and test FamilyHub with short output (tools/dev/verify.sh). Use whenever you need to build or run tests in this repo instead of calling ./gradlew directly.
---

# Build and test

`tools/dev/verify.sh` runs `spotlessApply` first, then the given Gradle tasks, and prints only compile errors, lint
violations, failed tests, per-module test totals and BUILD OK / BUILD FAILED. The full log is in
`build/dev/verify.log`; read it (with `grep`) only when the summary is not enough.

| Need | Command |
| --- | --- |
| Full gate (as CI, before handoff) | `tools/dev/verify.sh` (run in background: 3–6 min) |
| Compile only | `tools/dev/verify.sh --no-format :client:shared:compileKotlinJvm` |
| One module | `tools/dev/verify.sh :server:test`, `:client:shared:jvmTest`, `:integration-tests:test`, `:contract:jvmTest` |
| One test class | `tools/dev/verify.sh :client:shared:jvmTest --tests '*FamilyViewModelTest*'` |
| Force rerun of up-to-date tests | add `--rerun` |
| Debug APK | `tools/dev/verify.sh --no-format :client:android:assembleDebug` |

Rules:

- Do not run `spotlessApply` separately; the script does it.
- While iterating, run the narrowest task that covers the change. Run the full gate once at the end.
- Server and integration tests need Docker running (Testcontainers).
- "no test results written" means the tasks were up to date; add `--rerun` if you need fresh results.
