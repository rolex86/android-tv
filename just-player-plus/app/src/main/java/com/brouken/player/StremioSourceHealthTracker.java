package com.brouken.player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps recently failing sources out of the foreground critical path without disabling them. */
final class StremioSourceHealthTracker {
    static final long DEGRADED_AGE_MS = 2L * 60L * 1_000L;
    private static final int MAX_ENTRIES = 64;

    private final ConcurrentHashMap<String, Long> degradedAt = new ConcurrentHashMap<>();

    boolean isDegraded(StremioStreamSourceStore.Source source, long nowMs) {
        String key = key(source);
        Long failedAtMs = degradedAt.get(key);
        if (failedAtMs == null) {
            return false;
        }
        if (failedAtMs >= 0L
                && nowMs >= failedAtMs
                && nowMs - failedAtMs <= DEGRADED_AGE_MS) {
            return true;
        }
        degradedAt.remove(key, failedAtMs);
        return false;
    }

    void recordState(
            StremioStreamSourceStore.Source source,
            String state,
            long nowMs) {
        String key = key(source);
        if (StremioStreamAggregator.isCompleteSourceState(state)) {
            degradedAt.remove(key);
            return;
        }
        if ("cancelled".equals(state) || "manifest_cancelled".equals(state)) {
            return;
        }
        if (degradedAt.size() >= MAX_ENTRIES && !degradedAt.containsKey(key)) {
            String oldestKey = null;
            long oldestTimestamp = Long.MAX_VALUE;
            for (Map.Entry<String, Long> entry : degradedAt.entrySet()) {
                if (entry.getValue() < oldestTimestamp) {
                    oldestTimestamp = entry.getValue();
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                degradedAt.remove(oldestKey);
            }
        }
        degradedAt.put(key, nowMs);
    }

    void clear() {
        degradedAt.clear();
    }

    private static String key(StremioStreamSourceStore.Source source) {
        return source.id + '|' + StremioManifestCache.fingerprint(source.manifestUrl);
    }
}
