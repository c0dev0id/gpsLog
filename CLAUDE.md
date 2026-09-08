# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**gpsLog** — an Android GPS logging service.

**Status:** Core app implemented (package `de.codevoid.gpslog`): a foreground
logging service, per-run binary storage, a single Compose screen (start/stop,
live stats, runs list), and GPX export. Not yet exercised on a device.

## Build & CI

Builds run in CI/CD only. Do **not** attempt a local Android build — AGP is
unreachable behind a firewall, so there is no point trying to work around it.

Gradle tasks the workflows invoke (for reference, not for local use):

| Task | Command |
|---|---|
| Lint | `./gradlew lint` |
| Debug APK | `./gradlew assembleDebug` |
| Signed release APK | `./gradlew assembleRelease -PversionName=… -PversionCode=…` |

Pure-JVM unit tests (`./gradlew test`) cover `RunCodec` and `PointFilter` —
the two Android-free modules that carry the highest on-paper risk. Keep them
Android-free so CI can verify them without an emulator. There are no
instrumentation tests.

| CI task | Trigger |
|---|---|
| Lint + debug APK | Push to `main`, PR labeled `run-build`, or `workflow_dispatch` |
| Signed release APK + GitHub draft release | Manual `workflow_dispatch` |

Signing secrets required: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

The release workflow derives `versionName`/`versionCode` from the release tag
and passes them to Gradle as `-PversionName` / `-PversionCode`. Release signing
is configured from the `SIGNING_*` environment variables; when they are absent
the release build stays unsigned.

## Architecture

- **Single Activity** (`MainActivity`) — no fragments, no navigation component.
  It requests all permissions and the battery-optimization exemption up front
  and hosts the one Compose screen (`ui/GpsLogScreen`).
- **Jetpack Compose** UI only — no XML layouts.
- **minSdk 34** (Android 14). Chosen so that all runtime permissions, the
  `FOREGROUND_SERVICE_LOCATION` type, the 3-arg `startForeground`, and the
  `LocationManager` `Executor`/`Looper` overloads are unconditionally
  available — **no compatibility code or version guards**. Do not reintroduce
  `androidx.core` compat shims. Android 13 and older are unsupported by design.
- **Logging** is a foreground service (`service/LoggingService`, type
  `location`, `START_STICKY`) using the platform `LocationManager` +
  `GPS_PROVIDER` on a dedicated `HandlerThread` — **not** FusedLocationProvider
  (Play Services dep, ~1 Hz cap, smoothing). It holds a `PARTIAL_WAKE_LOCK` for
  the run and resumes an interrupted run on process restart or reboot
  (`service/BootReceiver` + a persisted active-run flag in `SettingsStore`).
- **Storage** is one append-only fixed-size binary file per run under
  `filesDir/runs/<startMillis>.dat` (`data/RunCodec`, `data/RunFile`,
  `data/RunRepository`) — no Room, no DataStore. Per the pre-1.0 rule there is
  **no schema or migration code**; readers tolerate a torn trailing record.
- **State** is shared through an `Application`-scoped singleton
  (`App` + `service/LoggingStateHolder` `StateFlow`) — no DI framework. The
  service writes; `ui/MainViewModel` collects.
- **Settings** (three export filters + active-run marker) live in
  `SharedPreferences` via `data/SettingsStore`.
- **GPX** is written with the built-in `android.util.Xml` serializer
  (`export/GpxExporter`), streamed to the output; filtering is pure Kotlin in
  `export/PointFilter`.
- The Compose BOM only manages the `androidx.compose.*` groups. Any other
  AndroidX dependency (e.g. `activity-compose`, `lifecycle-*`) needs an
  explicit version.
- **AGP 9.1's built-in Kotlin support is used** — only `com.android.application`
  and the Compose compiler plugin are applied. Do not add the `kotlin-android`
  plugin; AGP registers the Kotlin tasks and `kotlin { }` extension itself.
- Release signing is driven by `SIGNING_*` environment variables in
  `app/build.gradle.kts`. When they are absent the signing config is not
  created, so debug builds still work and release builds stay unsigned.

## Rules

- Always commit every logical step. Do not batch unrelated changes into one commit.
- Always rebase the working branch onto `main` at the end of a task.
- Maintain `CHANGELOG.md` (keep-a-changelog format) after each task.
- Maintain `.github/development-journal.md` with stack info, key decisions, and core features.

## Git

Configure before any git operation:
- `user.name = c0dev0id`
- `user.email = sh+git@codevoid.de`

No `Co-Authored-By` or other attribution lines in commits or PRs. Remove any lines containing "claude" from commit/PR messages. If `.gh_token` is present, use it for GitHub API access.
