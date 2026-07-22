package com.brouken.player.aisubtitles;

/** Validated ready response from the translation service. */
public final class AiSubtitleResult {
    public final String cacheKey;
    public final String outputFormat;
    public final String language;
    public final String label;
    public final String subtitleText;
    public final boolean cached;

    AiSubtitleResult(String cacheKey,
                     String outputFormat,
                     String language,
                     String label,
                     String subtitleText,
                     boolean cached) {
        this.cacheKey = cacheKey;
        this.outputFormat = outputFormat;
        this.language = language;
        this.label = label;
        this.subtitleText = subtitleText;
        this.cached = cached;
    }
}
