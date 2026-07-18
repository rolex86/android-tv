# JustPlayer Plus changelog

## Step 2 — Ordered audio language selection

- Applies primary, secondary and tertiary audio language preferences in order.
- Expands the device-language option into the Android device language list.
- Removes duplicate language codes while preserving priority.
- Allows `Media default` to act as the fallback point in the ordered list.
- Leaves renderer creation, decoder priority, audio passthrough, FFmpeg, tunneling and Dolby Vision handling unchanged.

## Step 1 — Preference model and settings UI

- Added the complete settings model and Android TV settings screen.
- Added configuration only; no playback behavior changed in Step 1.
