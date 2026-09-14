# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**gpsLog** — an Android GPS logging service (package `de.codevoid.gpslog`). A
foreground service records fixes from either the internal chipset or an external
Bluetooth NMEA receiver into per-run binary files; a four-tab Compose screen
records, manages runs, exports GPX and holds settings. It has not yet been
exercised on a real device.

The user is a minimalist: prefer the platform API over a library, and a
structural fix over a local workaround. Keep code KISS and testable.

`.github/development-journal.md` carries the *rationale* behind every decision
summarised here — read it before revisiting a design choice, and update it when
a decision or stack fact changes.

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

Unit tests (`app/src/test`, JUnit 4) cover `RunCodec`, `PointFilter` and
`NmeaParser` — the three Android-free modules with the highest on-paper risk.
Keep them and anything else you want tested free of Android classes; there is no
emulator and no instrumentation test setup.

| Workflow | Trigger | Outcome |
|---|---|---|
| `build.yml` | Push to `main` | Lint, unit tests, signed release APK published as the rolling `dev` pre-release (deleted and recreated each run, `versionName = dev-<short-sha>`, asset `gpslog-dev-<sha>.apk`) |
| `release.yml` | Manual `workflow_dispatch` | Signed release APK, a `vX.Y.Z` tag and a GitHub **draft** release; `versionCode = major*10000 + minor*100 + patch` |

Release builds run R8 (`isMinifyEnabled`, `isShrinkResources`) with only the
default optimized ProGuard file — there is no project `proguard-rules.pro`. Any
reflection-based code would need one.

Release signing is configured from the `SIGNING_*` environment variables in
`app/build.gradle.kts` (`SIGNING_KEYSTORE_PATH`, `_KEYSTORE_PASSWORD`,
`_KEY_ALIAS`, `_KEY_PASSWORD`; CI decodes the keystore from
`SIGNING_KEYSTORE_BASE64`). When absent the signing config is not created and
release builds stay unsigned, so the `dev` pre-release genuinely depends on the
secrets. `versionName`/`versionCode` are Gradle properties with defaults
`0.0.1`/`1`. The in-app updater can only install a nightly signed with the same
key as the installed build.

## Architecture

### Platform constraints (deliberate, do not relax)

- **minSdk 34** (Android 14), compile/target 36. Chosen so that runtime
  permissions, `FOREGROUND_SERVICE_LOCATION`, the 3-arg `startForeground`, the
  `LocationManager` `Executor`/`Looper` overloads and the dynamic-colour schemes
  are unconditionally available — **no compatibility code and no
  `Build.VERSION` guards**. Do not reintroduce `androidx.core` compat shims. The
  one `androidx.core` class in use is `FileProvider` (transitive via
  `activity-compose`) for the share/install intents; it is a feature, not a shim.
- **Platform `LocationManager` + `GPS_PROVIDER`**, not FusedLocationProvider
  (Play Services dependency, ~1 Hz cap, smoothing). `requestLocationUpdates`
  runs with `minTime=0, minDistance=0` to get the chipset's native rate.
- **Precise location is mandatory.** A coarse-only app may still request
  `GPS_PROVIDER` without throwing, but fixes are fuzzed and throttled to one per
  10 minutes and `GnssStatus` never fires. `MainActivity` checks
  `ACCESS_FINE_LOCATION` on resume and shows a banner; `startLogging` refuses to
  start without it, so the failure is named instead of silent.
- **No Room, no DataStore, no DI framework, no XML layouts, no navigation
  component, no XML/JSON/HTTP library.** Single `MainActivity`, one Compose
  screen with `PrimaryTabRow` tabs, an `Application`-scoped singleton (`App`)
  for wiring, `android.util.Xml` for GPX and `HttpURLConnection` + `org.json`
  for the updater.
- **Pre-1.0: no schema or migration code.** Readers tolerate a torn trailing
  record; a format change means bumping `RunCodec.VERSION` and accepting that
  old files fail `readHeader` — there is no upgrade path by design.
- **AGP 9.1 built-in Kotlin**: only `com.android.application` and the Compose
  compiler plugin are applied. Do not add `kotlin-android`; AGP registers the
  Kotlin tasks and the `kotlin { }` extension itself.
