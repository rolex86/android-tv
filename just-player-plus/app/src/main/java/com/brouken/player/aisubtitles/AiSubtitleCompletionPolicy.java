package com.brouken.player.aisubtitles;

import androidx.annotation.Nullable;

/** Pure completion helpers for selecting a newly attached AI subtitle track. */
final class AiSubtitleCompletionPolicy {
    private AiSubtitleCompletionPolicy() {
    }

    static boolean matchesPendingTrack(@Nullable String pendingId, @Nullable String runtimeId) {
        return SubtitleTrackIdentity.sameStableId(pendingId, runtimeId);
    }
}
