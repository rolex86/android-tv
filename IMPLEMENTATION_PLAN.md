# JustPlayer Plus implementation plan

The work was delivered as independently reviewable and revertible squash commits.

- [x] 1. Preference model and Android TV settings UI
- [x] 2. Ordered primary, secondary and tertiary audio languages
- [x] 3. Smart subtitle language and forced-subtitle rules
- [x] 4. Commentary and audio-description filtering
- [x] 5. Audio quality preference within the selected language
- [x] 6. Remembered audio and subtitle choices for title, series or global scope
- [x] 7a. Subtitle source, SDH filtering, size and position
- [x] 8. Playback defaults: resize, speed, seek steps and frame-rate policy
- [x] 9. Stremio resume result, completion threshold and back-button behavior
- [x] 10. External-player diagnostics
- [x] 11. Original-audio versus dubbed-audio preference
- [x] 12. True positive and negative subtitle delay for embedded and external subtitles
- [x] 13. Final preference-wiring audit and minSdk hardening

Protected playback components kept unchanged throughout the implementation:

- Media3 audio and video renderer construction
- audio sink and passthrough path
- FFmpeg extension libraries
- decoder priority implementation
- tunneling implementation
- Dolby Vision mapping and video decoder path

Subtitle delay is implemented before rendering by delegating to Media3's standard subtitle parsers and shifting only the generated cue timestamps. The standard text renderer and all audio/video renderers remain unchanged.
