# JustPlayer Plus implementation plan

The work is split into independently reviewable and revertible commits.

1. Preference model and settings UI
2. Ordered audio language selection
3. Smart subtitle language and forced-subtitle rules
4. Commentary and audio-description filtering
5. Audio quality preference within the selected language
6. Remembered choices for title and series
7. Subtitle delay and in-app subtitle appearance
8. Playback defaults: resize, speed, seek steps and frame-rate policy
9. Stremio resume, completion threshold and back-button behavior
10. External-player diagnostics

Protected playback components that must remain unchanged:

- Media3 renderer construction
- audio sink and passthrough path
- FFmpeg extension libraries
- decoder priority implementation
- tunneling implementation
- Dolby Vision mapping and video decoder path