- The Compose BOM only manages `androidx.compose.*`. Any other AndroidX
  dependency (`activity-compose`, `lifecycle-*`) needs an explicit version.

### Recording sources: the `FixSink` seam

`service/FixSink` (`onFix` / `onSatelliteStatus` / `onSourceEnabled`) is the
interface both sources drive and `LoggingService` implements. `startLogging`
branches on `SettingsStore.recordingSource`: `""` = internal GPS, anything else
is a paired Bluetooth MAC. **Every callback must arrive on the `gps-logger`
HandlerThread** — that is what keeps the writer and its counters single-threaded.

- *Internal*: `LocationListener` + `GnssStatus.Callback` registered on that
  thread's `Looper`/`Executor`. Needs `ACCESS_FINE_LOCATION`.
- *External*: `service/BluetoothNmeaSource` opens an RFCOMM socket on the SPP
  UUID and reads it on its **own** `gps-bt-reader` thread — a blocking socket
  read must never sit on `gps-logger` and starve the flush and notification
  callbacks — then `handler.post`s each parsed fix back. Auto-reconnects after
  3 s on a dropped link and reports the receiver off in between; satellite-status
  posts are time-gated to 2 s. Needs `BLUETOOTH_CONNECT`, requested lazily from
  the Settings picker so an internal-only user is never prompted. Only bonded
  devices are read — no discovery, no `BLUETOOTH_SCAN`, and no filtering for
  "is this a GNSS device" (there is no reliable signal; the user picks).

`data/NmeaParser` is pure Kotlin and talker-agnostic (dispatches on the 3-char
sentence type). `RMC` is the emit trigger — it carries the only date — enriched
from the most recent `GGA`; `GSV` is optional enrichment for the satellites-in-view
count. NMEA has no metres-accuracy field, so accuracy is derived as `HDOP × 5 m`;
without it `PointFilter` would drop every external point at export.

"Has fix" is `satellitesUsedInFix > 0` — the platform's own notion, which updates
even while no `Location` arrives and needs no staleness timer.

### Runtime: service lifecycle and threading

`service/LoggingService` is a foreground service of type `location`,
`START_STICKY`, driven by five intents from its companion:

| Intent | Effect |
|---|---|
| `ACTION_START` | New run: `id = System.currentTimeMillis()`, persisted as the active run in `SettingsStore` |
| `ACTION_RESUME` / null intent (sticky restart) | Re-open the persisted active run in append mode; if none, `stopSelf()` |
| `ACTION_PAUSE` / `ACTION_UNPAUSE` | Keep the source connected and satellite status flowing, but stop appending points |
| `ACTION_STOP` | Flush, clear the active-run marker, `stopForeground` + `stopSelf` |

`service/BootReceiver` sends `ACTION_RESUME` after `BOOT_COMPLETED` if an
active run is persisted. Only the user's Stop clears the marker; a process
kill or reboot therefore continues the same file until Stop. A user
Force-Stop is not resumed.

Threading contract: all run state (`writer`, `runId`, `pointCount`, `paused`,
rate window) lives on the dedicated `gps-logger` `HandlerThread`; fixes are
delivered there (never on the main Looper). Per fix: append to the `RunWriter`
in-memory buffer, then update `LoggingStateHolder` and the notification at most
every 2 s each. UI updates are skipped **entirely** while
`LoggingStateHolder.uiVisible` is false (flipped by `MainActivity.onResume/onPause`),
so background recording costs no allocations and no recompositions; the
notification update is unconditional. A 60 s `flushRunnable` writes the buffer
to the page cache.

**No wake lock and no `fdatasync`** — both were deliberately removed. The
foreground-service `location` type plus the GPS hardware keep the subsystem
running, and pinning the CPU for a multi-hour run only burned heat. A process
kill loses nothing (the page cache survives); only a power-loss crash loses up
to one flush interval. Do not reintroduce either as a "fix" for a missed write.

### Storage

