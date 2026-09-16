# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Stop as a notification action beside Pause/Resume, so a run can be ended from the shade without opening the app
- Delete asks for confirmation before removing runs; Merge, which loses nothing, does not
- "Share latest log" on Settings shows when the log was captured and is disabled with a note while no log exists
- Close (or Back) in the Runs header drops a multi-row selection at once
- Pause/Resume, on the Record tab and as a notification action: pausing releases the GPS receiver (or the Bluetooth link) outright, so a paused run draws no battery rather than nearly as much as a recording one, and it stays paused across a reboot or a process kill. Resuming re-acquires the receiver, which is quick because the GPS ephemeris is still cached. The timestamp gap produces a new track segment on export automatically. The Record item's dot turns grey while paused (red while actively recording), the live stats read "Paused", and the notification shows the point count.

### Fixed
- A connecting or reconnecting Bluetooth receiver is reported as "Receiver off" on the Record screen instead of "GPS disabled"
- Sharing the debug log while none existed silently did nothing; the row is now disabled until a log has been captured
- Notification no longer shows stale point/rate data while a Bluetooth receiver is disconnected; it now immediately updates to "Receiver disconnected — reconnecting…"
- A run paused before a reboot or a process kill no longer comes back recording — it comes back paused, with the receiver still released

### Changed
- Redesigned the screen around a bottom navigation bar with three destinations, Record, Runs and Settings; the Record item carries a recording dot (red while recording, grey while paused); the app bar title is gone and every surface starts with its own header
- Export is no longer a destination that waits for a selection: it is a page opened for the runs you choose — tap the latest run on Record, tap any run on Runs, or select several and press Export — and left with Back or its arrow. Sharing the run you just recorded is Stop, tap the latest run, Share GPX
- Record shows the latest run in place of the idle tiles, one tap from its Export page; the live tiles appear while recording
- Runs follows the platform list pattern: tap a run to export it, long-press to select it; selection mode shows checkboxes, a count with Close, and a tray with Delete, Merge and Export, and Back leaves it. Checkboxes are no longer shown on every row all the time
- Merge keeps the merged run selected, so it can be exported right away
- Share GPX is always enabled; the result panel still reports an empty result
- Record leads with the GPS state as a headline word (Ready, Searching, Fix, Paused, Receiver off, GPS disabled, Unavailable) with the start time and the recorded span underneath, and shows the live values as a grid of tiles with tabular numerals so they no longer jitter; Stop and Pause/Resume are large side-by-side buttons
- Runs are two-line list rows with the date and time, duration and point count, tinted when selected, with a "Recording"/"Paused" tag on the active run; the header shows a summary of all runs or the selection count
- Export filters are rows that say what they do, with the unit inside the field; an invalid entry is flagged with the valid range and snaps back to the value in effect when the field loses focus; the Time filter says it is ignored while a distance is set instead of being disabled; the result keeps the last figures dimmed while recomputing instead of showing "Calculating…", and names the fix when every point is filtered out; the Export button stays above the keyboard
- The GPS device is chosen from an inline list on Settings instead of a dropdown field, and settings are grouped under Recording, Diagnostics and About
- Windows 600 dp and wider — a phone in landscape or a tablet — get a navigation rail on the side instead of the bottom bar, and Record shows its status and controls beside the tiles while Export shows the filters beside the result, so everything fits without scrolling
- Run rows are separated by a divider and carry a share icon, so it is visible where a row begins and that tapping it exports the run; the "Recording" tag moved to the top of the active run's row, next to the run it describes
- The GPS device is now one row naming the current source, which opens a picker when tapped, instead of every paired Bluetooth device being listed at the top of Settings
- Text sizes across the app are closer together, the navigation rail's icons and labels are larger, and Stop is outlined rather than a solid red block, so no single element dominates a screen
- While recording in such a window Record now uses the whole height: the live values spread over a full-height panel in a larger numeral and the status block is centred beside them, instead of everything sitting in the top third with the rest of the screen empty
- Dark mode no longer flashes a light frame at launch
- Recording now holds no wake lock: the foreground-service location type + GPS hardware keeps the relevant subsystems running without pinning the CPU, reducing heat significantly during long runs
- Write durability relaxed: fdatasync removed from the periodic flush (OS page cache is sufficient; only a power-loss crash risks data loss, which is acceptable) and the flush interval extended from 10 s to 60 s
- All status updates (UI state and notification) throttled to 2 s; UI state updates are skipped entirely when the app is in background — no allocations or Compose recompositions during background recording
- Bluetooth satellite status posts throttled to 2 s regardless of how frequently GSV sentences arrive (was effectively ~12/s at 4 Hz with 3 constellation talkers)
- A disconnected Bluetooth receiver is retried with a backoff from 3 s up to 60 s instead of every 3 s indefinitely, so a receiver switched off or left behind no longer wakes the radio continuously for the rest of the run. Reconnection is still immediate in practice: the app is notified the moment the receiver comes back in range

