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
    """    private void handleSubtitles(Uri uri) {
        // Convert subtitles to UTF-8 if necessary
        SubtitleUtils.clearCache(this);
        uri = Utils.convertToUTF(this, uri);
        mPrefs.updateSubtitle(uri);
    }

    public void initializePlayer() {
""",
    """    private void handleSubtitles(Uri uri) {
        // Convert subtitles to UTF-8 if necessary
        SubtitleUtils.clearCache(this);
        uri = Utils.convertToUTF(this, uri);
        mPrefs.updateSubtitle(uri);
    }

    private int getInitialResizeMode() {
        if ("fit".equals(mPlusPrefs.resizeDefault)) {
            return AspectRatioFrameLayout.RESIZE_MODE_FIT;
        }
        if ("crop".equals(mPlusPrefs.resizeDefault)) {
            return AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        }
        return mPrefs.resizeMode;
    }

    private float getInitialPlaybackSpeed() {
        if ("remember".equals(mPlusPrefs.speedDefault)) {
            return mPrefs.speed;
        }
        try {
            return Float.parseFloat(mPlusPrefs.speedDefault);
        } catch (NumberFormatException ignored) {
            return mPrefs.speed;
        }
    }

    private boolean shouldMatchFrameRate(long duration) {
        if ("off".equals(mPlusPrefs.frameRatePolicy)) {
            return false;
        }
        if ("all".equals(mPlusPrefs.frameRatePolicy)) {
            return true;
        }
        if ("long_form".equals(mPlusPrefs.frameRatePolicy)) {
            return duration != C.TIME_UNSET
                    && duration >= TimeUnit.MINUTES.toMillis(20);
        }
        return mPrefs.frameRateMatching;
    }

    public void initializePlayer() {
""",
    "playback default helpers",
)

replace_once(
    "                    long seekTo = pos - 10_000;\n",
    "                    long seekTo = pos - mPlusPrefs.seekIncrementMs;\n",
    "rewind increment",
)
replace_once(
    "                    long seekTo = pos + 10_000;\n",
    "                    long seekTo = pos + mPlusPrefs.seekIncrementMs;\n",
    "fast-forward increment",
)

replace_once(
    """            playerView.setResizeMode(mPrefs.resizeMode);

            if (mPrefs.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                playerView.setScale(mPrefs.scale);
            } else {
                playerView.setScale(1.f);
            }
""",
    """            final int initialResizeMode = getInitialResizeMode();
            playerView.setResizeMode(initialResizeMode);

            if (initialResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    && "remember".equals(mPlusPrefs.resizeDefault)) {
                playerView.setScale(mPrefs.scale);
            } else {
                playerView.setScale(1.f);
            }
""",
    "initial resize mode",
)

replace_once(
    """                    if (duration != C.TIME_UNSET && duration > TimeUnit.MINUTES.toMillis(20)) {
                        timeBar.setKeyTimeIncrement(TimeUnit.MINUTES.toMillis(1));
                    } else {
                        timeBar.setKeyCountIncrement(20);
                    }
""",
    """                    timeBar.setKeyTimeIncrement(mPlusPrefs.seekIncrementMs);
""",
    "time bar seek increment",
)

replace_once(
    "                    if (mPrefs.frameRateMatching) {\n",
    "                    if (shouldMatchFrameRate(duration)) {\n",
    "frame rate policy",
)

replace_once(
    """                    if (mPrefs.speed <= 0.99f || mPrefs.speed >= 1.01f) {
                        player.setPlaybackSpeed(mPrefs.speed);
                    }
""",
    """                    final float initialPlaybackSpeed = getInitialPlaybackSpeed();
                    if (initialPlaybackSpeed <= 0.99f || initialPlaybackSpeed >= 1.01f) {
                        player.setPlaybackSpeed(initialPlaybackSpeed);
                    }
""",
    "initial playback speed",
)

path.write_text(text, encoding="utf-8")
