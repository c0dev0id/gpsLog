# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**gpsLog** — an Android GPS logging service (package `de.codevoid.gpslog`).
Core app is implemented: a foreground logging service, per-run binary
storage, a single Compose screen (start/stop, live stats, runs list) and GPX
export. It has not yet been exercised on a real device.

The user is a minimalist: prefer the platform API over a library, and a
structural fix over a local workaround. Keep code KISS and testable.

## Build & CI

Builds run in CI/CD only. Do **not** attempt a local Android build (not even
`./gradlew test`) — AGP is unreachable behind a firewall and every Gradle task
in this project needs it. There is no workaround; do not look for one.

Verification path instead: push, then read the GitHub Actions result for the
commit (via the GitHub tooling available to the session; a `.gh_token` file,
when present, holds a token for this). The `test` job uploads
`app/build/reports/tests/` as the `test-report` artifact on failure.

Gradle tasks the workflows run (reference only):

| Task | Command |
|---|---|
| Lint | `./gradlew lint` |
| All unit tests | `./gradlew test` |
| One test class | `./gradlew :app:testDebugUnitTest --tests "de.codevoid.gpslog.PointFilterTest"` |
| One test method | `./gradlew :app:testDebugUnitTest --tests "de.codevoid.gpslog.PointFilterTest.accuracyDropsMissingAndWorse"` |
| Signed release APK | `./gradlew assembleRelease -PversionName=… [-PversionCode=…]` |

Unit tests (`app/src/test`, JUnit 4) cover `RunCodec` and `PointFilter` — the
two Android-free modules with the highest on-paper risk. Keep them and anything
else you want tested free of Android classes; there is no emulator and no
instrumentation test setup.

| Workflow | Trigger | Outcome |
|---|---|---|
| `build.yml` | Push to `main` | Lint, unit tests, signed release APK published as the rolling `dev` pre-release (deleted and recreated each run, `versionName = dev-<short-sha>`) |
| `release.yml` | Manual `workflow_dispatch` | Signed release APK, a `vX.Y.Z` tag and a GitHub **draft** release; `versionCode = major*10000 + minor*100 + patch` |

Release signing is configured from the `SIGNING_*` environment variables in
`app/build.gradle.kts` (`SIGNING_KEYSTORE_PATH`, `_KEYSTORE_PASSWORD`,
`_KEY_ALIAS`, `_KEY_PASSWORD`; CI decodes the keystore from
`SIGNING_KEYSTORE_BASE64`). When absent the signing config is not created and
release builds stay unsigned, so the `dev` pre-release genuinely depends on the
secrets. `versionName`/`versionCode` are Gradle properties with defaults
`0.0.1`/`1`.

## Architecture

### Platform constraints (deliberate, do not relax)

- **minSdk 34** (Android 14), compile/target 36. Chosen so that runtime
  permissions, `FOREGROUND_SERVICE_LOCATION`, the 3-arg `startForeground` and
  the `LocationManager` `Executor`/`Looper` overloads are unconditionally
  available — **no compatibility code and no `Build.VERSION` guards**. Do not
  reintroduce `androidx.core` compat shims. The one `androidx.core` class in
  use is `FileProvider` (transitive via `activity-compose`) for the share
  export; it is a feature, not a shim.
- **Platform `LocationManager` + `GPS_PROVIDER`**, not FusedLocationProvider
  (Play Services dependency, ~1 Hz cap, smoothing). `requestLocationUpdates`
  runs with `minTime=0, minDistance=0` to get the chipset's native rate.
- **No Room, no DataStore, no DI framework, no XML layouts, no navigation
  component.** Single `MainActivity`, one Compose screen, an
  `Application`-scoped singleton (`App`) for wiring.
- **Pre-1.0: no schema or migration code.** Readers tolerate a torn trailing
  record; a format change means bumping `RunCodec.VERSION` and accepting that
  old files fail `readHeader` — there is no upgrade path by design.
- **AGP 9.1 built-in Kotlin**: only `com.android.application` and the Compose
  compiler plugin are applied. Do not add `kotlin-android`; AGP registers the
  Kotlin tasks and the `kotlin { }` extension itself.
- The Compose BOM only manages `androidx.compose.*`. Any other AndroidX
  dependency (`activity-compose`, `lifecycle-*`) needs an explicit version.

### Runtime: service lifecycle and threading

`service/LoggingService` is a foreground service of type `location`,
`START_STICKY`, driven by three intents from its companion:

