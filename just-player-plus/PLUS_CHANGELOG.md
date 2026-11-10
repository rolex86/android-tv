# JustPlayer Plus changelog

## Step 34 — Fix the source-wait slider resource namespace

- Use Android's framework `max` attribute for the aggregation `SeekBarPreference`, while keeping
  the AndroidX-specific minimum, increment, value display and continuous persistence attributes.
- Extend the preference audit to require the resource-linkable namespace combination.
- Raised the application version code to 276; the Connector remains at `1.14.0` because behavior
  and its local add-on protocol are unchanged from the configurable-wait implementation.

## Step 33 — Make the Connector source wait configurable on TV

- Added a TV-friendly source-wait slider to the aggregation settings with a range of 3–30 seconds,
  one-second remote-control steps and the existing nine-second behavior as its default.
- Persist each slider step immediately. A running lookup keeps the snapshot it started with, while
  the next lookup uses the new value without requiring the user to leave the settings row.
- Apply the selected value to both the overall aggregation deadline and the bounded upstream stream
  request, including a cold manifest lookup, so increasing the slider genuinely allows a slow
  healthy source more time without creating an unbounded request.
- Include the selected wait in aggregation cache identity and Connector diagnostics so changing the
  value cannot reuse a partial response produced under a different deadline and remains auditable.
- Raised the application version code to 275; the Connector remains at `1.14.0` because its local
  add-on protocol and response schema are unchanged.

## Step 32 — Let slow Connector sources finish without blocking on broken ones

- Removed the 1.5-second post-result cutoff. Every healthy enabled source can now use the normal
  nine-second aggregation window, so a fast add-on no longer suppresses slower Torrentio-like
  results merely by finishing first.
- Added a two-minute in-memory circuit breaker per source and manifest URL. A source that times out
  or fails is still queried on later requests, but outside the foreground critical path; any clean
  background result immediately restores it to normal waiting.
- Let unfinished source calls continue within their own bounded HTTP timeout instead of cancelling
  them when the foreground response is ready. Their exact stream response is cached briefly, so a
  later lookup can include results that completed after the current Stremio response was returned.
- Added a persistent routing-only manifest cache. A validated manifest is fresh for one hour, can
  be used while it is revalidated in the background for at most 24 hours, and supports conditional
  `ETag` / `Last-Modified` requests when the add-on supplies validators.
- Manifest URL changes select a different cache fingerprint. Definitive `401`, `403`, `404` and
  `410` responses, or a valid manifest without a stream resource, invalidate the cached route;
  timeouts, throttling and server failures retain only the bounded stale fallback.
- Kept incomplete aggregate responses on a one-second cache while complete responses retain the
  existing 30-second cache. Protected next-episode prefetch still requires every source to finish
  cleanly before it is persisted.
- Raised the application version code to 274; the Connector remains at `1.14.0` because its local
  add-on protocol and response schema are unchanged.

## Step 31 — Isolate stalled Connector sources

- Kept all enabled stream add-ons concurrent, but once a foreground request has at least one
  usable result it waits only a 1.5-second grace period for the remaining sources instead of being
  held for the full nine-second aggregation deadline.
- A valid empty response does not start the grace period, so the Connector can still wait for a
  different source that actually has streams for the requested item.
- A pending source is deferred only for the current foreground response and is tried again on a
  later lookup; it is never automatically disabled or permanently classified as broken.
- Background next-episode prefetch retains the full aggregation deadline and protected prefetch
  still requires every compatible enabled source to finish cleanly.
- Contained unexpected per-source task failures so one failed future cannot cancel collection of
  results already arriving from other sources.
- Raised the application version code to 273; the Connector remains at `1.14.0` because its local
  addon protocol and response schema are unchanged.

## Step 30 — Restore the Connector after application updates

- Registered the app-targeted `MY_PACKAGE_REPLACED` broadcast so Android restarts an explicitly
  enabled local Connector immediately after installing a newer APK.
- Reused the existing foreground-service startup path, which remains gated by the persisted
  Connector preference; an explicitly disabled Connector stays disabled after an update.
- Kept account queue recovery limited to device boot because WorkManager already preserves its
  scheduled work across application updates.
- Added regression and manifest audit coverage for both reboot and package-replacement recovery.
- Raised the application version code to 272; the Connector remains at `1.14.0` because its local
  addon protocol is unchanged.

## Step 29 — Keep account synchronization off media startup

- Disabled WorkManager's automatic process-start initializer and switched it to supported
  on-demand configuration.