### Added
- GPS logging foreground service recording raw `GPS_PROVIDER` fixes at the chipset's native rate, logging every field the fix offers (position, time, altitude, accuracy, speed, bearing and their accuracies)
- Single Compose screen: start/stop button reflecting service state, live stats (start time, points, update rate, GPS time, speed, accuracy), and a list of past runs
- Runs list with directional swipe — swipe left to delete, swipe right to select; the active run is shown but cannot be deleted until logging stops
- GPX 1.1 export of the selected runs (one track per run, split into segments across large time gaps), delivered either as a saved file (SAF) or through the system share sheet
- Persisted export filters: accuracy (default 10 m), distance (default off) and time (default off), applied accuracy-first then distance/time with distance taking precedence
- Automatic resume of an interrupted run after a process kill or device reboot; logging continues until the user stops it
- Requests all permissions and a battery-optimization exemption on launch so the service is not deferred or killed by the OS
- Adaptive launcher icon (with a monochrome layer for themed icons) and a matching notification icon
- Precise location is required: without it the screen shows a banner with a shortcut to the app's settings, Start is disabled, and the service refuses to record a run that could not produce usable fixes
- Live stats show the GPS state (disabled / receiver off / searching / fix) and the number of satellites used in the fix and visible, so a run recording no points explains why; the notification shows the satellite count while waiting for a fix
- In-app updater on the Settings tab: checks the published nightly on GitHub and, when it differs from the installed build, downloads the signed APK and hands it to the system installer (an in-place update requires the installed build to be signed with the same key)
- Merge combines several selected runs into a single run: their points are read, merged in time order and written to one file, and the originals are removed
- Debug NMEA log: a Settings switch that, while recording, tees the raw NMEA stream (timestamped, plus connect/disconnect notes) to a text file, with a button to share the latest capture — for diagnosing the receiver's constellations, delivery rate and HDOP. For the internal GPS it also records the chipset model, hardware year and GNSS capabilities, so the internal receiver's real rate and constellation support can be inspected
- Recording source can be an external classic-Bluetooth GNSS receiver: the Settings tab has a "GPS device" picker listing the internal GPS plus paired Bluetooth devices, so the phone's own GPS stays free for navigation while a run records from the external receiver. Fixes are read as NMEA (GGA + RMC) over the serial-port profile; accuracy is derived from HDOP so the points survive the export accuracy filter

### Changed
- Reorganised the UI into four tabs — **Record** (start/stop and live stats), **Runs** (the list), **Export** and **Settings** (the in-app updater) — so each surface owns its own scroll and neither pushes the other off-screen, including in landscape
- Selecting runs is an explicit checkbox (tapping anywhere on the row toggles it); Delete and the new Merge act on the whole multiselection via buttons below the list, replacing the per-row swipe-to-delete — the active run can be selected for export but not deleted or merged
- The recording details panel stays visible before a run is started, showing dashes for every value until the first fix, so the layout no longer jumps when logging begins
- The Export tab is greyed out while no run is selected, so it reads as unavailable
- Export is a dedicated tab, enabled only when runs are selected (its label shows the count), carrying the accuracy/distance/time filters, a summary of the selection and a live preview of the filtered result (tracks, remaining points and the percentage reduction) above a single Export action; saving to disk is done by sharing the GPX to a file manager, so the separate Save-file button is gone
- Applied the system's dynamic (Material You) colours and dark theme
- Raised minimum supported version to Android 14 (`minSdk` 26 → 34)

## [0.0.1] - 2026-09-08

### Added
- Scaffold Android app (`de.codevoid.gpslog`) with Jetpack Compose based on `andro-template`
- Build and release CI/CD GitHub Actions workflows
- Release signing configuration driven by `SIGNING_*` environment variables
- `versionName` / `versionCode` overridable from command line, derived from release tag by CI
- Project documentation: `CLAUDE.md`, `CHANGELOG.md`, `development-journal.md`, `.gitignore`, `README.md`
