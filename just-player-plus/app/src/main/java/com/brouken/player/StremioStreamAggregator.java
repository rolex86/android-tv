package com.brouken.player;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Runs all enabled upstream sources concurrently and never proxies the video itself. */
final class StremioStreamAggregator {
    private static final long TOTAL_DEADLINE_MS = 9_000L;
    private static final long CACHE_AGE_MS = 30_000L;
    private static final int MAX_CACHE_ENTRIES = 32;

    private final android.content.Context context;
    private final StremioStreamSourceStore sourceStore;
    private final ExternalPlayerDiagnostics diagnostics;
    private final OkHttpClient httpClient;
    private final StremioAddonClient addonClient;
    private final ExecutorService sourceExecutor;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    StremioStreamAggregator(android.content.Context context,
                            ExternalPlayerDiagnostics diagnostics) {
        this.context = context.getApplicationContext();
        sourceStore = new StremioStreamSourceStore(this.context);
        this.diagnostics = diagnostics;
        httpClient = StremioAddonClient.newHttpClient();
        addonClient = new StremioAddonClient(httpClient);
        sourceExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
            private int index;

            @Override
            public synchronized Thread newThread(@NonNull Runnable task) {
                Thread thread = new Thread(task, "stremio-source-" + (++index));
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    String aggregate(String type, String id) {
        StremioAggregationPreferences.Snapshot settings =
                StremioAggregationPreferences.read(context);
        List<StremioStreamSourceStore.Source> allSources = sourceStore.load();
        List<StremioStreamSourceStore.Source> sources = new ArrayList<>();
        for (StremioStreamSourceStore.Source source : allSources) {
            if (source.enabled) {
                sources.add(source);
            }
        }
        if (sources.isEmpty()) {
            return StremioConnectorService.LEGACY_STREAM_RESPONSE;
        }

        String cacheKey = cacheKey(type, id, settings, sources);
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && now - cached.createdAtMs <= CACHE_AGE_MS) {
            return cached.response;
        }

        CompletionService<StremioAddonClient.StreamResult> completion =
                new ExecutorCompletionService<>(sourceExecutor);
        List<Future<StremioAddonClient.StreamResult>> futures = new ArrayList<>();
        Map<String, Integer> priorities = new HashMap<>();
        for (int index = 0; index < sources.size(); index++) {
            StremioStreamSourceStore.Source source = sources.get(index);
            priorities.put(source.id, index);
            Callable<StremioAddonClient.StreamResult> task =
                    () -> addonClient.loadStreams(source, type, id);
            futures.add(completion.submit(task));
        }

        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(TOTAL_DEADLINE_MS);
        List<StremioStreamPipeline.SourceStreams> loaded = new ArrayList<>();
        int remaining = futures.size();
        try {
            while (remaining > 0) {
                long waitNanos = deadlineNanos - System.nanoTime();
                if (waitNanos <= 0L) {
                    break;
                }
                Future<StremioAddonClient.StreamResult> future =
                        completion.poll(waitNanos, TimeUnit.NANOSECONDS);
                if (future == null) {
                    break;
                }
                remaining--;
                StremioAddonClient.StreamResult result = future.get();
                Integer priority = priorities.get(result.source.id);
                if (priority == null) {
                    continue;
                }
                diagnostics.recordStremioConnector(
                        "aggregation_source_" + result.state,
                        "sourceId=" + shortId(result.source.id)
                                + " count=" + result.streams.size()
                                + " durationMs=" + result.durationMs);
                if ("loaded".equals(result.state)) {
                    loaded.add(new StremioStreamPipeline.SourceStreams(
                            result.source, result.streams, priority));
                }
            }
        } catch (Exception error) {
            diagnostics.recordStremioConnector(
                    "aggregation_partial", "completed=" + loaded.size());
        } finally {
            for (int index = 0; index < futures.size(); index++) {
                Future<StremioAddonClient.StreamResult> future = futures.get(index);
                if (!future.isDone()) {
                    future.cancel(true);
                    diagnostics.recordStremioConnector(
                            "aggregation_source_deadline",
                            "sourceId=" + shortId(sources.get(index).id)
                                    + " count=0 durationMs=" + TOTAL_DEADLINE_MS);
                }
            }
        }

        String response = StremioStreamPipeline.process(loaded, settings);
        if (!loaded.isEmpty() || remaining == 0) {
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(cacheKey, new CacheEntry(response, now));
        }
        diagnostics.recordStremioConnector(
                "aggregation_complete",
                "sources=" + loaded.size() + " durationMs="
                        + Math.max(0L, System.currentTimeMillis() - now));
        return response;
    }

    void shutdown() {
        cache.clear();
        sourceExecutor.shutdownNow();
        httpClient.dispatcher().cancelAll();
        httpClient.connectionPool().evictAll();
    }

    private static String cacheKey(
            String type,
            String id,
            StremioAggregationPreferences.Snapshot settings,
            List<StremioStreamSourceStore.Source> sources) {
        StringBuilder value = new StringBuilder(type).append('|').append(id)
                .append('|').append(settings.cacheKey());
        for (StremioStreamSourceStore.Source source : sources) {
            value.append('|').append(source.id)
                    .append('|').append(source.manifestUrl)
                    .append('|').append(source.name)
                    .append('|').append(source.enabled);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder();
            for (byte item : digest) {
                encoded.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.toString().hashCode());
        }
    }

    private static String shortId(String id) {
        return id.substring(0, Math.min(8, id.length()));
    }

    private static final class CacheEntry {
        final String response;
        final long createdAtMs;

        CacheEntry(String response, long createdAtMs) {
            this.response = response;
            this.createdAtMs = createdAtMs;
        }
    }
}
