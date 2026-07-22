package com.brouken.player.aisubtitles;

import android.net.Uri;

import androidx.annotation.Nullable;

/** Immutable link between the selected Media3 text track and its external source URI. */
public final class AiSubtitleSource {
    public final String id;
    @Nullable public final String label;
    @Nullable public final String language;
    public final String mimeType;
    public final String sourceFormat;
    public final Uri uri;

    AiSubtitleSource(String id,
                     @Nullable String label,
                     @Nullable String language,
                     String mimeType,
                     String sourceFormat,
                     Uri uri) {
        this.id = id;
        this.label = label;
        this.language = language;
        this.mimeType = mimeType;
        this.sourceFormat = sourceFormat;
        this.uri = uri;
    }

    public String displayName() {
        if (label != null && !label.trim().isEmpty()) {
            return label.trim();
        }
        if (language != null && !language.trim().isEmpty()) {
            return language.trim();
        }
        return "External subtitles";
    }
}
