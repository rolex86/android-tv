from pathlib import Path

ROOT = Path("just-player-plus/app/src/main/java/com/brouken/player")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


plus_prefs = ROOT / "PlusPrefs.java"
text = plus_prefs.read_text(encoding="utf-8")
text = replace_once(
    text,
    "    private int parseInt(String key, int fallback) {\n",
    """    String[] getPreferredSubtitleLanguages() {
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        appendSubtitleLanguagePreference(languages, subtitleLanguagePrimary);
        appendSubtitleLanguagePreference(languages, subtitleLanguageSecondary);
        appendSubtitleLanguagePreference(languages, subtitleLanguageTertiary);
        return languages.toArray(new String[0]);
    }

    boolean useMediaDefaultSubtitleFallback() {
        return Prefs.TRACK_DEFAULT.equals(subtitleLanguagePrimary)
                || Prefs.TRACK_DEFAULT.equals(subtitleLanguageSecondary)
                || Prefs.TRACK_DEFAULT.equals(subtitleLanguageTertiary);
    }

    private void appendSubtitleLanguagePreference(LinkedHashSet<String> languages,
                                                   String preference) {
        if (preference == null || preference.isEmpty()
                || TRACK_NONE.equals(preference) || Prefs.TRACK_DEFAULT.equals(preference)) {
            return;
        }
        if (Prefs.TRACK_DEVICE.equals(preference)) {
            for (String language : Utils.getDeviceLanguages()) {
                if (language != null && !language.isEmpty()) {
                    languages.add(language);
                }
            }
        } else {
            languages.add(preference);
        }
    }

    private int parseInt(String key, int fallback) {
""",
    "PlusPrefs subtitle language helpers",
)
plus_prefs.write_text(text, encoding="utf-8")


