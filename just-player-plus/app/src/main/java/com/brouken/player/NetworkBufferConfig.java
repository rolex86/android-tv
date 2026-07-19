package com.brouken.player;

import androidx.annotation.Nullable;

/** Immutable network buffering and retry profiles used when constructing Media3. */
final class NetworkBufferConfig {
    static final String PROFILE_DEFAULT = "default";
    static final String PROFILE_LARGER = "larger";
    static final String PROFILE_MAXIMUM = "maximum";

    static final int HTTP_CONNECT_TIMEOUT_MS = 25_000;
    static final int HTTP_READ_TIMEOUT_MS = 25_000;
    static final int LOAD_RETRY_COUNT = 6;

    final String profile;
    final int minBufferMs;
    final int maxBufferMs;
    final int bufferForPlaybackMs;
    final int bufferForPlaybackAfterRebufferMs;
    final int targetBufferBytes;

    private NetworkBufferConfig(String profile,
                                int minBufferMs,
                                int maxBufferMs,
                                int bufferForPlaybackMs,
                                int bufferForPlaybackAfterRebufferMs,
                                int targetBufferBytes) {
        this.profile = profile;
        this.minBufferMs = minBufferMs;
        this.maxBufferMs = maxBufferMs;
        this.bufferForPlaybackMs = bufferForPlaybackMs;
        this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs;
        this.targetBufferBytes = targetBufferBytes;
    }

    static NetworkBufferConfig fromPreference(@Nullable String profile) {
        if (PROFILE_LARGER.equals(profile)) {
            return new NetworkBufferConfig(
                    PROFILE_LARGER, 90_000, 90_000, 3_000, 5_000, mib(256));
        }
        if (PROFILE_MAXIMUM.equals(profile)) {
            return new NetworkBufferConfig(
                    PROFILE_MAXIMUM, 120_000, 120_000, 5_000, 10_000, mib(384));
        }
        return new NetworkBufferConfig(PROFILE_DEFAULT, 0, 0, 0, 0, 0);
    }

    boolean usesMedia3Defaults() {
        return PROFILE_DEFAULT.equals(profile);
    }

    private static int mib(int value) {
        return value * 1024 * 1024;
    }
}
