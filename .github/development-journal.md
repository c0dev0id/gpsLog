# Development Journal

## Software Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material3), Compose BOM 2026.02.01
- **Min SDK:** 34 (Android 14)
- **Target/Compile SDK:** 36
- **Location:** platform `LocationManager` + `GPS_PROVIDER` (no Play Services / FusedLocationProvider); optional external classic-Bluetooth GNSS receiver over SPP/NMEA
- **Persistence:** per-run append-only fixed-size binary files + `SharedPreferences` (no Room, no DataStore)
- **State sharing:** `Application`-scoped singleton + `StateFlow` (no DI framework)
- **Build:** CI-only via GitHub Actions (Gradle 9.4.0, AGP 9.1.0, Kotlin Compose plugin 2.3.10, JDK 17)

## Key Decisions

**AGP 9's built-in Kotlin support is used; no separate `kotlin-android` plugin.**
AGP 9.1 registers the `compileDebugKotlin` task and the `kotlin { }` extension on its own, so only `com.android.application` and the Compose compiler plugin are applied.

**Non-Compose AndroidX dependencies carry explicit versions.**
`androidx.compose:compose-bom` only constrains the `androidx.compose.*` groups. `androidx.activity:activity-compose` is pinned explicitly.

**Release signing comes from environment variables, not a properties file.**
`SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` are read in `app/build.gradle.kts`. The keystore never enters the repository, and when the variables are absent the signing config is simply not created, so debug builds and local release builds still work.

**Version metadata is a Gradle property, sourced from the release tag.**
The release workflow resolves the tag, derives `versionCode` as `major * 10000 + minor * 100 + patch`, and passes both via `-P` flags.

**Every push to `main` publishes a signed `dev` pre-release.**
`build.yml` runs lint and unit tests, then builds a *signed release* APK (`versionName = dev-<short-sha>`) and publishes it as a single rolling `dev` GitHub pre-release, deleting and recreating it each run so there is always exactly one current dev artifact. Signing is real (env-var driven), so the pre-release depends on the `SIGNING_*` secrets being present. Modelled on `c0dev0id/androsnd`; the `andro-template` workflow it was originally scaffolded from is outdated. `release.yml` (manual `workflow_dispatch` → tagged draft release) is kept separate and is not the day-to-day path.

**minSdk raised to 34 to delete all compatibility code.**
At API 34 the runtime `POST_NOTIFICATIONS` permission, `FOREGROUND_SERVICE_LOCATION`, the 3-arg `startForeground`, and the `LocationManager` `Executor`/`Looper` overloads are all unconditionally available, so no `androidx.core` compat layer or version guards are needed. Android 13 and older are unsupported by design.

**`LocationManager` + `GPS_PROVIDER`, not FusedLocationProvider.**
FusedLocationProvider is a Play Services dependency, caps at roughly 1 Hz and smooths fixes. Raw chipset-rate logging (device-dependent, up to ~10–20 Hz) requires `requestLocationUpdates(minTime=0, minDistance=0)` on the platform provider. Fixes are delivered on a dedicated `HandlerThread`, never the main Looper.

**Launcher icon is vector-only, no PNG density buckets.**
`mipmap-anydpi/ic_launcher.xml` is an adaptive icon whose background (gradient), foreground and monochrome layers are all `VectorDrawable`s under `res/drawable/`. With minSdk 34 every device supports adaptive icons, so no legacy `mipmap-*dpi` PNGs exist and the folder carries no `-v26` qualifier. The notification small icon `ic_stat_logging` is the same path data cropped to the 66dp safe zone. The SVG source of truth is the path data itself; regenerate previews by rendering the drawables' `pathData` in an SVG.

**Precise (fine) location is mandatory and checked explicitly.**
Since Android 12 a coarse-only app may still request `GPS_PROVIDER`; nothing throws, but fixes are fuzzed and throttled to one per 10 minutes and `GnssStatus` callbacks register yet never fire. That presents as a run with no points and "Receiver off". The Activity checks `ACCESS_FINE_LOCATION` on resume and surfaces a banner, and `LoggingService.startLogging` refuses to start without it, so the failure is named instead of silent.

