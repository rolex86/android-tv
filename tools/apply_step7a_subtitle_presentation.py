from pathlib import Path

ROOT = Path("just-player-plus/app/src/main/java/com/brouken/player")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


subtitle_utils = ROOT / "SubtitleUtils.java"
replace_once(
    subtitle_utils,
    """                .setLanguage(subtitleLanguage)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .setLabel(subtitleName);
""",
    """                .setLanguage(subtitleLanguage)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .setLabel(subtitleName)
                .setId(SmartSubtitleSelector.EXTERNAL_ID_PREFIX
                        + Integer.toHexString(uri.toString().hashCode()));
""",
    "external subtitle id",
)

activity = ROOT / "PlayerActivity.java"
text = activity.read_text(encoding="utf-8")

replacements = [
    (
        """                    player.getCurrentTracks(), languages, allowMediaDefault,
                    mPlusPrefs.allowUnknownSubtitles);
""",
        """                    player.getCurrentTracks(), languages, allowMediaDefault,
                    mPlusPrefs.allowUnknownSubtitles,
                    mPlusPrefs.ignoreSdhSubtitles,
                    mPlusPrefs.subtitleSourcePreference);
""",
    ),
]
count = text.count(replacements[0][0])
if count != 4:
    raise RuntimeError(f"subtitle selector calls: expected 4 matches, found {count}")
text = text.replace(replacements[0][0], replacements[0][1])

old_style = """    void updateSubtitleStyle(final Context context) {
        final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        final SubtitleView subtitleView = playerView.getSubtitleView();
        final boolean isTablet = Utils.isTablet(context);
        subtitlesScale = SubtitleUtils.normalizeFontScale(captioningManager.getFontScale(), isTvBox || isTablet);
        if (subtitleView != null) {
            final CaptioningManager.CaptionStyle userStyle = captioningManager.getUserStyle();
            final CaptionStyleCompat userStyleCompat = CaptionStyleCompat.createFromCaptionStyle(userStyle);
            final CaptionStyleCompat captionStyle = new CaptionStyleCompat(
                    userStyle.hasForegroundColor() ? userStyleCompat.foregroundColor : Color.WHITE,
                    userStyle.hasBackgroundColor() ? userStyleCompat.backgroundColor : Color.TRANSPARENT,
                    userStyle.hasWindowColor() ? userStyleCompat.windowColor : Color.TRANSPARENT,
                    userStyle.hasEdgeType() ? userStyleCompat.edgeType : CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    userStyle.hasEdgeColor() ? userStyleCompat.edgeColor : Color.BLACK,
                    Typeface.create(userStyleCompat.typeface != null ? userStyleCompat.typeface : Typeface.DEFAULT,
                            mPrefs.subtitleStyleBold ? Typeface.BOLD : Typeface.NORMAL));
            subtitleView.setStyle(captionStyle);
            subtitleView.setApplyEmbeddedStyles(mPrefs.subtitleStyleEmbedded);
            subtitleView.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION * 2f / 3f);
        }
        setSubtitleTextSize();
    }
"""
new_style = """    void updateSubtitleStyle(final Context context) {
        final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        final SubtitleView subtitleView = playerView.getSubtitleView();
        final boolean isTablet = Utils.isTablet(context);
        mPlusPrefs.reload();

        subtitlesScale = SubtitleUtils.normalizeFontScale(
                captioningManager.getFontScale(), isTvBox || isTablet);
        if (!\"system\".equals(mPlusPrefs.subtitleScale)) {
            try {
                float requestedScale = Float.parseFloat(mPlusPrefs.subtitleScale) / 100f;
                subtitlesScale = SubtitleUtils.normalizeFontScale(
                        1f, isTvBox || isTablet) * requestedScale;
            } catch (NumberFormatException ignored) {
                // Keep the system-derived size for malformed stored values.
            }
        }

        if (subtitleView != null) {
            final CaptioningManager.CaptionStyle userStyle = captioningManager.getUserStyle();
            final CaptionStyleCompat userStyleCompat = CaptionStyleCompat.createFromCaptionStyle(userStyle);
            final CaptionStyleCompat captionStyle = new CaptionStyleCompat(
                    userStyle.hasForegroundColor() ? userStyleCompat.foregroundColor : Color.WHITE,
                    userStyle.hasBackgroundColor() ? userStyleCompat.backgroundColor : Color.TRANSPARENT,
                    userStyle.hasWindowColor() ? userStyleCompat.windowColor : Color.TRANSPARENT,
                    userStyle.hasEdgeType() ? userStyleCompat.edgeType : CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    userStyle.hasEdgeColor() ? userStyleCompat.edgeColor : Color.BLACK,
                    Typeface.create(userStyleCompat.typeface != null ? userStyleCompat.typeface : Typeface.DEFAULT,
                            mPrefs.subtitleStyleBold ? Typeface.BOLD : Typeface.NORMAL));
            subtitleView.setStyle(captionStyle);
            subtitleView.setApplyEmbeddedStyles(mPrefs.subtitleStyleEmbedded);

            float bottomPadding = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION * 2f / 3f;
            if (\"bottom\".equals(mPlusPrefs.subtitlePosition)) {
                bottomPadding = 0.02f;
            } else if (\"raised\".equals(mPlusPrefs.subtitlePosition)) {
                bottomPadding = 0.14f;
            }
            subtitleView.setBottomPaddingFraction(bottomPadding);
        }
        setSubtitleTextSize();
    }
"""
if text.count(old_style) != 1:
    raise RuntimeError(f"subtitle style block: expected one match, found {text.count(old_style)}")
text = text.replace(old_style, new_style, 1)
activity.write_text(text, encoding="utf-8")
