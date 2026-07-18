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
    """    public Prefs mPrefs;
    public PlusPrefs mPlusPrefs;
    public BrightnessControl mBrightnessControl;
""",
    """    public Prefs mPrefs;
    public PlusPrefs mPlusPrefs;
    private RememberedTrackStore rememberedTrackStore;
    private boolean trackMemoryArmed;
    private String trackMemorySignature;
    public BrightnessControl mBrightnessControl;
""",
    "remembered track fields",
)

replace_once(
    """        mPrefs = new Prefs(this);
        mPlusPrefs = new PlusPrefs(this);
        Utils.setOrientation(this, mPrefs.orientation);
""",
    """        mPrefs = new Prefs(this);
        mPlusPrefs = new PlusPrefs(this);
        rememberedTrackStore = new RememberedTrackStore(this);
        Utils.setOrientation(this, mPrefs.orientation);
""",
    "remembered track store initialization",
)

replace_once(
    """    public void initializePlayer() {
        boolean isNetworkUri = Utils.isSupportedNetworkUri(mPrefs.mediaUri);
        haveMedia = mPrefs.mediaUri != null;
""",
    """    public void initializePlayer() {
        boolean isNetworkUri = Utils.isSupportedNetworkUri(mPrefs.mediaUri);
        haveMedia = mPrefs.mediaUri != null;
        trackMemoryArmed = false;
        trackMemorySignature = null;
""",
    "track memory reset",
)

replace_once(
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

                    mPlusPrefs.reload();
                    RememberedTrackStore.Selection rememberedSelection =
                            rememberedTrackStore.load(
                                    mPlusPrefs.rememberTrackScope, apiTitle, mPrefs.mediaUri);
                    if (!restoredAudioSelection && rememberedSelection != null) {
                        restoredAudioSelection =
                                applyRememberedAudioSelection(rememberedSelection);
                    }
                    if (!restoredSubtitleSelection && rememberedSelection != null) {
                        restoredSubtitleSelection =
                                applyRememberedSubtitleSelection(rememberedSelection);
                    }
                    if (!restoredAudioSelection) {
                        applySmartAudioSelection();
                    }
                    if (!restoredSubtitleSelection) {
                        applySmartSubtitleSelection();
                    }
                    armTrackMemory();
""",
    "ready-state remembered track restoration",
)

replace_once(
    """        @Override
        public void onPlayerError(PlaybackException error) {
""",
    """        @Override
        public void onTracksChanged(Tracks tracks) {
            rememberManualTrackSelection(tracks);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
""",
    "track change listener",
)

replace_once(
    """    private void applySmartAudioSelection() {
""",
    """    private boolean applyRememberedAudioSelection(
            RememberedTrackStore.Selection rememberedSelection) {
        if (player == null || rememberedSelection.audioAutomatic) {
            return false;
        }
        TrackSelectionOverride override = RememberedTrackStore.findAudioOverride(
                player.getCurrentTracks(), rememberedSelection);
        if (override == null) {
            return false;
        }
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .setOverrideForType(override)
                        .build());
        return true;
    }

    private boolean applyRememberedSubtitleSelection(
            RememberedTrackStore.Selection rememberedSelection) {
        if (player == null || rememberedSelection.subtitleAutomatic) {
            return false;
        }
        TrackSelectionParameters.Builder builder =
                player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (rememberedSelection.subtitleDisabled) {
            player.setTrackSelectionParameters(
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            return true;
        }
        TrackSelectionOverride override = RememberedTrackStore.findSubtitleOverride(
                player.getCurrentTracks(), rememberedSelection);
        if (override == null) {
            return false;
        }
        player.setTrackSelectionParameters(
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(override)
                        .build());
        return true;
    }

    private void armTrackMemory() {
        trackMemoryArmed = false;
        playerView.postDelayed(() -> {
            if (player == null) {
                return;
            }
            RememberedTrackStore.Selection selection = RememberedTrackStore.capture(
                    player.getCurrentTracks(), player.getTrackSelectionParameters());
            trackMemorySignature = selection.signature();
            trackMemoryArmed = true;
        }, 500L);
    }

    private void rememberManualTrackSelection(Tracks tracks) {
        if (!trackMemoryArmed || player == null) {
            return;
        }
        mPlusPrefs.reload();
        if ("off".equals(mPlusPrefs.rememberTrackScope)) {
            return;
        }
        RememberedTrackStore.Selection selection = RememberedTrackStore.capture(
                tracks, player.getTrackSelectionParameters());
        String signature = selection.signature();
        if (signature.equals(trackMemorySignature)) {
            return;
        }
        trackMemorySignature = signature;
        rememberedTrackStore.save(
                mPlusPrefs.rememberTrackScope, apiTitle, mPrefs.mediaUri, selection);
    }

    private void applySmartAudioSelection() {
""",
    "remembered track helpers",
)

replace_once(
    """    public void releasePlayer(boolean save) {
        if (save) {
""",
    """    public void releasePlayer(boolean save) {
        trackMemoryArmed = false;
        if (save) {
""",
    "release memory disarm",
)

path.write_text(text, encoding="utf-8")