**Fix status comes from `GnssStatus`, not from fix age.**
A `GnssStatus.Callback` is registered next to the location updates (same handler thread, ~1 Hz from the engine). "Has fix" is defined as at least one satellite flagged `usedInFix`; this is the platform's own notion, updates even while no `Location` arrives, and needs no timer to expire a stale fix. Provider on/off is taken from `LocationListener.onProviderEnabled/Disabled`. The per-fix state update copies the existing `LoggingState` so these fields survive.

**One append-only fixed-size binary file per run, not a database.**
Each run is `filesDir/runs/<startMillis>.dat`: a 16-byte header plus 68-byte records with a validity bitmask (absent optional fields written as NaN sentinels). Point count is `(size − header) / recordSize` in O(1); a torn trailing partial record is ignored for crash safety. No end marker — a graceful stop and a crash are indistinguishable, and interrupted runs are just runs. This honours the pre-1.0 "no schema/migration code" rule.

**In-memory buffer flushed and `fd.sync()`ed every 10 s.**
Bounds data loss on an unexpected kill to at most the last flush interval while keeping per-fix write cost low. A `PARTIAL_WAKE_LOCK` is held for the run so Doze/CPU-suspend cannot defer the flush timer.

**Auto-resume via persisted active-run flag + START_STICKY + BOOT_COMPLETED.**
The active run id is written to `SharedPreferences` on start and cleared on stop. A system-driven restart (`START_STICKY`, null intent) and a reboot (`BootReceiver`) both re-open the same file in append mode and keep logging until the user taps Stop. A battery-optimization exemption is requested so the OS leaves the service alone; both the exemption and receiving `BOOT_COMPLETED` are background-FGS-start exemptions (OEM aggressiveness still varies). A user-initiated Force-Stop is a permanent OS stop and is not resumed, by design.

**Export filters: accuracy first, then distance/time with "distance beats time".**
Accuracy always drops missing/worse fixes. When distance > 0 the decision is governed entirely by distance (minimum spacing from the last kept point); time only governs cadence when distance is 0. Both off keeps all accuracy-passing points. `PointFilter` and `RunCodec` are pure Kotlin so they are unit-tested on the JVM in CI — they carry the highest on-paper risk.

**GPX written with the built-in `android.util.Xml` serializer, streamed to the output.**
No XML library. One `<trk>` per run; a new `<trkseg>` is started across gaps larger than 60 s (e.g. a reboot pause) so viewers don't draw a straight line over the gap.

**UI is four tabs (Record / Runs / Export / Settings), not one long scroll.**
The first cut put every control and the runs list in a single `LazyColumn`; live stats then reflowed the list on start/stop and in landscape pushed it off-screen, and the whole thing scrolled as one blob so a swipe on a run row scrolled the controls instead of the list. Splitting into a **Record** tab (start/stop + live stats), a **Runs** tab (the list), an **Export** tab and a **Settings** tab (the in-app updater, and the home for any future preferences) gives each surface its own scroll and fixes both. Tabs are plain `PrimaryTabRow` + a `rememberSaveable` index — no `navigation-compose`, honouring the "no navigation component" rule. A recording dot on the Record tab label keeps the logging state visible from the other tabs. The Export tab is `enabled` only when the selection is non-empty and its label carries the count (`Export (N)`) and is greyed (0.38 alpha) otherwise; because the selection is ephemeral while `tab` is `rememberSaveable`, a `LaunchedEffect` falls back to the Runs tab if the tab is restored to Export with an empty selection (e.g. after process death). The live-stats panel stays mounted on the Record tab even when idle (every value reads `—` until Start), so the layout doesn't jump when logging begins.

**Row actions: checkbox multiselect; Delete and Merge act on the whole selection.**
Selection is a leading `Checkbox` and the whole card is clickable to toggle it. The earlier swipe-to-reveal delete was dropped entirely: having multiselect but only being able to action one row at a time (per-row swipe) was inconsistent, so Delete — and the new Merge — are buttons below the list that operate on the multiselection. The active run may be selected for export but is excluded from Delete/Merge (its file is being appended); the buttons' counts and enabled state reflect the selection minus the active run (Delete needs ≥1, Merge ≥2). This removed the `draggable`/`Animatable` swipe machinery.

