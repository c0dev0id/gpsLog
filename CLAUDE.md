# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**gpsLog** — an Android GPS logging service.

**Status:** Boilerplate only. The template scaffold is complete (package
`de.codevoid.gpslog`); the app builds and shows a placeholder Compose
screen.

## Build & CI

Builds normally run in CI/CD.

| CI task | Trigger |
|---|---|
| Lint + debug APK | Push to `main`, PR labeled `run-build`, or `workflow_dispatch` |
| Signed release APK + GitHub draft release | Manual `workflow_dispatch` |

Signing secrets required: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

The release workflow derives `versionName`/`versionCode` from the release tag
and passes them to Gradle as `-PversionName` / `-PversionCode`. Release signing
is configured from the `SIGNING_*` environment variables; when they are absent
the release build stays unsigned.

## Architecture

- **Single Activity** (`MainActivity`) — no fragments, no navigation component.
- **Jetpack Compose** UI only — no XML layouts.
- **minSdk 26** (Android 8.0) — no need for pre-Oreo compatibility paths.
- The Compose BOM only manages the `androidx.compose.*` groups. Any other
  AndroidX dependency (e.g. `activity-compose`) needs an explicit version.

## Rules

- Always commit every logical step. Do not batch unrelated changes into one commit.
- Always rebase the working branch onto `main` at the end of a task.
- Maintain `CHANGELOG.md` (keep-a-changelog format) after each task.
- Maintain `.github/development-journal.md` with stack info, key decisions, and core features.

## Git

Configure before any git operation:
- `user.name = c0dev0id`
- `user.email = sh+git@codevoid.de`

No `Co-Authored-By` or other attribution lines in commits or PRs. Remove any lines containing "claude" from commit/PR messages. If `.gh_token` is present, use it for GitHub API access.
