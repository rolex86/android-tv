package com.brouken.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Runs all enabled upstream sources concurrently and never proxies the video itself. */
final class StremioStreamAggregator {
    private static final String CACHE_SCHEMA = "upstream-relevance-v5";
    static final long DEGRADED_PROBE_GRACE_MS = 250L;
    private static final long COMPLETION_POLL_SLICE_MS = 100L;
    private static final long REGULAR_CACHE_AGE_MS = 30_000L;
    private static final long PARTIAL_CACHE_AGE_MS = 1_000L;
    private static final int MAX_CACHE_ENTRIES = 32;

    private final android.content.Context context;
    private final StremioStreamSourceStore sourceStore;
    private final StremioProtectedPrefetchCache protectedPrefetchCache;
    private final ExternalPlayerDiagnostics diagnostics;
    private final OkHttpClient httpClient;
    private final StremioAddonClient addonClient;
    private final StremioSourceHealthTracker sourceHealthTracker =
            new StremioSourceHealthTracker();
    private final ExecutorService aggregationExecutor;
    private final ExecutorService sourceExecutor;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Object flightLock = new Object();
    private final Map<String, AggregationTask> inFlight = new HashMap<>();

    @Nullable private AggregationTask activePrefetchTask;
    @Nullable private volatile String protectedPrefetchKey;

    StremioStreamAggregator(android.content.Context context,
                            ExternalPlayerDiagnostics diagnostics) {
        this.context = context.getApplicationContext();
        sourceStore = new StremioStreamSourceStore(this.context);
        protectedPrefetchCache = new StremioProtectedPrefetchCache(this.context);
        this.diagnostics = diagnostics;
        httpClient = StremioAddonClient.newHttpClient();
        addonClient = new StremioAddonClient(this.context, httpClient);
        aggregationExecutor = Executors.newCachedThreadPool(
                namedThreadFactory("stremio-aggregation-"));
        sourceExecutor = Executors.newCachedThreadPool(
                namedThreadFactory("stremio-source-"));
    }

    String aggregate(String type, String id) {
        RequestSnapshot request = snapshot(type, id);
        if (request.sources.isEmpty()) {
            recordNoSources(request, "no_enabled_sources");
            return StremioConnectorService.LEGACY_STREAM_RESPONSE;
        }

        CacheEntry cached = findCached(request.cacheKey, System.currentTimeMillis(), true);
        if (cached != null) {
            recordCacheHit(cached, "foreground");
            return cached.result.pipeline.response;
        }
        StremioProtectedPrefetchCache.Entry persisted = findPersistedCacheHit(
                request, System.currentTimeMillis(), "foreground");
        if (persisted != null) {
            return persisted.response;
        }

        AggregationTask task;
        boolean joined;
        synchronized (flightLock) {
            cached = findCached(request.cacheKey, System.currentTimeMillis(), true);
            if (cached != null) {
                recordCacheHit(cached, "foreground");
                return cached.result.pipeline.response;
            }
            persisted = findPersistedCacheHit(
                    request, System.currentTimeMillis(), "foreground_recheck");
            if (persisted != null) {
                return persisted.response;
            }
            task = inFlight.get(request.cacheKey);
            joined = task != null;
            if (task == null) {
                task = startTaskLocked(request);
            }
            task.foregroundRequested = true;
            task.foregroundWaiters++;
        }
        if (joined) {
            diagnostics.recordStremioConnector(
                    "aggregation_joined",
                    type + "/" + id + " mode=foreground");
        }

        try {
            return task.future.get().pipeline.response;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            diagnostics.recordStremioConnector(
                    "aggregation_wait_cancelled", type + "/" + id + " reason=interrupted");
            return StremioConnectorService.LEGACY_STREAM_RESPONSE;
        } catch (CancellationException error) {
            diagnostics.recordStremioConnector(
                    "aggregation_wait_cancelled", type + "/" + id + " reason=cancelled");
            return StremioConnectorService.LEGACY_STREAM_RESPONSE;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            throw new IllegalStateException(cause == null ? error : cause);
        } finally {
            synchronized (flightLock) {
                task.foregroundWaiters = Math.max(0, task.foregroundWaiters - 1);
            }
        }
    }

