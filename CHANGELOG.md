# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- In-app updater on the Record tab: checks the published nightly on GitHub and, when it differs from the installed build, downloads the signed APK and hands it to the system installer (an in-place update requires the installed build to be signed with the same key)
- Merge combines several selected runs into a single run: their points are read, merged in time order and written to one file, and the originals are removed

### Changed
- Reorganised the UI into three tabs — **Record** (start/stop and live stats), **Runs** (the list) and **Export** — so each surface owns its own scroll and neither pushes the other off-screen, including in landscape
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
