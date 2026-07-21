# JustPlayer Plus

JustPlayer Plus is a private Android and Android TV fork focused on smarter audio and subtitle selection while preserving the proven playback pipeline of the upstream Just Player project.

## Project goals

- Ordered audio-language preferences.
- Smart subtitle selection across embedded and externally supplied tracks.
- Support for subtitle tracks passed by Stremio and other external players.
- Remembered audio, subtitle and playback choices.
- Better Android TV settings and external-player diagnostics.
- No changes to the Media3 renderer, audio sink, passthrough path, FFmpeg integration, tunneling or Dolby Vision decoding unless explicitly developed and tested as a separate change.

## Supported media

Playback support is inherited from the upstream project and the device's Android media decoders.

- **Audio:** Vorbis, Opus, FLAC, ALAC, PCM/WAVE, MP1, MP2, MP3, AMR, AAC, AC-3, E-AC-3, DTS, DTS-HD, TrueHD, IAMF and MPEG-H.
- **Video:** H.263, H.264/AVC, H.265/HEVC, MPEG-4 SP, VP8, VP9 and AV1.
- **Containers:** MP4, MOV, WebM, MKV, Ogg, MPEG-TS, MPEG-PS, FLV and limited AVI support.
- **Streaming:** DASH, HLS, SmoothStreaming and RTSP.
- **Subtitles:** SRT, SSA/ASS, TTML, VTT and DVB.
- **HDR:** HDR10+, Dolby Vision and the original optional Dolby Vision profile 7 to HDR HEVC fallback on compatible hardware.

Actual format support depends on the Android device, installed system decoders, connected display and audio equipment.

## External-player subtitle behavior

Stremio and other external players may supply subtitle tracks with the playback intent. JustPlayer Plus treats those tracks as candidates together with embedded subtitle tracks. The final track is chosen by the user's JustPlayer Plus language, forced-subtitle and source-preference rules.

## Optional AI subtitle translation

The disabled-by-default **AI subtitles** setting can manually translate the currently selected
external SRT or WebVTT track into Czech. The app sends subtitle text only after confirmation to a
user-operated backend, stores no provider API key, keeps all original tracks, and attaches the
result as `Čeština (AI)` without changing the renderer path. The companion service and deployment
instructions are in [`../ai-subtitle-backend`](../ai-subtitle-backend/README.md).

## Protected playback components

Feature work in this fork must not modify these components as part of ordinary preference or track-selection changes:

- Media3 renderer construction
- audio sink and passthrough path
- FFmpeg extension libraries
- decoder-priority implementation
- tunneled playback implementation
- Dolby Vision and HDR video-decoder path

## Building

The repository contains the GitHub Actions workflow:

```text
.github/workflows/build-just-player-plus.yml
```

It builds the `latestUniversalRelease` APK and uploads it as the `justplayer-plus-apk` workflow artifact.

## Upstream and license

This fork is based on the open-source [Just Player project](https://github.com/moneytoo/Player). Upstream source history and issue references remain available in that repository.

The source code retains its original open-source license. See [LICENSE](LICENSE) for the complete terms.
