# JustPlayer Plus changelog

## Step 16 — Projected playback end time

- Shows the projected wall-clock end beside the current position and media duration.
- Honors the device's 12/24-hour clock format and the active playback speed.
- Updates only on controller and player events, with no background polling.
- Added an enabled-by-default setting to hide or show the estimate.

## Step 15 — Streaming resilience

- Added Default, Larger (90 s / 256 MiB) and Maximum (120 s / 384 MiB) network-buffer profiles.
- Increased HTTP connect/read timeouts to 25 seconds and retriable source-load attempts to six.
- Keeps the Media3 default load control unchanged unless a larger network profile is selected.

## Step 14 — Next-episode efficiency and strict opt-out

- Replaced one-second playback-position polling with a single Media3 position message.
- Distinguishes Stremio movie and series launches so movies never arm an episode card.
- Creates metadata/card resources only while the connector is enabled and immediately cancels
  pending metadata, artwork and popup work when it is disabled.

## Step 13 — Final audit and hardening

- Added a permanent CI check that maps every Plus preference to its settings entry and runtime hook.
- Added checks protecting renderer priority, Dolby Vision mapping and audio passthrough construction.
- Replaced subtitle timestamp overflow helpers with arithmetic compatible with the minimum supported Android version.

## Step 12 — Two-way subtitle delay

- Applies positive and negative subtitle delay by shifting cue timestamps produced by Media3's standard subtitle parsers.
- Covers embedded, external, progressive and adaptive-streaming subtitle sources.
- Preserves correct cue emission after seeking.
- Leaves the text, audio and video renderers unchanged.

## Step 11 — Original audio or dubbing

- Added a choice between language-order selection, preferred original audio and preferred dubbing.
- Uses Media3 role metadata plus conservative label detection.
- Falls back to the existing language and quality ordering when metadata is insufficient.

## Step 10 — External-player diagnostics

- Added a bounded, privacy-conscious log for received intents, tracks, selection reasons, return values and playback errors.
- Added settings actions to view, copy and clear diagnostics.
- Removes query parameters, credentials and full paths from logged media URLs.

## Step 9 — Stremio resume and exit behavior

- Returns consistent position, duration and completion status to callers requesting an external-player result.
- Added configurable 90%, 95%, 98% and five-minutes-remaining completion rules.
- Added controls-then-exit, immediate-exit and confirmation back-button modes.

## Step 8 — Playback defaults

- Added configurable remembered/Fit/Crop startup resize mode.
- Added configurable remembered or fixed startup playback speed.
- Added configurable remote and time-bar seek increments.
- Added legacy, disabled, all-video and long-form-only frame-rate policies.

## Step 7a — Subtitle presentation and source preference

- Added subtitle size and vertical-position choices.
- Added embedded/external subtitle source preference.
- Added SDH/hearing-impaired subtitle avoidance while retaining fallback behavior.
- Tagged externally supplied subtitle tracks for reliable source classification.

## Step 6 — Remembered track choices

- Remembers manual audio and subtitle choices by title, series or globally.
- Restores explicit subtitle-off state as well as selected audio/subtitle tracks.
- Keeps automatic selection as the fallback when a remembered track is unavailable.

## Step 5 — Audio quality preference

- Ranks supported audio tracks within the selected language by channel count and bitrate.
- Supports best-quality and first-matching-track behavior.

## Step 4 — Commentary and audio-description filtering

- Avoids commentary and descriptive-audio tracks during automatic selection.
- Uses role flags and conservative label matching.
- Falls back to excluded tracks only when no normal supported audio track is available.

## Step 3 — Smart subtitles and forced rules

- Added Off, forced-only, foreign-audio and always-on subtitle modes.
- Added ordered subtitle languages, unknown-language fallback and media-default fallback.
- Selects forced tracks for native audio when requested and full subtitles for foreign audio.

## Step 2 — Ordered audio language selection

- Applies primary, secondary and tertiary audio language preferences in order.
- Expands the device-language option into the Android device language list.
- Removes duplicate language codes while preserving priority.
- Allows `Media default` to act as the fallback point in the ordered list.

## Step 1 — Preference model and settings UI

- Added the complete Plus preference model and Android TV settings screen.
- Kept playback behavior unchanged until the corresponding independent steps were implemented.

## Protected playback path

All steps preserve audio/video renderer construction, decoder priority, the audio sink and passthrough path, FFmpeg extensions, tunneling, Dolby Vision mapping and the video decoder path.
