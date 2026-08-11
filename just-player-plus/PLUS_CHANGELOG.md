# JustPlayer Plus changelog

## Step 24 — Resilient next-episode stream handoff

- Protected next-episode prefetch now requires a complete, non-empty response from every
  compatible enabled source; partial and empty results remain short-lived and are retried.
- The Connector remembers the final ordered direct-URL stream queue for each exact episode ID.
- A series source error can continue with the next filtered stream, including another source,
  without confusing episodes or depending on Stremio retrying the same cached stream.
- Direct fallback retains the stable Stremio series identity, playback position, supplied
  subtitles and remembered manual audio/subtitle choices.
- Exact launch-marker and stream-URL associations take precedence over unrelated newer Connector
  requests while the legacy expected-episode hint remains bounded and user navigation-safe.
- Remembered known-language tracks can map to an untagged equivalent on another release; an
  untagged remembered track maps to a tagged track only when their semantic labels agree.
- Invalidated pre-version-266 aggregation and protected-prefetch cache entries, raised the
  Connector manifest to `1.12.0`, and raised the application version code to 266.

## Step 23 — Reliable next-episode popup fallback

- Kept the precise Media3 position message as the primary next-episode popup trigger.
- Added a series-only fallback watchdog after the next episode has been resolved, protecting
  against VOD streams that replace or adjust their timeline after the position message is armed.
- The watchdog checks every 30 seconds far from the popup, every 5 seconds while approaching it,
  and once per second only during the final minute before the configured notice window.
- Pause, dismissal, playback completion, player release and session replacement immediately stop
  the watchdog; timeline changes re-arm both triggers without allowing a duplicate card.
- Diagnostics now identify whether the card was triggered by Media3, a direct position check or
  the watchdog. Movies never start the watchdog.
- Raised the application version code to 257.

## Step 22 — Stremio-independent startup subtitle handoff

- The Connector now stores its bounded OpenSubtitles listing in a private, validated cache keyed
  by content ID, release filename and the configured language order.
- JustPlayer reads that cache before constructing the first media item, so OpenSubtitles remain
  available even when Stremio launches the external player with `suppliedSubtitles=0`.
- A cache miss performs one background startup lookup with the same hard 1.5-second deadline;
  playback initialization waits only for that bounded preflight and then proceeds on every result.
- Embedded-source preference still affects only automatic track selection. It never hides cached
  OpenSubtitles tracks from the subtitle list.
- No subtitle path replaces an active media item, reconnects the video source or discards its
  buffer. Title enrichment remains late and UI-only.
- Raised the application version code to 256 and the Connector manifest to 1.7.0.

## Step 21 — Playback-immutable OpenSubtitles preload

- The local Connector performs the public OpenSubtitles listing while Stremio is still resolving
  its subtitle add-ons and gives up after a hard 1.5-second deadline.
- Successful results travel through cache-safe loopback URLs that preserve language, label and
  match metadata; JustPlayer unwraps them into standard Media3 subtitle configurations.
- Every available subtitle is now part of the first media item. Late lookup and automatic
  `setMediaItem` rebuilding were removed, so subtitle enrichment cannot reset the duration,
  discard the video buffer or reconnect the active stream.
- Timeout, network failure or an empty result returns only the identity marker and never delays
  playback beyond the fixed deadline.
- Raised the application version code to 255 and the Connector manifest to 1.6.0.

## Step 20 — Cache-safe Stremio identity handoff

- The local Connector now returns a valid empty metadata subtitle carrying the Cinemeta `tt` ID
  and release filename in a loopback-only URL.
- JustPlayer removes the marker before attaching real subtitles and records its identity as a fresh
  correlation event, so cached Stremio responses remain usable across Torrentio, Comet,
  MediaFusion and other stream providers.
- Movie/episode title resolution and OpenSubtitles lookup no longer require Stremio to repeat the
  Connector request immediately before every external-player launch.
- Marker parsing accepts only the versioned `127.0.0.1` Connector URL and validated movie/series
  IDs; foreign, malformed and wrong-port URLs are ignored.

## Step 19 — On-demand AI subtitle translation

- Added a disabled-by-default, manually triggered AI translation branch for selected external
  SRT and WebVTT tracks.
- Added an isolated controller with lazy networking, strict player-session validation and
  best-effort server-side cancellation through `DELETE /v1/translations/{jobId}`.
- Added optional Bearer-token authentication, specific authorization/request/size error messages
  and bounded response validation.
- The server owns model- and prompt-aware cache invalidation; the player only stores the validated
  returned SRT by the server-provided cache key, preventing stale local translations after a model
  change.
- Preserves every original subtitle configuration and selects the stable `plus-ai:<cacheKey>`
  Czech SRT track after Media3 exposes it, without repeated progress toasts.
- Uses the separately deployed **AI Subtitle Translator** Home Assistant add-on; no Gemini API key
  is stored in the Android app.
- Kept renderers, decoder priority, audio passthrough, tunneling, buffering and Dolby Vision
  handling unchanged.

## Step 18 — Reliable Stremio title correlation and scrollable diagnostics

- Stremio title lookup no longer depends on the caller package or `return_result`; those
  constraints remain only on next-episode continuation.
- Content IDs are remembered against a private SHA-256 hash of Stremio's launch/file title, so
  replaying a cached source can still restore movie or episode metadata without stale guessing.
- Connector diagnostics now report metadata startup, correlation and missing-request states.
- The diagnostics viewer uses a focusable, vertically scrollable monospace panel for touch and
  Android TV D-pad navigation.

## Step 17 — Stremio movie titles

- Stremio movie launches now resolve their display title from Cinemeta once at startup.
- Movie title enrichment performs no playback-position polling and cannot arm the next-episode popup.
- Late metadata responses are ignored after the player session changes or the connector is disabled.

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
