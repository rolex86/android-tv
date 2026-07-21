# Android TV

Android TV applications and utilities maintained by `rolex86`.

## JustPlayer Plus

`just-player-plus/` is a separately installable fork of
[Just (Video) Player](https://github.com/moneytoo/Player), focused on
improved language, subtitle and external-player behavior for Android TV
and Stremio.

The Android application ID is `com.rolex86.justplayerplus`, so it can be
installed alongside the original `com.brouken.player` application.

### Build

GitHub Actions builds the release APK after relevant changes. Locally:

```bash
cd just-player-plus
./gradlew assembleLatestUniversalRelease
```

## AI subtitle backend

`ai-subtitle-backend/` is the optional self-hosted Gemini translation service used by the
disabled-by-default AI subtitle feature. It requires Node.js 20+ and keeps the provider key only
on the server. See its README for local and container deployment.
