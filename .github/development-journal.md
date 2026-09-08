# Development Journal

## Software Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material3), Compose BOM 2026.02.01
- **Min SDK:** 26 (Android 8.0)
- **Target/Compile SDK:** 36
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

**Lint and the debug build share one CI job.**
Running lint and assembleDebug in one job avoids duplicated Gradle distribution downloads and cache concurrency issues.

## Core Features

None implemented yet. Planned:

- GPS Logging Service
