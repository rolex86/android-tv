package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Stores the single protected next-episode response across Connector service recreation.
 *
 * <p>The cache key fingerprints the episode, aggregation settings and enabled source manifests,
 * so a changed configuration cannot reuse an older response.</p>
 */
final class StremioProtectedPrefetchCache {
    static final long MAX_AGE_MS = 2L * 60L * 60L * 1_000L;

    private static final String PREFS_NAME = "stremio_protected_prefetch_cache";
    private static final String KEY_CACHE_KEY = "cache_key";
    private static final String KEY_RESPONSE = "response";
    private static final String KEY_CREATED_AT_MS = "created_at_ms";
    private static final String KEY_STREAM_COUNT = "stream_count";

    private final SharedPreferences preferences;

    StremioProtectedPrefetchCache(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    synchronized Entry find(String requestedKey, long nowMs) {
        Entry entry = read();
        if (entry == null) {
            return null;
        }
        if (!isStructurallyValid(entry) || !isFresh(entry.createdAtMs, nowMs)) {
            clear();
            return null;
        }
        return entry.cacheKey.equals(requestedKey) ? entry : null;
    }

    synchronized boolean replace(
            String cacheKey,
            String response,
            int streamCount,
            long createdAtMs) {
        Entry entry = new Entry(cacheKey, response, streamCount, createdAtMs);
        if (!isStructurallyValid(entry)) {
            return false;
        }
        return preferences.edit()
                .putString(KEY_CACHE_KEY, cacheKey)
                .putString(KEY_RESPONSE, response)
                .putLong(KEY_CREATED_AT_MS, createdAtMs)
                .putInt(KEY_STREAM_COUNT, streamCount)
                .commit();
    }

    synchronized void retainOnly(String cacheKey) {
        String storedKey = preferences.getString(KEY_CACHE_KEY, null);
        if (storedKey != null && !storedKey.equals(cacheKey)) {
            clear();
        }
    }

    synchronized void clear() {
        preferences.edit().clear().commit();
    }

    @Nullable
    private Entry read() {
        String cacheKey = preferences.getString(KEY_CACHE_KEY, null);
        String response = preferences.getString(KEY_RESPONSE, null);
        long createdAtMs = preferences.getLong(KEY_CREATED_AT_MS, -1L);
        int streamCount = preferences.getInt(KEY_STREAM_COUNT, -1);
        if (cacheKey == null && response == null && createdAtMs < 0L && streamCount < 0) {
            return null;
        }
        return new Entry(cacheKey, response, streamCount, createdAtMs);
    }

    static boolean isFresh(long createdAtMs, long nowMs) {
        return createdAtMs >= 0L
                && nowMs >= createdAtMs
                && nowMs - createdAtMs <= MAX_AGE_MS;
    }

    static boolean isStructurallyValid(@Nullable Entry entry) {
        return entry != null
                && entry.cacheKey != null
                && !entry.cacheKey.isEmpty()
                && entry.response != null
                && entry.streamCount > 0
                && StremioConnectorService.streamCount(entry.response) == entry.streamCount;
    }

    static final class Entry {
        @Nullable final String cacheKey;
        @Nullable final String response;
        final int streamCount;
        final long createdAtMs;

        Entry(@Nullable String cacheKey,
              @Nullable String response,
              int streamCount,
              long createdAtMs) {
            this.cacheKey = cacheKey;
            this.response = response;
            this.streamCount = streamCount;
            this.createdAtMs = createdAtMs;
        }
    }
}
