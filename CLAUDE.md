# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**gpsLog** — an Android GPS logging service (package `de.codevoid.gpslog`). A
foreground service records fixes from either the internal chipset or an external
Bluetooth NMEA receiver into per-run binary files; one Compose screen with
three destinations (Record, Runs, Settings) and an Export page records,
manages runs, exports GPX and holds settings. It runs on the owner's devices;
there is no emulator and no instrumentation test setup.

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
`NmeaParser` — the three Android-free modules with the highest on-paper risk —
plus the UI's pure helpers `ui/Format.kt` (`Formats`, `formatDuration`,
`reductionPercent`, the filter parsers) and `ui/RecordStatus.kt`. Keep them and
anything else you want tested free of Android classes; there is no emulator and
no instrumentation test setup.

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
- **Internal GNSS is optional; a device without it is supported.** A Wi-Fi-only tablet
  has no `GPS_PROVIDER` at all, and `requestLocationUpdates` throws
  `IllegalArgumentException` for a provider that does not exist — on the `gps-logger`
  thread, which took the process with it. `LocationManager.hasProvider` is the presence
  question, asked in `LoggingService.startSource` (refuse) and once in
  `MainViewModel.hasInternalGps` (a `val`: only a mock-location app can add or remove a
  provider, and there is no presence broadcast — the service's own re-check is the
  guarantee, not the `val`). Such a device records from an external Bluetooth receiver
  like any other. `uses-feature android.hardware.location.gps` is therefore
  `required="false"`.
- **Precise location is mandatory.** A coarse-only app may still request
  `GPS_PROVIDER` without throwing, but fixes are fuzzed and throttled to one per
  10 minutes and `GnssStatus` never fires. `MainActivity` checks
  `ACCESS_FINE_LOCATION` on resume and shows a banner; `startLogging` refuses to
  start without it, so the failure is named instead of silent.
- **No Room, no DataStore, no DI framework, no XML layouts, no navigation
  component, no XML/JSON/HTTP library.** Single `MainActivity`, one Compose
  screen behind a `NavigationBar`/`NavigationRail`, an `Application`-scoped singleton (`App`)
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
  thread's `Looper`/`Executor`. Needs `ACCESS_FINE_LOCATION` and a `GPS_PROVIDER` that
  exists (`hasProvider`); the check and the request are separate round trips to the
  system server, so the `IllegalArgumentException` is caught as well — no pre-check can
  close that on its own. `isProviderEnabled` answers false for a missing provider by
  contract ("true if the provider exists and is enabled"), so `internalGpsEnabled()` is
  that one call; `hasProvider` is what tells *absent* from *switched off*, which are
  different words to the user. `registerGnssStatusCallback` returns true always — it is
  not an availability probe, and with no GNSS engine the server side is a no-op.
- *External*: `service/BluetoothNmeaSource` opens an RFCOMM socket on the SPP
  UUID and reads it on its **own** `gps-bt-reader` thread — a blocking socket
  read must never sit on `gps-logger` and starve the flush and notification
  callbacks — then `handler.post`s each parsed fix back. Reconnects on a dropped
  link with exponential backoff (3 s→60 s, reset on connect), reporting the
  receiver off in between; `ACTION_ACL_CONNECTED` on the paired address cuts the
  wait short when the device is really back, as a fast path only — not every
  receiver re-initiates a link, so the backoff is the mechanism. Satellite-status
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
| `ACTION_PAUSE` / `ACTION_UNPAUSE` | Release / re-acquire the fix source (see **Pause is cold** below); also sent by the notification actions |
| `ACTION_STOP` | Flush, clear the active-run marker, `stopForeground` + `stopSelf` |

**A platform refusal is never a crash.** `startForeground` with the `location`
type throws when the app holds neither `ACCESS_COARSE_LOCATION` nor
`ACCESS_FINE_LOCATION`, and a *background* start — `BootReceiver`, a sticky
restart — additionally needs `ACCESS_BACKGROUND_LOCATION`, because that policy
is while-in-use only. `Service.startForeground` swallows only `RemoteException`,
so a refusal reached `onStartCommand` on the main thread and killed the process
— at every boot, since only Stop clears the marker. It is caught; the marker is
cleared only for a run that never began, so an interrupted one stays resumable.

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

**Pause is cold.** `setPaused` releases the fix source through `stopSource()`,
flushes the writer and cancels the flush timer, so the chipset (or the Bluetooth
link) powers down and a paused run costs no battery; Resume re-acquires it. The
flag is persisted next to the active run id, so a reboot or a sticky restart
comes back paused with nothing acquired — do not reintroduce a hardcoded
`paused = false` in `startLogging`. If the source cannot be re-acquired on
Resume the run stays paused rather than being discarded. Because the live stats
go quiet while paused, the notification carries the Pause/Resume action.

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
  debug-logging toggle, each as a `StateFlow`, plus the active-run marker and its
  paused flag (plain getter/setter — the UI reads paused from `LoggingState`).
