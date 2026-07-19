package com.brouken.player;

import androidx.annotation.Nullable;

import java.util.Objects;

/** Parsed Stremio series video id, conventionally {@code metaId:season:episode}. */
final class StremioEpisodeId {
    final String raw;
    final String metaId;
    final int season;
    final int episode;

    private StremioEpisodeId(String raw, String metaId, int season, int episode) {
        this.raw = raw;
        this.metaId = metaId;
        this.season = season;
        this.episode = episode;
    }

    @Nullable
    static StremioEpisodeId parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int episodeSeparator = value.lastIndexOf(':');
        if (episodeSeparator <= 0 || episodeSeparator == value.length() - 1) {
            return null;
        }
        int seasonSeparator = value.lastIndexOf(':', episodeSeparator - 1);
        if (seasonSeparator <= 0 || seasonSeparator == episodeSeparator - 1) {
            return null;
        }
        try {
            int season = Integer.parseInt(value.substring(seasonSeparator + 1, episodeSeparator));
            int episode = Integer.parseInt(value.substring(episodeSeparator + 1));
            if (season < 0 || episode < 0) {
                return null;
            }
            return new StremioEpisodeId(
                    value, value.substring(0, seasonSeparator), season, episode);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    boolean belongsToSameSeries(StremioEpisodeId other) {
        return other != null && metaId.equals(other.metaId);
    }

    String displayCode() {
        return String.format(java.util.Locale.US, "S%02dE%02d", season, episode);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StremioEpisodeId
                && raw.equals(((StremioEpisodeId) other).raw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw);
    }
}
