# gpsLog

GPS logging service for Android. A foreground service records raw GPS fixes at
the device's native rate; a single Compose screen starts/stops logging, shows
live stats, lists past runs, and exports selected runs as GPX.

Requires Android 14 (API 34) or newer.

## Status

Core app implemented: the foreground logging service, per-run binary storage,
the Compose UI, and GPX export. Not yet exercised on a real device.

## Build

Builds run in CI; AGP is not available for local builds.

| Workflow | Trigger | Outcome |
|---|---|---|
| Build | Push to `main` | Lint, unit tests, and a signed release APK published as the rolling `dev` pre-release |
| Release | Manual dispatch | Signed release APK, a version tag, and a draft GitHub release |

The signed APK depends on the `SIGNING_*` repository secrets being set.
