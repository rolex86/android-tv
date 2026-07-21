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
