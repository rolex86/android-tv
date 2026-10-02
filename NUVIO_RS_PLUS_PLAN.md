# Nuvio RS Plus implementation plan

Nuvio RS Plus is maintained in the same repository as JustPlayer Plus but as an
independent Android TV application.

## Upstream

- Source: `DavidVamaiotu/NuvioTV-Reshaped`
- Branch: `subtitle-autosync`
- Local source directory: `nuvio-rs-plus/`
- Local application name: `Nuvio RS Plus`
- Local application id: `com.rolex86.nuviorsplus`
- Update repository: `rolex86/android-tv`

The initial import is a git subtree so later upstream pulls can merge normally instead
of replacing the whole source tree.

## Goals

- Keep Nuvio Reshaped playback behavior, AutoSync, audio passthrough, buffering,
  Dolby Vision handling, tracking and update logic intact unless a feature explicitly
  requires a small integration point.
- Keep every Plus feature isolated and independently testable.
- Never publish an automatic update after an unresolved upstream merge conflict or a
  failed build/test.
- Keep the app separately installable from official Nuvio and official Nuvio RS.

## Feature phases

- [ ] 0. Bootstrap Nuvio RS Plus source, identity, signing and CI
- [ ] 1. AI subtitle translation
  - manual translation of the currently selected external/addon SRT or WebVTT track
  - existing self-hosted translator backend
  - optional Bearer token
  - translated track appears as `Čeština (AI)`
  - preserve original tracks
  - cooperate with Nuvio RS AutoSync rather than replacing it
- [ ] 2. Smart audio exclusions
  - ignore commentary tracks
  - ignore audio-description / descriptive-audio tracks
  - conservative role-flag and label matching
  - safe fallback when every candidate is filtered
- [ ] 3. Original vs dubbed audio preference
  - language order remains primary
  - modes: language order / prefer original / prefer dubbing
  - conservative role-flag and label matching
- [ ] 4. Best-quality audio ranking
  - rank compatible candidates within the selected language
  - codec, channel count and bitrate as tie-breakers
  - never modify passthrough, audio sink or decoder construction
- [ ] 5. Subtitle source preference
  - automatic / embedded / addon
  - preserve preferred-language, forced-subtitle and AutoSync behavior
- [ ] 6. Release/update automation
  - periodic upstream check
  - merge latest Nuvio RS changes
  - run unit tests and release build
  - publish only on success
  - in-app updater points to this repository

## Protected playback paths

Ordinary Plus work must not casually modify:

- Media3 video renderer construction
- audio sink and passthrough
- decoder-priority implementation
- tunneled playback
- Dolby Vision / HDR processing
- Nuvio RS buffer engine
- Nuvio RS AutoSync timing engine

If a feature requires touching one of these paths, it is treated as a separate change
with dedicated testing.

## Release model

The repository already contains a persistent signing setup for JustPlayer Plus. The
Nuvio RS Plus build workflow may reuse that signing identity for stable update
compatibility, while the Android application id remains unique.

Nuvio service configuration is supplied at build time. Public client configuration can
be resolved from Nuvio's public configuration endpoint, while credentials that are not
public are kept out of source control.