- The run list (and `latestDebugLog`, the newest captured NMEA log) is rebuilt
  from disk on `onResume`, after delete/merge, and reactively whenever
  `loggingState.runId` flips. Selection is ephemeral ViewModel state.
- `MainViewModel.activeRun` reduces `LoggingState` to `ActiveRun(id, paused)?`
  for the shell and the Runs list, so point ticks recompose only the Record
  surface. `exportTarget` is the set of runs the Export page is open for
  (empty = closed), kept apart from `selected`; `exportPreview` and `export()`
  read it. It is deliberately not saved across process death.
- The notification offers Pause/Resume and, always, Stop (`ACTION_STOP`).
- `MainActivity` owns everything that needs an Activity: the up-front permission
  chain (location → background location → battery-optimization exemption), the
  `uiVisible` flag, and the `FileProvider`-backed share and install intents over
  `cacheDir/{exports,downloads,debug}` (`res/xml/file_paths.xml`).

### UI

One screen, three always-reachable destinations — Record, Runs, Settings —
behind a Material 3 `NavigationBar` (a `rememberSaveable` index and a `when`
— no `navigation-compose`), or a `NavigationRail` on the start side once the
window is 600 dp or wider (`WideWindowMinWidth`, read from
`LocalWindowInfo.current.containerDpSize` — no window-size-class library).
**Export is a page, not a destination**: it is composed in the content slot
while `MainViewModel.exportTarget` (the set of run ids it is open for) is
non-empty, opened by `openExport(ids)` from the latest-run row on Record, a
tap on a Runs row, or the Runs tray, and closed by `BackHandler`, its own
back arrow or any navigation item (`closeExport()` before switching). There
is no app bar: surfaces open with a `SurfaceHeader` (optional leading icon
slot for Back/Close). The shell (`ui/GpsLogScreen.kt`) collects only
`activeRun` and `exportTarget`; each surface lives in its own file and
collects its own flows, so a 2 s `LoggingState` tick recomposes
`RecordSurface` alone. The shell passes `wide` to Record and Export, which
lay out in two columns capped at `WideContentMaxWidth` (840 dp).

- **Record** (`RecordSurface.kt`) — a status word from `recordStatus()` with a
  subtitle from `recordSubtitle()`; the recorded span is `lastFix − start`, so
  it advances with fixes and freezes while paused (no ticking clock). 56 dp
  text-only controls: Stop on the left, outlined with `error` content like
  Delete on the Runs tray, Pause/Resume on the right, one filled button at a
  time. The row is capped at `WideControlsMaxWidth` in a wide window. While logging, six
  tabular-numeral tiles in one `Panel`; while idle, the same slot holds
  `LatestRunRow` (`runs.firstOrNull()`), whose tap opens its Export page. In a
  wide window a *recording* run fills the height (`BoxWithConstraints`, capped
  at `WideContentMaxHeight`): both columns take that height as a minimum, the
  status block centres in its column and the tile rows sit `TileGap` apart,
  centred. Spreading them evenly put more space between the rows than they
  occupied, so do not go back to `SpaceEvenly`. The minimum goes on the
  `Column` inside `Panel`, never on the panel — `Surface` is not guaranteed to
  pass a minimum constraint to its content.
  Recording is independent of the export filters: nothing on Record reads
  them. `recordStatus` maps a false `gpsEnabled` to *Receiver off* for an
  external source (the service initialises it internal-only and drops it on a
  Bluetooth disconnect) and to *GPS disabled* for the internal one, and
  answers the idle case too, ranked outermost obstacle first: *No GPS device*
  (no chipset), *GPS disabled* (Location switched off), *Unavailable* (no
  precise location). All three are internal-source-only and idle-only — a run
  already going reports what it is doing. Start is enabled exactly when the
  status is *Ready*, so the word and the button cannot disagree; for the same
  reason the precise-location banner stays down while the status is *No GPS
  device*. `internalGpsEnabled` is a `StateFlow` re-read in `onResume`, like
  `preciseLocation`: the Location switch moves outside the app, and one
  boolean does not earn a `PROVIDERS_CHANGED_ACTION` receiver.