**Merge rewrites the selected runs into one file via the normal codec.**
`RunRepository.merge` reads every selected run with `RunReader.readAll`, concatenates the points, sorts them by time, and writes a single run with `RunWriter` (same header/record codec as live logging). It writes to a `.dat.tmp` first, then deletes the originals and renames the temp over the earliest run's id, so a crash mid-merge leaves the originals intact. The merged run's id is `min(ids)` (the earliest start). Honours the pre-1.0 "no schema/migration" rule — it's just points in, points out.

**Export is its own tab with a live filtered-result preview, tied to the selection.**
The always-visible filter fields, the bottom sheet and the separate Save/Share buttons are gone. Selecting runs enables an **Export** tab that shows a summary of the selection (run count + raw point total from `RunInfo.pointCount`, free), the accuracy/distance/time filters, a live **Result** line (tracks = selected runs, remaining points and the percentage reduction) and a single **Export** action. The Save-file (SAF `CreateDocument`) path was dropped because sharing the GPX to a file manager already writes it to disk — one Share action suffices. The result preview is a `StateFlow<ExportPreview>` on the ViewModel built with `combine(selected, filters, runs)` → `transformLatest` (`@ExperimentalCoroutinesApi`), so a filter change mid-read cancels the stale computation; each recompute reads every selected run via `RunReader.readAll` and runs `PointFilter` on `Dispatchers.IO`, emitting `Computing` then `Ready`. It re-reads files on each change (no retained point cache) — KISS over memory-heavy caching, acceptable because reads are fast and cancellation-friendly. The Export button is disabled until the preview is `Ready`.

**Dynamic (Material You) colour via a `GpsLogTheme` wrapper.**
The app previously used a bare `MaterialTheme {}` with no `colorScheme`, i.e. the baseline purple and no dark mode. `ui/Theme.kt` selects `dynamicLightColorScheme`/`dynamicDarkColorScheme` from `isSystemInDarkTheme()`; at minSdk 34 both are unconditionally available, so no fallback palette and no version guard.

