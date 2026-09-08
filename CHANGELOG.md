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

### Changed
- Raised minimum supported version to Android 14 (`minSdk` 26 → 34)

## [0.0.1] - 2026-09-08

### Added
- Scaffold Android app (`de.codevoid.gpslog`) with Jetpack Compose based on `andro-template`
- Build and release CI/CD GitHub Actions workflows
- Release signing configuration driven by `SIGNING_*` environment variables
- `versionName` / `versionCode` overridable from command line, derived from release tag by CI
- Project documentation: `CLAUDE.md`, `CHANGELOG.md`, `development-journal.md`, `.gitignore`, `README.md`