- **Runs** (`RunsSurface.kt`) — stock two-line `ListItem` rows with ONE
  `combinedClickable` in both modes: tap opens the run's Export page, long-press
  toggles selection; selection mode is derived (`selected.isNotEmpty()`), shows
  the leading `Checkbox`, a `"<n> selected"` header with a Close icon, a
  `BackHandler` that clears the selection, and an `ActionTray` with Delete
  (confirmed by an `AlertDialog` — the one irreversible action), Merge (no
  dialog; keeps the merged run selected) and Export. The active run may be
  exported but is excluded from Delete/Merge. Never swap the row modifier
  between modes — the swap would happen under a finger that is still down.
  Rows are separated by a `HorizontalDivider` and carry a trailing Share glyph
  whenever a tap would export: the container is transparent, so without both
  the row has neither a visible bound nor an affordance. The active run's tag
  is `overlineContent`, not trailing, where a wide window stranded it. The
  list's `LazyListState` is hoisted to the shell so the Export page pushed
  over it does not reset the scroll position.
- **Export** (`ExportSurface.kt`) — the page for `exportTarget`: a single run
  reads by its title, several as a count; `FilterRow`s (meaning left, 120 dp
  field with a `suffix` unit right; the pure parsers commit valid input
  immediately, invalid input flags the field and snaps back on focus loss or
  Done; Time stays enabled with a helper while a distance is set); the result
  panel dims the last `Ready` value while `Computing`; Share GPX is never
  disabled (`export()` filters the points itself). The root has
  `imePadding()`; the shell's `consumeWindowInsets(padding)` makes that land
  on the keyboard rather than a bar's height above it.
- **Settings** (`SettingsSurface.kt`) — `ListItem` rows under Recording /
  Diagnostics / About: one row naming the current GPS device, opening an
  `AlertDialog` picker (internal plus every paired classic device, scrollable,
  lazy `BLUETOOTH_CONNECT` from an "Allow" row inside it). The list is
  unbounded, so it must not sit inline. On a device with no chipset the row
  and the picker's internal option both read "No GPS device found" (one
  `private const val`, so they cannot drift apart) and that option is
  disabled rather than hidden — which makes the picker's note the only way
  forward, so it is never silence: `PickerNote` carries either *Allow* (the
  permission) or *Pair* (the system Bluetooth settings, since this app does
  no pairing). The bonded set and the permission are re-read when the picker
  opens, not once per composition — this surface survives the trip to system
  settings, so a device paired there would otherwise never appear — and
  *Pair* closes the dialog so the next open re-reads. The debug-NMEA switch,
  "Share latest log" (disabled with a reason while `latestDebugLog` is null),
  the version, and one updater row whose slots follow `UpdateState`.

`ui/Components.kt` holds the shared pieces: `SurfaceHeader`, `SectionHeader`,
`Panel` (`surfaceContainerLow`), `ActionTray` (`surfaceContainer`),
`RecordingDot`, `ValueWithUnit`, `ControlLabel`, `RevealEnter`/`RevealExit`,
`ContentMaxWidth` (600 dp), `WideWindowMinWidth`, `WideContentMaxWidth`,
`WideContentMaxHeight`, `TileGap`, `WideControlsMaxWidth`,
`Gutter`, `ControlHeight`. The recording marker is `error`; paused is the
neutral `outline` (words: `onSurfaceVariant`), never dynamic `tertiary`,
which lands on red for some wallpapers. `ui/Format.kt` and
`ui/RecordStatus.kt` are pure Kotlin under JUnit — keep them free of Android
imports; leaf composables take Strings and Booleans, never a `Formats`.

Type stays within about a factor of two across a screen: `headlineLarge` for
the status word, `headlineSmall` for live values, `bodyLarge` for row titles,
`labelLarge` for the smallest labels. Do not reach for `displaySmall` or
`labelMedium` in content again, and keep the rail's glyph and label a step
above the bar's, since it is read from further away.

`ui/Theme.kt` applies the dynamic (Material You) light/dark schemes with tabular
figures (`tnum`) on the display/headline/title roles only; at minSdk 34 the
schemes are always available, so there is no fallback palette.
`res/values-night/themes.xml` makes the first frame dark. The UI carries no
experimental Material opt-in — do not reintroduce `MaterialExpressiveTheme`
(spring motion is wrong for a still recording screen, and its motion-scheme
factories are internal in material3 1.4.0), `ModalBottomSheet` (experimental,
and three IME fields plus a preview do not fit a half sheet) or
`material-icons-extended` (the core set covers every icon; Stop and Pause are
text buttons by design). `material-icons-core` is an explicit, BOM-managed
dependency — material3 does not put it on the compile classpath. Motion
budget: two `AnimatedVisibility` transitions (precise-location banner, selection
tray) plus component-internal animation — no perpetual animation, no
destination transitions, no elapsed-time ticker.

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

`MainViewModel.exportPreview` is `combine(exportTarget, filters, runs)` →
`transformLatest`, so a filter change mid-read cancels the stale computation. It
re-reads every targeted run from disk on each change rather than caching points —
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
