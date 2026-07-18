from pathlib import Path
import re

ROOT = Path("just-player-plus/app/src/main/java/com/brouken/player")


subtitle_utils = ROOT / "SubtitleUtils.java"
text = subtitle_utils.read_text(encoding="utf-8")
old = "                .setLabel(subtitleName);"
new = """                .setLabel(subtitleName)
                .setId(SmartSubtitleSelector.EXTERNAL_ID_PREFIX
                        + Integer.toHexString(uri.toString().hashCode()));"""
if text.count(old) != 1:
    raise RuntimeError(f"external subtitle label: expected 1 match, found {text.count(old)}")
subtitle_utils.write_text(text.replace(old, new, 1), encoding="utf-8")


activity = ROOT / "PlayerActivity.java"
text = activity.read_text(encoding="utf-8")

pattern = re.compile(
    r"(player\.getCurrentTracks\(\), languages, allowMediaDefault,\s*\n\s*)"
    r"mPlusPrefs\.allowUnknownSubtitles\);"
)
replacement = (
    r"\1mPlusPrefs.allowUnknownSubtitles,\n"
    r"                    mPlusPrefs.ignoreSdhSubtitles,\n"
    r"                    mPlusPrefs.subtitleSourcePreference);"
)
text, count = pattern.subn(replacement, text)
if count != 4:
    raise RuntimeError(f"subtitle selector calls: expected 4 matches, found {count}")

old = "        final boolean isTablet = Utils.isTablet(context);\n"
new = """        final boolean isTablet = Utils.isTablet(context);
        mPlusPrefs.reload();
"""
if text.count(old) != 1:
    raise RuntimeError(f"subtitle prefs reload: expected 1 match, found {text.count(old)}")
text = text.replace(old, new, 1)

old = "        subtitlesScale = SubtitleUtils.normalizeFontScale(captioningManager.getFontScale(), isTvBox || isTablet);\n"
new = """        subtitlesScale = SubtitleUtils.normalizeFontScale(
                captioningManager.getFontScale(), isTvBox || isTablet);
        if (!"system".equals(mPlusPrefs.subtitleScale)) {
            try {
                float requestedScale = Float.parseFloat(mPlusPrefs.subtitleScale) / 100f;
                subtitlesScale = SubtitleUtils.normalizeFontScale(
                        1f, isTvBox || isTablet) * requestedScale;
            } catch (NumberFormatException ignored) {
                // Keep the system-derived size for malformed stored values.
            }
        }
"""
if text.count(old) != 1:
    raise RuntimeError(f"subtitle scale: expected 1 match, found {text.count(old)}")
text = text.replace(old, new, 1)

old = "            subtitleView.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION * 2f / 3f);\n"
new = """            float bottomPadding = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION * 2f / 3f;
            if ("bottom".equals(mPlusPrefs.subtitlePosition)) {
                bottomPadding = 0.02f;
            } else if ("raised".equals(mPlusPrefs.subtitlePosition)) {
                bottomPadding = 0.14f;
            }
            subtitleView.setBottomPaddingFraction(bottomPadding);
"""
if text.count(old) != 1:
    raise RuntimeError(f"subtitle position: expected 1 match, found {text.count(old)}")
text = text.replace(old, new, 1)

activity.write_text(text, encoding="utf-8")
