package com.brouken.player.aisubtitles;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;

/** Creates stable Media3 configurations for cached AI-generated SRT tracks. */
public final class AiSubtitleTrackFactory {
    public static final String CZECH_AI_LABEL = "Čeština (AI)";

    private AiSubtitleTrackFactory() {
    }

    public static MediaItem.SubtitleConfiguration create(
            AiSubtitleFileStore.StoredSubtitle subtitle) {
        return new MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                .setId(SelectedSubtitleResolver.AI_ID_PREFIX + subtitle.cacheKey)
                .setLanguage(subtitle.language)
                .setLabel(CZECH_AI_LABEL)
                .setMimeType("application/x-subrip")
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .build();
    }
}