One append-only file per run at `filesDir/runs/<startMillis>.dat`. The file
name **is** the run id **is** the start time. `data/RunCodec` (pure Kotlin,
`java.nio`, big-endian) defines the layout: a 16-byte header (magic `GPSL`,
version, record size) and fixed 68-byte records with a flags bitmask marking
which optional fields are present (absent ones are written as NaN, read back
as `null`). Fixed size gives O(1) point count and random access from the file
length. There is no end marker, so a graceful stop and a crash produce
identical files.

`data/RunFile` holds `RunWriter` (buffer + flush; the header is the one thing
fsync'd, once, at creation) and `RunReader` (`pointCount`/`firstRecord`/`lastRecord`
seek without validating the header; `readAll` validates it and loads everything).
`data/RunRepository` is plain filesystem listing/deletion plus `merge`, which
reads the selected runs, concatenates and time-sorts the points and rewrites them
through the normal `RunWriter` codec path into a `.dat.tmp`, then deletes the
originals and renames the temp over `min(ids)` — so a crash mid-merge leaves the
originals intact.

### State and data flow

```
LocationManager ─┐
                 ├─▶ FixSink (LoggingService) ─▶ RunWriter ─▶ <id>.dat
BluetoothNmea ───┘            │
                              ▼ (throttled, skipped in background)
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
- `SettingsStore` owns the three export filters, the recording source and the
  debug-logging toggle, each as a `StateFlow`, plus the active-run marker.
- The run list is rebuilt from disk on `onResume`, after delete/merge, and
  reactively whenever `loggingState.runId` flips. Selection is ephemeral
  ViewModel state.
- `MainActivity` owns everything that needs an Activity: the up-front permission
  chain (location → background location → battery-optimization exemption), the
  `uiVisible` flag, and the `FileProvider`-backed share and install intents over
  `cacheDir/{exports,downloads,debug}` (`res/xml/file_paths.xml`).

### UI

One screen, four tabs (`PrimaryTabRow` + a `rememberSaveable` index — no
`navigation-compose`), so each surface owns its own scroll:

- **Record** — start/stop/pause, the precise-location banner, live stats (the
  panel stays mounted when idle, showing `—`, so the layout does not jump). The
  tab label carries a recording dot, amber while paused.
- **Runs** — the list, with a leading checkbox for multiselect; Delete and Merge
  are buttons below it acting on the whole selection. The active run may be
  selected for export but is excluded from Delete/Merge.
- **Export** — enabled only when the selection is non-empty (label shows the
  count). A `LaunchedEffect` falls back to Runs if the saved tab index restores
  to Export with an empty selection.
- **Settings** — recording-source picker, the updater, the debug-NMEA toggle
  and share button.

`ui/Theme.kt` applies the dynamic (Material You) light/dark schemes; at minSdk
34 they are always available, so there is no fallback palette.

### Export

`export/PointFilter` (pure Kotlin, unit-tested) applies accuracy first, then
distance **or** time: while distance > 0 it alone decides (minimum spacing from
the last kept point); time only governs cadence when distance is 0; both off
keeps every accuracy-passing point. `export/GpxExporter` streams GPX 1.1 with
the built-in `android.util.Xml` serializer — one `<trk>` per run, a new
`<trkseg>` whenever consecutive points are more than 60 s apart (so a pause or
reboot gap is not drawn as a straight line). The ViewModel runs the export on
`Dispatchers.IO` and closes the stream; delivery is the system share sheet only
(the SAF save path was removed — sharing to a file manager already writes to
disk).

`MainViewModel.exportPreview` is `combine(selected, filters, runs)` →
`transformLatest`, so a filter change mid-read cancels the stale computation. It
re-reads every selected run from disk on each change rather than caching points —
KISS over memory, and cheap because reads are fast and cancellable.

### Updater

`update/UpdateChecker` GETs the public `releases/tags/dev` endpoint with
`HttpURLConnection` (a `User-Agent` is required or GitHub 403s) and parses it
with `org.json` — no token, no library. "New" is a *different* short SHA than the
installed `dev-<sha>`; dev SHAs are unordered, so it cannot detect *newer*. The
APK streams to `cacheDir/downloads` and goes to the system installer via
`ACTION_VIEW` + `FileProvider`, which needs `REQUEST_INSTALL_PACKAGES` (banned on
Play, fine for GitHub distribution) and `INTERNET` — the app's only network use.

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
