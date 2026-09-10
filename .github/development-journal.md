# Development Journal

## Software Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material3), Compose BOM 2026.02.01
- **Min SDK:** 34 (Android 14)
- **Target/Compile SDK:** 36
- **Location:** platform `LocationManager` + `GPS_PROVIDER` (no Play Services / FusedLocationProvider)
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

**UI is three tabs (Record / Runs / Export), not one long scroll.**
The first cut put every control and the runs list in a single `LazyColumn`; live stats then reflowed the list on start/stop and in landscape pushed it off-screen, and the whole thing scrolled as one blob so a swipe on a run row scrolled the controls instead of the list. Splitting into a **Record** tab (start/stop + live stats), a **Runs** tab (the list) and an **Export** tab gives each surface its own scroll and fixes both. Tabs are plain `PrimaryTabRow` + a `rememberSaveable` index — no `navigation-compose`, honouring the "no navigation component" rule. A recording dot on the Record tab label keeps the logging state visible from the other tabs. The Export tab is `enabled` only when the selection is non-empty and its label carries the count (`Export (N)`); because the selection is ephemeral while `tab` is `rememberSaveable`, a `LaunchedEffect` falls back to the Runs tab if the tab is restored to Export with an empty selection (e.g. after process death).

**Row actions: explicit checkbox select, two-step swipe-to-reveal delete.**
Selection was a hidden swipe-right with no affordance; it is now a leading `Checkbox` (discoverable, multi-select). Delete no longer auto-dismisses on swipe (too easy to lose a run) — swiping the card left uncovers a Delete button that must be tapped. The reveal uses a plain `draggable` + `Animatable` offset rather than `AnchoredDraggable`, because those APIs are stable across Compose releases and this project cannot be built locally to catch breakage. The active run cannot be revealed, so it cannot be deleted while logging.

**Export is its own tab with a live filtered-result preview, tied to the selection.**
The always-visible filter fields, the bottom sheet and the separate Save/Share buttons are gone. Selecting runs enables an **Export** tab that shows a summary of the selection (run count + raw point total from `RunInfo.pointCount`, free), the accuracy/distance/time filters, a live **Result** line (tracks = selected runs, remaining points and the percentage reduction) and a single **Export** action. The Save-file (SAF `CreateDocument`) path was dropped because sharing the GPX to a file manager already writes it to disk — one Share action suffices. The result preview is a `StateFlow<ExportPreview>` on the ViewModel built with `combine(selected, filters, runs)` → `transformLatest` (`@ExperimentalCoroutinesApi`), so a filter change mid-read cancels the stale computation; each recompute reads every selected run via `RunReader.readAll` and runs `PointFilter` on `Dispatchers.IO`, emitting `Computing` then `Ready`. It re-reads files on each change (no retained point cache) — KISS over memory-heavy caching, acceptable because reads are fast and cancellation-friendly. The Export button is disabled until the preview is `Ready`.

**Dynamic (Material You) colour via a `GpsLogTheme` wrapper.**
The app previously used a bare `MaterialTheme {}` with no `colorScheme`, i.e. the baseline purple and no dark mode. `ui/Theme.kt` selects `dynamicLightColorScheme`/`dynamicDarkColorScheme` from `isSystemInDarkTheme()`; at minSdk 34 both are unconditionally available, so no fallback palette and no version guard.

**In-app nightly updater talks to the GitHub releases API, no token, no library.**
The Record tab has a "Check for updates" control. `UpdateChecker` does a plain `HttpURLConnection` GET of `releases/tags/dev` (the rolling pre-release) and parses it with the built-in `org.json` — release assets on a public repo download anonymously, so no token is embedded, and a `User-Agent` header is set because GitHub 403s requests without one. The asset is `gpslog-dev-<sha>.apk` and the installed `versionName` is `dev-<sha>`, so "new" is decided by comparing the short SHA. Note this can only detect *different*, not *newer* — dev SHAs are unordered and no build timestamp is stored. The APK is streamed to `cacheDir/downloads/` and handed to the system installer via `ACTION_VIEW` + `FileProvider`. This needs `REQUEST_INSTALL_PACKAGES` (banned on Play, fine for GitHub distribution) and `INTERNET` (the app's first use of the network — location logging never needed it); the first install routes the user through `ACTION_MANAGE_UNKNOWN_APP_SOURCES`. Crucially, an in-place update only succeeds when the nightly is signed with the same key as the installed build (the CI keystore) — a differently-signed or local build must be uninstalled first.

## Core Features

- **GPS logging foreground service** — records raw fixes at the chipset rate, survives Doze, resumes after process kill or reboot.
- **Three-tab screen** — a Record tab (start/stop + live stats), a Runs tab (the past-runs list with checkbox multi-select and two-step swipe-to-delete) and an Export tab enabled by the selection.
- **GPX export** — selected runs shared via the system share sheet from the Export tab, which carries the persisted accuracy/distance/time filters and a live preview of the filtered result (tracks, remaining points, percentage reduction).
- **In-app updater** — checks the GitHub `dev` pre-release and installs a newer signed nightly APK via the system package installer.
