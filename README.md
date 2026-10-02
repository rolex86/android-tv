# Android TV

Android TV applications and utilities maintained by `rolex86`.

## JustPlayer Plus

`just-player-plus/` is a separately installable fork of
[Just (Video) Player](https://github.com/moneytoo/Player), focused on
improved language, subtitle and external-player behavior for Android TV
and Stremio.

The Android application ID is `com.rolex86.justplayerplus`, so it can be
installed alongside the original `com.brouken.player` application.

The optional AI subtitle translation client is disabled by default and
expects a separately deployed translation backend. The backend is
intentionally not part of this repository.

### Build

GitHub Actions builds the release APK after relevant changes. Locally:

```bash
cd just-player-plus
./gradlew assembleLatestUniversalRelease
```

## Nuvio RS Plus

`nuvio-rs-plus/` is a separately installable customization of
[NuvioTV Reshaped](https://github.com/DavidVamaiotu/NuvioTV-Reshaped), kept alongside
JustPlayer Plus in this repository.

The project is intended to preserve Nuvio Reshaped's player, AutoSync, buffering and
tracking features while adding the small set of JustPlayer Plus behaviors that are
still missing:

- optional AI subtitle translation using the existing self-hosted translator backend
- commentary and audio-description avoidance
- original-audio / dubbed-audio preference
- best-quality audio ranking within the preferred language
- embedded / addon subtitle source preference

Upstream synchronization is automated and stops instead of publishing when a merge or
build cannot be completed safely. See `NUVIO_RS_PLUS_PLAN.md`.