| Intent | Effect |
|---|---|
| `ACTION_START` | New run: `id = System.currentTimeMillis()`, persisted as the active run in `SettingsStore` |
| `ACTION_RESUME` / null intent (sticky restart) | Re-open the persisted active run in append mode; if none, `stopSelf()` |
| `ACTION_STOP` | Flush, clear the active-run marker, `stopForeground` + `stopSelf` |

`service/BootReceiver` sends `ACTION_RESUME` after `BOOT_COMPLETED` if an
active run is persisted. Only the user's Stop clears the marker; a process
kill or reboot therefore continues the same file until Stop. A user
Force-Stop is not resumed.

Threading contract: all run state (`writer`, `runId`, `pointCount`, rate
window) lives on the dedicated `gps-logger` `HandlerThread`; fixes are
delivered there (never on the main Looper). Per fix: append to the
`RunWriter` in-memory buffer, update `LoggingStateHolder` at most every
250 ms, update the notification at most every 1 s. A 10 s `flushRunnable`
writes and `fd.sync()`s the buffer, so a crash loses at most 10 s. A
`PARTIAL_WAKE_LOCK` is held for the run so Doze cannot defer the flush.

### Storage

One append-only file per run at `filesDir/runs/<startMillis>.dat`. The file
name **is** the run id **is** the start time. `data/RunCodec` (pure Kotlin,
`java.nio`, big-endian) defines the layout: a 16-byte header (magic `GPSL`,
version, record size) and fixed 68-byte records with a flags bitmask marking
which optional fields are present (absent ones are written as NaN, read back
as `null`). Fixed size gives O(1) point count and random access from the file
length. There is no end marker, so a graceful stop and a crash produce
identical files.

`data/RunFile` holds `RunWriter` (buffer + flush) and `RunReader`
(`pointCount`/`firstRecord`/`lastRecord` seek without validating the header;
`readAll` validates it and loads everything — used once per run at export).
`data/RunRepository` is plain filesystem listing/deletion returning `RunInfo`.

### State and data flow

```
LocationManager ─▶ LoggingService ─▶ RunWriter ─▶ <id>.dat
                        │
                        ▼ (throttled)
               LoggingStateHolder (StateFlow, process singleton)
                        │
                        ▼
                 ui/MainViewModel ─▶ ui/GpsLogScreen
```

- The service **writes** `LoggingStateHolder`; the ViewModel only collects.
  Commands go the other way as fire-and-forget intents (`vm.start()` →
  `LoggingService.start`). No Binder, no bound service.
- `LoggingState` does not survive process death. "Is a run active" is
  authoritative in `SettingsStore` (`SharedPreferences`), not in the holder.
- `SettingsStore` also owns the three export filters as a `StateFlow`
  (accuracy default 10 m, distance and time default off).
- The run list is rebuilt from disk on `onResume` and after delete; selection
  is ephemeral ViewModel state.
- `MainActivity` owns everything that needs an Activity: the up-front
  permission chain (location → background location → battery-optimization
  exemption), the SAF `CreateDocument` launcher, and the share sheet backed by
  `FileProvider` over `cacheDir/exports` (`res/xml/file_paths.xml`).

### Export

`export/PointFilter` (pure Kotlin, unit-tested) applies accuracy first, then
distance **or** time: while distance > 0 it alone decides (minimum spacing from
the last kept point); time only governs cadence when distance is 0; both off
keeps every accuracy-passing point. `export/GpxExporter` streams GPX 1.1 with
the built-in `android.util.Xml` serializer — one `<trk>` per run, a new
`<trkseg>` whenever consecutive points are more than 60 s apart. The
ViewModel runs the export on `Dispatchers.IO` and closes the stream.

## Rules

- Commit every logical step; never batch unrelated changes.
- Rebase the working branch onto `main` at the end of a task.
- Update `CHANGELOG.md` (keep-a-changelog) after each task with user-facing
  changes only; update `.github/development-journal.md` when a decision or
  stack fact changes.
- Always use the latest available library versions. Before hand-rolling a
  feature, check whether the platform or a dependency already provides it and
  present that option.

## Git

Configure before any git operation:
- `user.name = c0dev0id`
- `user.email = sh+git@codevoid.de`

No `Co-Authored-By` or other attribution lines in commits or PRs. Remove any
lines containing "claude" from commit/PR messages. If `.gh_token` is present,
use it for GitHub API access.
