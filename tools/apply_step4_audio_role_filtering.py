from pathlib import Path

path = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
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
    """                    boolean restoredSubtitleSelection = false;
                    boolean restoredAudioSelection = false;
                    if (!apiAccess) {
                        restoredSubtitleSelection = "#none".equals(mPrefs.subtitleTrackId)
                                || getTrackGroupFromFormatId(
                                C.TRACK_TYPE_TEXT, mPrefs.subtitleTrackId) != null;
                        restoredAudioSelection = getTrackGroupFromFormatId(
                                C.TRACK_TYPE_AUDIO, mPrefs.audioTrackId) != null;
                        setSelectedTracks(mPrefs.subtitleTrackId, mPrefs.audioTrackId);
                    }
                    if (!restoredAudioSelection) {
                        applySmartAudioSelection();
                    }
                    if (!restoredSubtitleSelection) {
                        applySmartSubtitleSelection();
                    }
""",
    "ready-state smart audio application",
)

replace_once(
    """    private void applySmartSubtitleSelection() {
""",
    """    private void applySmartAudioSelection() {
        if (player == null) {
            return;
        }

        mPlusPrefs.reload();
        if (!mPlusPrefs.ignoreCommentaryAudio && !mPlusPrefs.ignoreAudioDescription) {
            return;
        }

        TrackSelectionOverride audioOverride = SmartAudioSelector.findPreferredAudio(
                player.getCurrentTracks(),
                mPlusPrefs.getPreferredAudioLanguages(),
                mPlusPrefs.ignoreCommentaryAudio,
                mPlusPrefs.ignoreAudioDescription);
        if (audioOverride != null) {
            player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters().buildUpon()
                            .setOverrideForType(audioOverride)
                            .build());
        }
    }

    private void applySmartSubtitleSelection() {
""",
    "smart audio helper insertion",
)

path.write_text(text, encoding="utf-8")
