from pathlib import Path

JAVA = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
STRINGS = Path("just-player-plus/app/src/main/res/values/strings.xml")

text = JAVA.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    """    boolean intentReturnResult;
    boolean playbackFinished;

    DisplayManager displayManager;
""",
    """    boolean intentReturnResult;
    boolean playbackFinished;
    private long resultPosition = C.TIME_UNSET;
    private long resultDuration = C.TIME_UNSET;
    private AlertDialog exitDialog;

    DisplayManager displayManager;
""",
    "external result fields",
)

replace_once(
    """                    if (bundle.containsKey(API_POSITION)) {
                        mPrefs.updatePosition((long) bundle.getInt(API_POSITION));
                    }
""",
    """                    if (bundle.containsKey(API_POSITION)) {
                        Object requestedPosition = bundle.get(API_POSITION);
                        if (requestedPosition instanceof Number) {
                            mPrefs.updatePosition(((Number) requestedPosition).longValue());
                        }
                    }
""",
    "external input position",
)

replace_once(
    """    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        restorePlayStateAllowed = false;
        super.onBackPressed();
    }

    @Override
    public void finish() {
        if (intentReturnResult) {
            Intent intent = new Intent("com.mxtech.intent.result.VIEW");
            intent.putExtra(API_END_BY, playbackFinished ? "playback_completion" : "user");
            if (!playbackFinished) {
                if (player != null) {
                    long duration = player.getDuration();
                    if (duration != C.TIME_UNSET) {
                        intent.putExtra(API_DURATION, (int) player.getDuration());
                    }
                    if (player.isCurrentMediaItemSeekable()) {
                        if (mPrefs.persistentMode) {
                            intent.putExtra(API_POSITION, (int) mPrefs.nonPersitentPosition);
                        } else {
                            intent.putExtra(API_POSITION, (int) player.getCurrentPosition());
                        }
                    }
                }
            }
            setResult(Activity.RESULT_OK, intent);
        }

        super.finish();
    }
""",
    """    private void snapshotPlaybackResult() {
        if (player == null) {
            return;
        }
        long duration = player.getDuration();
        if (duration != C.TIME_UNSET && duration >= 0L) {
            resultDuration = duration;
        }
        if (player.isCurrentMediaItemSeekable()) {
            resultPosition = Math.max(0L, player.getCurrentPosition());
        }
        if (isPlaybackComplete(resultPosition, resultDuration)) {
            playbackFinished = true;
        }
    }

    private boolean isPlaybackComplete(long position, long duration) {
        if (position < 0L || duration == C.TIME_UNSET || duration <= 0L) {
            return false;
        }
        mPlusPrefs.reload();
        switch (mPlusPrefs.completionRule) {
            case "percent_90":
                return position >= Math.round(duration * 0.90d);
            case "percent_98":
                return position >= Math.round(duration * 0.98d);
            case "remaining_5m":
                if (duration > TimeUnit.MINUTES.toMillis(5)) {
                    return duration - position <= TimeUnit.MINUTES.toMillis(5);
                }
                return position >= Math.round(duration * 0.95d);
            case "percent_95":
            default:
                return position >= Math.round(duration * 0.95d);
        }
    }

    private static int externalResultValue(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private void finishFromBack() {
        restorePlayStateAllowed = false;
        super.onBackPressed();
    }

    private void showExitConfirmation() {
        if (exitDialog != null && exitDialog.isShowing()) {
            return;
        }
        final boolean resumePlayback = player != null && player.isPlaying();
        if (resumePlayback) {
            player.pause();
        }
        exitDialog = new AlertDialog.Builder(this)
                .setMessage(R.string.exit_playback_query)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (resumePlayback && player != null) {
                        player.play();
                    }
                })
                .setPositiveButton(R.string.exit_playback_confirm,
                        (dialog, which) -> finishFromBack())
                .setOnCancelListener(dialog -> {
                    if (resumePlayback && player != null) {
                        player.play();
                    }
                })
                .create();
        exitDialog.setOnDismissListener(dialog -> exitDialog = null);
        exitDialog.show();
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        mPlusPrefs.reload();
        if ("immediate".equals(mPlusPrefs.backButtonBehavior)) {
            finishFromBack();
            return;
        }
        if ("confirm".equals(mPlusPrefs.backButtonBehavior)) {
            showExitConfirmation();
            return;
        }
        if (controllerVisible && player != null && player.isPlaying()) {
            playerView.hideController();
            return;
        }
        finishFromBack();
    }

    @Override
    public void finish() {
        snapshotPlaybackResult();
        if (intentReturnResult) {
            Intent intent = new Intent("com.mxtech.intent.result.VIEW");
            intent.putExtra(API_END_BY, playbackFinished ? "playback_completion" : "user");
            if (resultDuration != C.TIME_UNSET) {
                intent.putExtra(API_DURATION, externalResultValue(resultDuration));
            }
            if (resultPosition != C.TIME_UNSET) {
                intent.putExtra(API_POSITION, externalResultValue(resultPosition));
            }
            setResult(Activity.RESULT_OK, intent);
        }

        super.finish();
    }
""",
    "back handling and external result",
)

replace_once(
    """            case KeyEvent.KEYCODE_BACK:
                if (isTvBox) {
                    if (controllerVisible && player != null && player.isPlaying()) {
                        playerView.hideController();
                        return true;
                    } else {
                        onBackPressed();
                    }
                }
                break;
""",
    """            case KeyEvent.KEYCODE_BACK:
                if (isTvBox) {
                    onBackPressed();
                    return true;
                }
                break;
""",
    "TV back behavior",
)

replace_once(
    """    private void savePlayer() {
        if (player != null) {
            mPrefs.updateBrightness(mBrightnessControl.currentBrightnessLevel);
""",
    """    private void savePlayer() {
        if (player != null) {
            snapshotPlaybackResult();
            mPrefs.updateBrightness(mBrightnessControl.currentBrightnessLevel);
""",
    "result snapshot during save",
)

replace_once(
    """            } else if (state == Player.STATE_ENDED) {
                playbackFinished = true;
                if (apiAccess) {
                    finish();
                }
            }
""",
    """            } else if (state == Player.STATE_ENDED) {
                playbackFinished = true;
                snapshotPlaybackResult();
                if (apiAccess) {
                    finish();
                }
            }
""",
    "ended result snapshot",
)

JAVA.write_text(text, encoding="utf-8")

strings = STRINGS.read_text(encoding="utf-8")
marker = "</resources>"
addition = """    <string name="exit_playback_query">Exit playback?</string>
    <string name="exit_playback_confirm">Exit</string>
</resources>"""
if strings.count(marker) != 1:
    raise RuntimeError(f"strings closing tag: expected one match, found {strings.count(marker)}")
STRINGS.write_text(strings.replace(marker, addition, 1), encoding="utf-8")
