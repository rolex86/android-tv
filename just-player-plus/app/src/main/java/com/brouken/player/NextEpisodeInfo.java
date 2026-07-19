package com.brouken.player;

import androidx.annotation.Nullable;

/** Display-ready next episode metadata, observed or derived from Stremio episode metadata. */
final class NextEpisodeInfo {
    final StremioEpisodeId current;
    final StremioEpisodeId next;
    @Nullable final String seriesTitle;
    @Nullable final String episodeTitle;
    @Nullable final String artworkUrl;

    NextEpisodeInfo(StremioEpisodeId current,
                    StremioEpisodeId next,
                    @Nullable String seriesTitle,
                    @Nullable String episodeTitle,
                    @Nullable String artworkUrl) {
        this.current = current;
        this.next = next;
        this.seriesTitle = emptyToNull(seriesTitle);
        this.episodeTitle = emptyToNull(episodeTitle);
        this.artworkUrl = emptyToNull(artworkUrl);
    }

    private static String emptyToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
