package com.brouken.player.aisubtitles;

import androidx.annotation.Nullable;

/** Request metadata sent to the private translation service. */
public final class AiSubtitleJob {
    public final String subtitleText;
    public final String sourceFormat;
    @Nullable public final String sourceLanguage;
    public final String targetLanguage;
    @Nullable public final String title;
    @Nullable public final String contentType;
    @Nullable public final String contentId;
    @Nullable public final Integer season;
    @Nullable public final Integer episode;
    public final String clientVersion;

    public AiSubtitleJob(String subtitleText,
                         String sourceFormat,
                         @Nullable String sourceLanguage,
                         String targetLanguage,
                         @Nullable String title,
                         @Nullable String contentType,
                         @Nullable String contentId,
                         @Nullable Integer season,
                         @Nullable Integer episode,
                         String clientVersion) {
        this.subtitleText = subtitleText;
        this.sourceFormat = sourceFormat;
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.title = title;
        this.contentType = contentType;
        this.contentId = contentId;
        this.season = season;
        this.episode = episode;
        this.clientVersion = clientVersion;
    }
}
