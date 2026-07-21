package com.brouken.player.aisubtitles;

import android.content.Context;
import androidx.preference.PreferenceManager;

/** Preference access isolated to the optional AI subtitle feature. */
public final class AiSubtitlePreferences {
    public static final String KEY_API_TOKEN = "aiSubtitleApiToken";

    private AiSubtitlePreferences() {
    }

    public static String readApiToken(Context context) {
        String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY_API_TOKEN, "");
        return value == null ? "" : value;
    }
}