    /**
     * Warms one next-episode target. A new target replaces obsolete background work instead of
     * appending to a queue. Foreground HTTP waiters are never cancelled.
     */
    void prefetch(String type, String id) {
        RequestSnapshot request = snapshot(type, id);
        if (request.sources.isEmpty()) {
            recordNoSources(request, "prefetch_no_enabled_sources");
            return;
        }

        AggregationTask task;
        String state;
        synchronized (flightLock) {
            StremioProtectedPrefetchCache.Entry persisted =
                    protectedPrefetchCache.find(
                            request.cacheKey, System.currentTimeMillis());
            boolean sameTarget = request.cacheKey.equals(protectedPrefetchKey)
                    || persisted != null;
            if (persisted != null) {
                protectedPrefetchKey = request.cacheKey;
            }
            CacheEntry cached = findCached(
                    request.cacheKey, System.currentTimeMillis(), sameTarget);
            if (sameTarget && cached != null) {
                recordCacheHit(cached, "prefetch");
                return;
            }
            if (sameTarget && persisted != null) {
                recordPersistedCacheHit(persisted, "prefetch");
                return;
            }

            if (!sameTarget) {
                AggregationTask previous = activePrefetchTask;
                protectedPrefetchKey = request.cacheKey;
                protectedPrefetchCache.retainOnly(request.cacheKey);
                activePrefetchTask = null;
                if (previous != null && shouldCancelReplacedPrefetch(
                        previous.request.cacheKey,
                        request.cacheKey,
                        previous.foregroundWaiters)) {
                    previous.prefetchOwner = false;
                    cancelTaskLocked(previous, "replaced");
                } else if (previous != null
                        && !previous.request.cacheKey.equals(request.cacheKey)) {
                    previous.prefetchOwner = false;
                    diagnostics.recordStremioConnector(
                            "aggregation_prefetch_detached",
                            previous.request.type + "/" + previous.request.id
                                    + " reason=replaced foregroundWaiters="
                                    + previous.foregroundWaiters);
                }
            }

            CacheEntry cachedAfterReplacement = findCached(
                    request.cacheKey, System.currentTimeMillis(), true);
            if (cachedAfterReplacement != null) {
                recordCacheHit(cachedAfterReplacement, "prefetch");
                return;
            }
            StremioProtectedPrefetchCache.Entry persistedAfterReplacement =
                    protectedPrefetchCache.find(
                            request.cacheKey, System.currentTimeMillis());
            if (persistedAfterReplacement != null) {
                recordPersistedCacheHit(persistedAfterReplacement, "prefetch");
                return;
            }

            task = inFlight.get(request.cacheKey);
            if (task == null) {
                task = startTaskLocked(request);
                state = "started";
            } else {
                state = "joined";
            }
            task.prefetchOwner = true;
            activePrefetchTask = task;
        }
        diagnostics.recordStremioConnector(
                "aggregation_prefetch_" + state,
                type + "/" + id + " enabledSources=" + request.sources.size());
    }

    void shutdown() {
        synchronized (flightLock) {
            for (AggregationTask task : new ArrayList<>(inFlight.values())) {
                cancelTaskLocked(task, "shutdown");
            }
            inFlight.clear();
            activePrefetchTask = null;
            protectedPrefetchKey = null;
        }
        cache.clear();
        sourceHealthTracker.clear();
        addonClient.shutdown();
        aggregationExecutor.shutdownNow();
        sourceExecutor.shutdownNow();
        httpClient.dispatcher().cancelAll();
        httpClient.connectionPool().evictAll();
    }

    private AggregationTask startTaskLocked(RequestSnapshot request) {
        AggregationTask task = new AggregationTask(request);
        inFlight.put(request.cacheKey, task);
        try {
            aggregationExecutor.execute(task.future);
        } catch (RejectedExecutionException error) {
            if (inFlight.get(request.cacheKey) == task) {
                inFlight.remove(request.cacheKey);
            }
            throw error;
        }
        return task;
    }

    private void cancelTaskLocked(AggregationTask task, String reason) {
        if (inFlight.get(task.request.cacheKey) == task) {
            inFlight.remove(task.request.cacheKey);
        }
        if (activePrefetchTask == task) {
            activePrefetchTask = null;
        }
        task.prefetchOwner = false;
        task.cancellation.cancel();
        task.future.cancel(true);
        diagnostics.recordStremioConnector(
                "aggregation_prefetch_cancelled",
                task.request.type + "/" + task.request.id + " reason=" + reason);
    }

