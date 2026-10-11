# gpsLog

An Android GPS logger for people who want the raw track and nothing else: a
foreground service records fixes at the receiver's native rate and exports them
as GPX. Built for the owner's own devices, Android 14 (API 34) or newer.

## Features

- **Native-rate recording** — logs `GPS_PROVIDER` fixes with no smoothing or rate cap, every field the fix carries.
- **External receivers** — records from a paired Bluetooth NMEA receiver, leaving the phone's own GPS free.
- **Cold pause** — pausing releases the receiver entirely, so a paused run draws no battery; Resume re-acquires it.
- **Survives reboots** — an interrupted run resumes after a process kill or reboot and keeps recording until you stop it.
- **GPX export** — filter by accuracy, distance or time, preview the result, share the GPX.
- **Merge** — combine several runs into one, points sorted by time.
- **In-app updater** — checks the nightly build on GitHub and installs it.

## Screens

One screen, three destinations, plus an export page opened on demand.

| Screen | Purpose |
|---|---|
| Record | Start, pause and stop; live GPS state and stats |
| Runs | List past runs, select, delete, merge, export |
| Settings | Pick the GPS device, debug log, updater |
| Export | Filter and share a run as GPX (opened from Record or Runs) |

## Permissions

| Permission | Why |
|---|---|
| Precise location | Mandatory — coarse fixes are fuzzed and throttled |
| Background location | Keeps recording when the app is backgrounded |
| Bluetooth connect | Only when you pick an external receiver |
| Install packages | For the in-app updater (GitHub distribution) |

## Getting started

1. Install the latest APK from the [`dev` pre-release](../../releases/tag/dev).
2. Grant precise location and allow the battery-optimization exemption on launch.
3. On Record, press Start. For an external receiver, pick it under Settings first.
4. Stop the run, then tap it to open its Export page and share the GPX.

## Build

Builds run in CI; AGP is not available for local builds.

| Workflow | Trigger | Outcome |
|---|---|---|
| Build | Push to `main` | Lint, unit tests, signed APK published as the rolling `dev` pre-release |
| Release | Manual dispatch | Signed APK, a version tag and a draft GitHub release |

The signed APK depends on the `SIGNING_*` repository secrets being set.