**In-app nightly updater talks to the GitHub releases API, no token, no library.**
The Settings tab has a "Check for updates" control. `UpdateChecker` does a plain `HttpURLConnection` GET of `releases/tags/dev` (the rolling pre-release) and parses it with the built-in `org.json` — release assets on a public repo download anonymously, so no token is embedded, and a `User-Agent` header is set because GitHub 403s requests without one. The asset is `gpslog-dev-<sha>.apk` and the installed `versionName` is `dev-<sha>`, so "new" is decided by comparing the short SHA. Note this can only detect *different*, not *newer* — dev SHAs are unordered and no build timestamp is stored. The APK is streamed to `cacheDir/downloads/` and handed to the system installer via `ACTION_VIEW` + `FileProvider`. This needs `REQUEST_INSTALL_PACKAGES` (banned on Play, fine for GitHub distribution) and `INTERNET` (the app's first use of the network — location logging never needed it); the first install routes the user through `ACTION_MANAGE_UNKNOWN_APP_SOURCES`. Crucially, an in-place update only succeeds when the nightly is signed with the same key as the installed build (the CI keystore) — a differently-signed or local build must be uninstalled first.

**Recording source is pluggable: internal GPS or an external classic-Bluetooth NMEA receiver.**
The motivation is comparison — recording an external receiver while the phone's own GPS stays free for navigation. A `FixSink` interface (`onFix`/`onSatelliteStatus`/`onSourceEnabled`) is the seam both sources drive; `LoggingService` implements it and `startLogging` branches on `SettingsStore.recordingSource` (`""` = internal, otherwise a paired MAC). The internal path is unchanged. The external path is `BluetoothNmeaSource`: an RFCOMM socket on the SPP UUID `00001101-0000-1000-8000-00805F9B34FB`, read on its **own** thread (a blocking socket read must not sit on the `gps-logger` HandlerThread and starve the flush/notification callbacks), with each parsed fix `handler.post`ed back onto the logger thread so the writer and its counters stay single-threaded. It auto-reconnects on drop and reports the receiver off in the meantime. Classic Bluetooth (SPP), not BLE — it's a plain ASCII byte stream, simpler than GATT.

**Bluetooth devices are not filtered for "is this a GNSS device"; the user picks.**
Bluetooth Class-of-Device has no GNSS class and SPP receivers report as uncategorized, so there is no reliable programmatic signal. The Settings picker lists every paired classic/dual device by name and trusts the user's choice. `BLUETOOTH_CONNECT` is requested lazily from the picker (via a Compose `rememberLauncherForActivityResult`), never in the up-front location chain, so an internal-only user is never prompted. No `BLUETOOTH_SCAN` — only bonded devices are read, never discovery.

**NMEA parsing is `GGA` + `RMC` only; `GSA`/`GSV` are optional enrichment.**
`data/NmeaParser` is pure Kotlin (unit-tested in CI like `PointFilter`/`RunCodec`), talker-agnostic (accepts `GP`/`GN`/`GL`/… by matching the 3-char sentence type). RMC carries the only date, so it is the emit trigger; a `GpsRecord` is produced per valid RMC (status `A`), enriched with the most recent GGA (MSL altitude, HDOP, fix quality, satellites-in-use). NMEA has no meters-accuracy field and `PointFilter` drops points with missing accuracy, so accuracy is derived as `HDOP × 5 m` (nominal UERE) — otherwise every external point would be filtered out on export. GSV, if present, sums satellites-in-view across per-constellation talkers purely for the live stats. Reference device: a GNS 3000 (MediaTek MT3333, SBAS-capable — its DGPS/SBAS fixes report GGA quality 2, which the parser accepts as any quality > 0).

**Raw-NMEA capture is a passive tee to a shareable file, not a device query.**
Over SPP a NMEA receiver only *streams*; it cannot be "queried" for capabilities like a REST API (that would need vendor `PMTK` commands written back to the device — deliberately out of scope for now). But the stream itself reveals the config: active constellations from the GSV talker IDs, delivery rate from line timestamps, HDOP and fix quality from GGA. So a Settings toggle (`SettingsStore.debugLogging`) makes `BluetoothNmeaSource` tee each raw line to `cacheDir/debug/nmea-<runId>.log` via `NmeaDebugLog`, timestamped `HH:mm:ss.SSS`. All file IO stays on the reader thread (opened at the top of `runLoop`, closed in its `finally`), so the single-threaded writer contract is untouched. The file is shared through the existing `FileProvider` (`text/plain`), and `MainViewModel.latestDebugLog()` picks the newest file in the debug dir. Motivation: diagnose why an external receiver (GNS 3000 / MT3333) reports fewer satellites and lower accuracy than the tablet's internal chipset — likely fewer enabled constellations and single-frequency silicon, with the HDOP×5 accuracy heuristic possibly tripping the 10 m export filter.

The internal GPS gets the same treatment so the two sources can be compared on equal footing. The internal chipset emits no NMEA of its own accessible over `LocationManager`, but the platform exposes an NMEA stream via `addNmeaListener(Executor, OnNmeaMessageListener)` — registered on the same `handlerExecutor` that marshals the `GnssStatus` callback, so `NmeaDebugLog.line()` stays on the `gps-logger` thread. Before streaming, the log also records `getGnssHardwareModelName()`, `getGnssYearOfHardware()` and `getGnssCapabilities()` — a static probe of what the silicon *can* do (measurements, multi-constellation, dual-frequency) versus what it currently emits. This is the cheap data-gathering step behind the user's "direct internal option" idea: the 1 Hz cap has no app-level knob (`requestLocationUpdates` already runs `minTime=0`), so before building anything we want to see whether the capabilities suggest any headroom at all.

## Core Features

- **GPS logging foreground service** — records raw fixes at the chipset rate, survives Doze, resumes after process kill or reboot.
- **Selectable recording source** — the internal GPS or a paired external classic-Bluetooth GNSS receiver (SPP/NMEA), chosen on the Settings tab.
- **Four-tab screen** — a Record tab (start/stop + live stats), a Runs tab (the past-runs list with checkbox multi-select plus Delete/Merge), an Export tab enabled by the selection, and a Settings tab (GPS-device picker + the in-app updater).
- **GPX export** — selected runs shared via the system share sheet from the Export tab, which carries the persisted accuracy/distance/time filters and a live preview of the filtered result (tracks, remaining points, percentage reduction).
- **In-app updater** — checks the GitHub `dev` pre-release and installs a newer signed nightly APK via the system package installer.
- **Debug NMEA log** — an optional Settings toggle tees the raw NMEA from a run to a timestamped text file, shareable from Settings, for diagnosing the receiver's constellations, rate and HDOP. For the internal GPS it also captures the chipset model, hardware year and GNSS capabilities (via `addNmeaListener` and the `getGnss*` probes).