    private AggregationResult executeAndCache(AggregationTask task) {
        AggregationResult result = runAggregation(task);
        long completedAtMs = System.currentTimeMillis();
        CacheEntry cachedEntry = null;
        if (!task.cancellation.isCancelled() && result.cacheable) {
            cachedEntry = putCache(task.request.cacheKey, result, completedAtMs);
            int streamCount = StremioConnectorService.streamCount(
                    result.pipeline.response);
            boolean persistAttempted = false;
            boolean persistSucceeded = false;
            synchronized (flightLock) {
                if (!task.cancellation.isCancelled()
                        && task.prefetchOwner
                        && task.request.cacheKey.equals(protectedPrefetchKey)
                        && result.protectable) {
                    persistAttempted = true;
                    persistSucceeded = protectedPrefetchCache.replace(
                            task.request.cacheKey,
                            result.pipeline.response,
                            streamCount,
                            completedAtMs);
                    if (persistSucceeded && cachedEntry != null) {
                        cachedEntry.protectedPrefetch = true;
                    }
                }
            }
            if (persistAttempted) {
                diagnostics.recordStremioConnector(
                        persistSucceeded
                                ? "aggregation_prefetch_persisted"
                                : "aggregation_prefetch_persist_failed",
                        task.request.type + "/" + task.request.id
                                + " streams=" + streamCount);
            }
        }
        if (!task.cancellation.isCancelled() && task.prefetchOwner) {
            diagnostics.recordStremioConnector(
                    "aggregation_prefetch_complete",
                    task.request.type + "/" + task.request.id + ' '
                            + result.pipeline.stats.summary()
                            + " durationMs=" + result.durationMs);
        }
        return result;
    }

    private void onTaskFinished(AggregationTask task) {
        synchronized (flightLock) {
            if (inFlight.get(task.request.cacheKey) == task) {
                inFlight.remove(task.request.cacheKey);
            }
            if (activePrefetchTask == task) {
                activePrefetchTask = null;
            }
        }
    }