activity = ROOT / "PlayerActivity.java"
text = activity.read_text(encoding="utf-8")
text = replace_once(
    text,
    """        final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        if (!captioningManager.isEnabled()) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            );
        }
        Locale locale = captioningManager.getLocale();
        if (locale != null) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setPreferredTextLanguage(locale.getISO3Language())
            );
        }
""",
    """        final String[] preferredSubtitleLanguages = mPlusPrefs.getPreferredSubtitleLanguages();
        TrackSelectionParameters.Builder subtitleParameters = trackSelector.buildUponParameters()
                .setPreferredTextLanguages(preferredSubtitleLanguages)
                .setSelectUndeterminedTextLanguage(mPlusPrefs.allowUnknownSubtitles)
                .setSelectTextByDefault(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        trackSelector.setParameters(subtitleParameters);
""",
    "PlayerActivity initial subtitle parameters",
)
text = replace_once(
    text,
    """                    if (!apiAccess) {
                        setSelectedTracks(mPrefs.subtitleTrackId, mPrefs.audioTrackId);
                    }
""",
    """                    boolean restoredSubtitleSelection = false;
                    if (!apiAccess) {
                        restoredSubtitleSelection = "#none".equals(mPrefs.subtitleTrackId)
                                || getTrackGroupFromFormatId(
                                C.TRACK_TYPE_TEXT, mPrefs.subtitleTrackId) != null;
                        setSelectedTracks(mPrefs.subtitleTrackId, mPrefs.audioTrackId);
                    }
                    if (!restoredSubtitleSelection) {
                        applySmartSubtitleSelection();
                    }
""",
    "PlayerActivity smart subtitle application",
)
text = replace_once(
    text,
    """    public void setSelectedTracks(final String subtitleId, final String audioId) {
        if ("#none".equals(subtitleId)) {
            if (trackSelector == null) {
                return;
            }
            trackSelector.setParameters(trackSelector.buildUponParameters().setDisabledTextTrackSelectionFlags(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED));
        }

        TrackGroup subtitleGroup = getTrackGroupFromFormatId(C.TRACK_TYPE_TEXT, subtitleId);
        TrackGroup audioGroup = getTrackGroupFromFormatId(C.TRACK_TYPE_AUDIO, audioId);

        TrackSelectionParameters.Builder overridesBuilder = new TrackSelectionParameters.Builder(this);
        TrackSelectionOverride trackSelectionOverride = null;
        final List<Integer> tracks = new ArrayList<>(); tracks.add(0);
        if (subtitleGroup != null) {
            trackSelectionOverride = new TrackSelectionOverride(subtitleGroup, tracks);
            overridesBuilder.addOverride(trackSelectionOverride);
        }
        if (audioGroup != null) {
            trackSelectionOverride = new TrackSelectionOverride(audioGroup, tracks);
            overridesBuilder.addOverride(trackSelectionOverride);
        }

        if (player != null) {
            TrackSelectionParameters.Builder trackSelectionParametersBuilder = player.getTrackSelectionParameters().buildUpon();
            if (trackSelectionOverride != null) {
                trackSelectionParametersBuilder.setOverrideForType(trackSelectionOverride);
            }
            player.setTrackSelectionParameters(trackSelectionParametersBuilder.build());
        }
    }

""",
    """    public void setSelectedTracks(final String subtitleId, final String audioId) {
        if (player == null) {
            return;
        }

        TrackGroup subtitleGroup = getTrackGroupFromFormatId(C.TRACK_TYPE_TEXT, subtitleId);
        TrackGroup audioGroup = getTrackGroupFromFormatId(C.TRACK_TYPE_AUDIO, audioId);
        TrackSelectionParameters.Builder builder =
                player.getTrackSelectionParameters().buildUpon();

        if ("#none".equals(subtitleId)) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else if (subtitleGroup != null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(
                            subtitleGroup, Collections.singletonList(0)));
        }

        if (audioGroup != null) {
            builder.setOverrideForType(new TrackSelectionOverride(
                    audioGroup, Collections.singletonList(0)));
        }

        player.setTrackSelectionParameters(builder.build());
    }

    private void applySmartSubtitleSelection() {
        if (player == null) {
            return;
        }

        mPlusPrefs.reload();
        final String mode = mPlusPrefs.subtitleMode;
        final String[] languages = mPlusPrefs.getPreferredSubtitleLanguages();
        final boolean allowMediaDefault = mPlusPrefs.useMediaDefaultSubtitleFallback();
        TrackSelectionOverride subtitleOverride = null;

        if ("forced".equals(mode)) {
            subtitleOverride = SmartSubtitleSelector.findForcedSubtitle(
                    player.getCurrentTracks(), languages, allowMediaDefault,
                    mPlusPrefs.allowUnknownSubtitles);
        } else if ("foreign_audio".equals(mode)) {
            boolean nativeAudio = SmartSubtitleSelector.isAudioLanguageNative(
                    player.getAudioFormat(), Utils.getDeviceLanguages());
            if (nativeAudio) {
                if (mPlusPrefs.preferForcedSubtitles) {
                    subtitleOverride = SmartSubtitleSelector.findForcedSubtitle(
                            player.getCurrentTracks(), languages, allowMediaDefault,
                            mPlusPrefs.allowUnknownSubtitles);
                }
            } else {
                subtitleOverride = SmartSubtitleSelector.findFullSubtitle(
                        player.getCurrentTracks(), languages, allowMediaDefault,
                        mPlusPrefs.allowUnknownSubtitles);
            }
        } else if ("always".equals(mode)) {
            subtitleOverride = SmartSubtitleSelector.findFullSubtitle(
                    player.getCurrentTracks(), languages, allowMediaDefault,
                    mPlusPrefs.allowUnknownSubtitles);
        }

        TrackSelectionParameters.Builder builder =
                player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (subtitleOverride == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(subtitleOverride);
        }
        player.setTrackSelectionParameters(builder.build());
    }

""",
    "PlayerActivity selected tracks and smart subtitle helper",
)
activity.write_text(text, encoding="utf-8")
