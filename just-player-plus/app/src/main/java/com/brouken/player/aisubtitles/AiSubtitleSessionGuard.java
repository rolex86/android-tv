package com.brouken.player.aisubtitles;

import androidx.annotation.Nullable;

import java.util.Objects;

/** Pure late-result guard kept separately so lifecycle cancellation is regression-testable. */
final class AiSubtitleSessionGuard {
    private AiSubtitleSessionGuard() {
    }

    static boolean isCurrent(boolean featureEnabled,
                             boolean released,
                             long expectedToken,
                             long currentToken,
                             int expectedGeneration,
                             int currentGeneration,
                             @Nullable String expectedMedia,
                             @Nullable String currentMedia,
                             @Nullable String expectedSourceId,
                             @Nullable String currentSourceId,
                             @Nullable String expectedLanguage,
                             @Nullable String currentLanguage) {
        return featureEnabled
                && !released
                && expectedToken == currentToken
                && expectedGeneration == currentGeneration
                && Objects.equals(expectedMedia, currentMedia)
                && Objects.equals(expectedSourceId, currentSourceId)
                && Objects.equals(expectedLanguage, currentLanguage);
    }
}