- Removed the account queue flush from `PlayerActivity.onCreate()` so first media preparation does
  not read the encrypted account key, parse the retry queue or initialize background schedulers.
- Dispatch the persisted-queue check only once after playback is actually running, with Keystore
  and queue work performed off the main thread.
- Preserved network-constrained retry, checkpoint scheduling and reboot recovery.
- Raised the application version code to 271; the Connector remains at `1.14.0` because its local
  addon protocol is unchanged.

## Step 28 — Optional Stremio account progress synchronization

- Added a single default-off gate for synchronizing only episodes that continue inside JustPlayer
  Plus and therefore cannot use Stremio's original external-player callback.
- Account linking uses Stremio's one-time link flow; the resulting auth key is encrypted with a
  non-exportable Android Keystore key in the no-backup directory and is never stored in preferences.
- Read–modify–write synchronization preserves the complete current `libraryItem`, verifies a
  second pre-write snapshot and rereads the server object after every update.
- Completed episodes update the official anchored watched bitfield while preserving all existing
  episode bits. Partial episodes update the exact video ID, resume position, duration and
  `lastWatched` state without inferring watched time from seeks.
- Added durable, credential-free retry checkpoints, 90-second in-player progress snapshots and
  immediate snapshots on pause or exit. A network-constrained WorkManager task survives app and
  device restarts and retries failed delivery with exponential backoff until the queue is verified
  on the server. Disabling the gate cancels scheduled/running work and clears the queue.
- Movies and a single Stremio-launched episode that exits without internal continuation remain on
  the standard callback path. Once an internal continuation is accepted and that callback must be
  suppressed, account sync records the completed current episode as well as every later episode.
- Raised the application version code to 270; the Connector remains at `1.14.0` because its local
  addon protocol is unchanged.

## Step 27 — Shield-safe in-player episode continuation

- JustPlayer Plus now keeps the external-player activity alive and resolves the exact next series
  episode through the local Connector instead of returning early to Stremio.
- Protected prefetch persists the complete ordered direct-stream plan before the end of the current
  episode, while actual media probing remains sequential and starts only after the transition so a
  second decoder or concurrent video load cannot burden Shield-class devices.
- Every candidate is verified against Media3's real audio and subtitle tracks. The remembered
  per-series selection is a hard contract; an unknown or conflicting language is skipped instead
  of being played optimistically.
- Candidate failover is now paused and muted with fixed per-candidate and overall timeouts, zero
  probe retries and an explicit safe-failure dialog when no compatible release exists.
- Manual Play and natural completion use the same continuation path. Direct-stream recovery for
  the current episode follows the same strict track validation before resuming at its old position.
- Added a bounded, episode-deduplicated local watched journal as the stable handoff point for a
  future user-authorized Trakt synchronization module.
- Raised the application version code to 269 and the Connector manifest to `1.14.0`.

## Step 26 — Return next-episode control to Stremio

- Removed the custom `stremio:///detail/...?...autoPlay=true` launch that opened an episode detail
  or stream list instead of continuing through Stremio's active external-player session.
- Natural completion now returns the standard MX Player-compatible
  `end_by=playback_completion` result without old-episode position or duration, leaving episode
  selection and autoplay to the calling Stremio activity.
- The popup's explicit Play action uses the same result handoff immediately instead of seeking the
  old stream to its final frame or launching a second Stremio activity.
- The exact resolved next episode remains stored as a short-lived Connector correlation hint before
  the result is returned, preserving track memory, prefetch and direct-stream fallback identity.
- Dismissing the popup still returns a user exit at natural end and therefore opts out of automatic
  continuation.
- Raised the application version code to 268; the Connector remains at `1.13.0` because its stream
  API and ordering are unchanged.

## Step 25 — Preserve complete upstream stream relevance

- Source-provided numeric match scores now take precedence over cache, language and file-size
  preferences inside that source's already assigned result positions.
- Fixed the real Webshare false-positive case where both `Bluey` and `KILL BLUE` are marked as
  strong matches, but their numeric scores are `1.0` and `0.545454…` respectively.
- Per-source limits now retain the source's most relevant candidates instead of allowing a larger
  similarly named file to consume the available slots.
- Sources without explicit relevance metadata keep the existing technical sorting behavior.
- Invalidated pre-version-267 aggregation and protected-prefetch cache entries, raised the
  Connector manifest to `1.13.0`, and raised the application version code to 267.

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