    private AggregationResult runAggregation(AggregationTask task) {
        RequestSnapshot request = task.request;
        StremioRequestCancellation cancellation = task.cancellation;
        long startedAt = System.currentTimeMillis();
        long startedAtNanos = System.nanoTime();
        CompletionService<StremioAddonClient.StreamResult> completion =
                new ExecutorCompletionService<>(sourceExecutor);
        List<SourceCall> sourceCalls = new ArrayList<>();
        Map<Future<StremioAddonClient.StreamResult>, SourceCall> futureSources =
                new HashMap<>();
        int requiredRemaining = 0;
        for (int index = 0; index < request.sources.size(); index++) {
            StremioStreamSourceStore.Source source = request.sources.get(index);
            boolean degradedAtStart = sourceHealthTracker.isDegraded(
                    source, System.currentTimeMillis());
            SourceCall sourceCall = new SourceCall(source, index, degradedAtStart);
            if (!degradedAtStart) {
                requiredRemaining++;
            }
            Callable<StremioAddonClient.StreamResult> sourceTask =
                    () -> {
                        try {
                            StremioAddonClient.StreamResult result = addonClient.loadStreams(
                                    source,
                                    request.type,
                                    request.id,
                                    request.settings.sourceWaitMs(),
                                    cancellation);
                            sourceHealthTracker.recordState(
                                    source, result.state, System.currentTimeMillis());
                            return result;
                        } catch (RuntimeException error) {
                            sourceHealthTracker.recordState(
                                    source, "exception", System.currentTimeMillis());
                            throw error;
                        }
                    };
            Future<StremioAddonClient.StreamResult> future = completion.submit(sourceTask);
            sourceCall.future = future;
            sourceCalls.add(sourceCall);
            futureSources.put(future, sourceCall);
        }

        long deadlineNanos = startedAtNanos
                + TimeUnit.MILLISECONDS.toNanos(request.settings.sourceWaitMs());
        long degradedProbeDeadlineNanos = startedAtNanos
                + TimeUnit.MILLISECONDS.toNanos(DEGRADED_PROBE_GRACE_MS);
        List<StremioStreamPipeline.SourceStreams> loaded = new ArrayList<>();
        int remaining = sourceCalls.size();
        int completedSources = 0;
        int failedSources = 0;
        String stopReason = "all_sources";
        try {
            while (remaining > 0 && !cancellation.isCancelled()) {
                long nowNanos = System.nanoTime();
                if (task.foregroundRequested && requiredRemaining <= 0) {
                    boolean allowShortProbe = completedSources == 0
                            && nowNanos < degradedProbeDeadlineNanos;
                    if (!allowShortProbe) {
                        stopReason = "degraded_sources_background";
                        break;
                    }
                }
                long effectiveDeadlineNanos = deadlineNanos;
                if (task.foregroundRequested
                        && requiredRemaining <= 0
                        && completedSources == 0) {
                    effectiveDeadlineNanos = Math.min(
                            deadlineNanos, degradedProbeDeadlineNanos);
                }
                long waitNanos = effectiveDeadlineNanos - nowNanos;
                if (waitNanos <= 0L) {
                    stopReason = effectiveDeadlineNanos < deadlineNanos
                            ? "degraded_sources_background" : "total_deadline";
                    break;
                }
                Future<StremioAddonClient.StreamResult> future =
                        completion.poll(
                                Math.min(
                                        waitNanos,
                                        TimeUnit.MILLISECONDS.toNanos(
                                                COMPLETION_POLL_SLICE_MS)),
                                TimeUnit.NANOSECONDS);
                if (future == null) {
                    continue;
                }
                SourceCall sourceCall = futureSources.get(future);
                if (sourceCall == null || sourceCall.completed) {
                    continue;
                }
                sourceCall.completed = true;
                remaining--;
                completedSources++;
                if (!sourceCall.degradedAtStart) {
                    requiredRemaining = Math.max(0, requiredRemaining - 1);
                }
                StremioAddonClient.StreamResult result;
                try {
                    result = future.get();
                } catch (CancellationException error) {
                    if (!cancellation.isCancelled()) {
                        failedSources++;
                        recordSourceTaskFailure(
                                sourceCall.source, "cancelled", error);
                    }
                    continue;
                } catch (ExecutionException error) {
                    failedSources++;
                    Throwable cause = error.getCause();
                    recordSourceTaskFailure(
                            sourceCall.source,
                            "exception",
                            cause == null ? error : cause);
                    continue;
                }
                diagnostics.recordStremioConnector(
                        "aggregation_source_" + result.state,
                        "sourceId=" + shortId(result.source.id)
                                + " count=" + result.streams.size()
                                + " durationMs=" + result.durationMs);
                if ("loaded".equals(result.state)) {
                    loaded.add(new StremioStreamPipeline.SourceStreams(
                            result.source, result.streams, sourceCall.priority));
                } else if (!isCompleteSourceState(result.state)) {
                    failedSources++;
                }
            }
            if (cancellation.isCancelled()) {
                stopReason = "cancelled";
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            stopReason = "interrupted";
            diagnostics.recordStremioConnector(
                    "aggregation_partial",
                    "loadedSources=" + loaded.size()
                            + " remainingSources=" + remaining
                            + " error=InterruptedException");
        } catch (Exception error) {
            stopReason = "exception";
            diagnostics.recordStremioConnector(
                    "aggregation_partial",
                    "loadedSources=" + loaded.size()
                            + " remainingSources=" + remaining
                            + " error=" + error.getClass().getSimpleName());
        }

        for (SourceCall sourceCall : sourceCalls) {
            if (sourceCall.completed || sourceCall.future.isDone()) {
                continue;
            }
            if ("total_deadline".equals(stopReason)
                    && !sourceCall.degradedAtStart) {
                sourceHealthTracker.recordState(
                        sourceCall.source, "deadline", System.currentTimeMillis());
            }
            diagnostics.recordStremioConnector(
                    "aggregation_source_background",
                    "sourceId=" + shortId(sourceCall.source.id)
                            + " count=0 reason=" + stopReason
                            + " durationMs=" + Math.max(
                            0L, System.currentTimeMillis() - startedAt));
        }

        StremioStreamPipeline.Result pipeline =
                StremioStreamPipeline.processDetailed(loaded, request.settings);
        diagnostics.recordStremioConnector(
                "loaded".equals(pipeline.state)
                        ? "aggregation_pipeline" : "aggregation_pipeline_failed",
                "state=" + pipeline.state + ' ' + pipeline.stats.summary());
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        boolean complete = remaining == 0
                && failedSources == 0
                && !cancellation.isCancelled();
        boolean cacheable = "loaded".equals(pipeline.state)
                && (!loaded.isEmpty() || remaining == 0);
        boolean protectable = cacheable && complete && pipeline.stats.returned > 0;
        diagnostics.recordStremioConnector(
                "aggregation_complete",
                "configuredSources=" + request.allSources.size()
                        + " enabledSources=" + request.sources.size()
                        + " sourceWaitSeconds=" + request.settings.sourceWaitSeconds
                        + " loadedSources=" + loaded.size() + ' '
                        + "failedSources=" + failedSources
                        + " complete=" + complete
                        + " stopReason=" + stopReason
                        + " protectable=" + protectable + ' '
                        + pipeline.stats.summary() + " durationMs=" + durationMs);
        return new AggregationResult(
                pipeline, cacheable, protectable, complete, durationMs);
    }

    static boolean isCompleteSourceState(String state) {
        return "loaded".equals(state)
                || "unsupported_type".equals(state)
                || "unsupported_id".equals(state)
                || "missing_stream_resource".equals(state);
    }

    static boolean shouldAwaitSource(boolean foregroundRequested, boolean degraded) {
        return !foregroundRequested || !degraded;
    }

    private void recordSourceTaskFailure(
            @Nullable StremioStreamSourceStore.Source source,
            String state,
            Throwable error) {
        diagnostics.recordStremioConnector(
                "aggregation_source_" + state,
                "sourceId=" + (source == null ? "unknown" : shortId(source.id))
                        + " count=0 error=" + error.getClass().getSimpleName());
    }

    private RequestSnapshot snapshot(String type, String id) {
        StremioAggregationPreferences.Snapshot settings =
                StremioAggregationPreferences.read(context);
        List<StremioStreamSourceStore.Source> allSources = sourceStore.load();
        List<StremioStreamSourceStore.Source> sources = new ArrayList<>();
        for (StremioStreamSourceStore.Source source : allSources) {
            if (source.enabled) {
                sources.add(source);
            }
        }
        return new RequestSnapshot(
                type, id, settings, allSources, sources,
                cacheKey(type, id, settings, sources));
    }

    @Nullable
    private CacheEntry findCached(String key, long nowMs, boolean allowProtected) {
        CacheEntry cached = cache.get(key);
        if (cached == null) {
            return null;
        }
        boolean protectedEntry = allowProtected
                && cached.protectedPrefetch
                && key.equals(protectedPrefetchKey);
        long maxAgeMs = cached.result.complete
                ? REGULAR_CACHE_AGE_MS : PARTIAL_CACHE_AGE_MS;
        if (protectedEntry || nowMs - cached.createdAtMs <= maxAgeMs) {
            return cached;
        }
        cache.remove(key, cached);
        return null;
    }

    private void recordCacheHit(CacheEntry cached, String mode) {
        diagnostics.recordStremioConnector(
                "aggregation_cache_hit",
                cached.result.pipeline.stats.summary()
                        + " ageMs=" + Math.max(
                        0L, System.currentTimeMillis() - cached.createdAtMs)
                        + " mode=" + mode);
    }

    @Nullable
    private StremioProtectedPrefetchCache.Entry findPersistedCacheHit(
            RequestSnapshot request,
            long nowMs,
            String mode) {
        StremioProtectedPrefetchCache.Entry persisted =
                protectedPrefetchCache.find(request.cacheKey, nowMs);
        if (persisted == null) {
            return null;
        }
        protectedPrefetchKey = request.cacheKey;
        recordPersistedCacheHit(persisted, mode);
        return persisted;
    }

    private void recordPersistedCacheHit(
            StremioProtectedPrefetchCache.Entry cached,
            String mode) {
        diagnostics.recordStremioConnector(
                "aggregation_persistent_cache_hit",
                "streams=" + cached.streamCount
                        + " ageMs=" + Math.max(
                        0L, System.currentTimeMillis() - cached.createdAtMs)
                        + " mode=" + mode);
    }

    private CacheEntry putCache(String key, AggregationResult result, long createdAtMs) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            String oldestKey = null;
            long oldestTimestamp = Long.MAX_VALUE;
            String protectedKey = protectedPrefetchKey;
            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                if (entry.getKey().equals(protectedKey)) {
                    continue;
                }
                if (entry.getValue().createdAtMs < oldestTimestamp) {
                    oldestTimestamp = entry.getValue().createdAtMs;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                cache.remove(oldestKey);
            }
        }
        CacheEntry entry = new CacheEntry(result, createdAtMs);
        cache.put(key, entry);
        return entry;
    }

    private void recordNoSources(RequestSnapshot request, String reason) {
        diagnostics.recordStremioConnector(
                "aggregation_complete",
                "configuredSources=" + request.allSources.size()
                        + " enabledSources=0 loadedSources=0"
                        + " raw=0 accepted=0 returned=0"
                        + " reason=" + reason + " durationMs=0");
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        return new ThreadFactory() {
            private int index;

            @Override
            public synchronized Thread newThread(@NonNull Runnable task) {
                Thread thread = new Thread(task, prefix + (++index));
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static String cacheKey(
            String type,
            String id,
            StremioAggregationPreferences.Snapshot settings,
            List<StremioStreamSourceStore.Source> sources) {
        StringBuilder value = new StringBuilder(CACHE_SCHEMA)
                .append('|').append(type).append('|').append(id)
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

    static boolean shouldCancelReplacedPrefetch(
            @Nullable String currentKey,
            @Nullable String replacementKey,
            int foregroundWaiters) {
        return currentKey != null
                && replacementKey != null
                && !currentKey.equals(replacementKey)
                && foregroundWaiters <= 0;
    }

    private final class AggregationTask {
        final RequestSnapshot request;
        final StremioRequestCancellation cancellation = new StremioRequestCancellation();
        final FutureTask<AggregationResult> future;
        int foregroundWaiters;
        volatile boolean foregroundRequested;
        volatile boolean prefetchOwner;

        AggregationTask(RequestSnapshot request) {
            this.request = request;
            future = new FutureTask<>(() -> {
                try {
                    return executeAndCache(this);
                } catch (RuntimeException error) {
                    if (prefetchOwner) {
                        diagnostics.recordStremioConnector(
                                "aggregation_prefetch_failed",
                                request.type + "/" + request.id
                                        + " error=" + error.getClass().getSimpleName());
                    }
                    throw error;
                } finally {
                    onTaskFinished(this);
                }
            });
        }
    }

    private static final class RequestSnapshot {
        final String type;
        final String id;
        final StremioAggregationPreferences.Snapshot settings;
        final List<StremioStreamSourceStore.Source> allSources;
        final List<StremioStreamSourceStore.Source> sources;
        final String cacheKey;

        RequestSnapshot(
                String type,
                String id,
                StremioAggregationPreferences.Snapshot settings,
                List<StremioStreamSourceStore.Source> allSources,
                List<StremioStreamSourceStore.Source> sources,
                String cacheKey) {
            this.type = type;
            this.id = id;
            this.settings = settings;
            this.allSources = allSources;
            this.sources = sources;
            this.cacheKey = cacheKey;
        }
    }

    private static final class AggregationResult {
        final StremioStreamPipeline.Result pipeline;
        final boolean cacheable;
        final boolean protectable;
        final boolean complete;
        final long durationMs;

        AggregationResult(
                StremioStreamPipeline.Result pipeline,
                boolean cacheable,
                boolean protectable,
                boolean complete,
                long durationMs) {
            this.pipeline = pipeline;
            this.cacheable = cacheable;
            this.protectable = protectable;
            this.complete = complete;
            this.durationMs = durationMs;
        }
    }

    private static final class SourceCall {
        @NonNull final StremioStreamSourceStore.Source source;
        final int priority;
        final boolean degradedAtStart;
        Future<StremioAddonClient.StreamResult> future;
        boolean completed;

        SourceCall(
                StremioStreamSourceStore.Source source,
                int priority,
                boolean degradedAtStart) {
            this.source = source;
            this.priority = priority;
            this.degradedAtStart = degradedAtStart;
        }
    }

    private static final class CacheEntry {
        final AggregationResult result;
        final long createdAtMs;
        volatile boolean protectedPrefetch;

        CacheEntry(AggregationResult result, long createdAtMs) {
            this.result = result;
            this.createdAtMs = createdAtMs;
        }
    }
}
