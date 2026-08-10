package com.brouken.player;

/** MX Player-compatible result semantics for callers such as Stremio. */
final class ExternalPlaybackResultPolicy {
    static final String END_BY_PLAYBACK_COMPLETION = "playback_completion";
    static final String END_BY_USER = "user";

    private ExternalPlaybackResultPolicy() {
    }

    static String endBy(boolean playbackFinished, boolean userInitiatedExit) {
        return playbackFinished && !userInitiatedExit
                ? END_BY_PLAYBACK_COMPLETION : END_BY_USER;
    }

    /**
     * Completed playback must contain only {@code end_by}. Position and duration describe an
     * interrupted session and make Stremio resume the episode that just ended.
     */
    static boolean shouldIncludeProgress(String endBy) {
        return !END_BY_PLAYBACK_COMPLETION.equals(endBy);
    }
}
