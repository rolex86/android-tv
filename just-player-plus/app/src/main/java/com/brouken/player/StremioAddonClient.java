package com.brouken.player;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Generic bounded client for any upstream add-on implementing Stremio's stream resource. */
final class StremioAddonClient {
    static final long SOURCE_TIMEOUT_MS = 8_000L;
    static final long MIN_SOURCE_WAIT_MS = 3_000L;
    static final long MAX_SOURCE_WAIT_MS = 30_000L;
    private static final int MAX_MANIFEST_BYTES = 1 * 1024 * 1024;
    private static final int MAX_STREAM_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_STREAMS_PER_RESPONSE = 500;
    private static final long STREAM_CACHE_AGE_MS = 30_000L;
    private static final int MAX_STREAM_CACHE_ENTRIES = 64;

    private final OkHttpClient httpClient;
    @Nullable private final StremioManifestCache manifestCache;
    @Nullable private final ExecutorService manifestRefreshExecutor;
    private final Set<String> manifestRefreshes = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    private final ConcurrentHashMap<String, StreamCacheEntry> streamCache =
            new ConcurrentHashMap<>();

    StremioAddonClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
        manifestCache = null;
        manifestRefreshExecutor = null;
    }

    StremioAddonClient(Context context, OkHttpClient httpClient) {
        this.httpClient = httpClient;
        manifestCache = new StremioManifestCache(context);
        manifestRefreshExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private int index;

            @Override
            public synchronized Thread newThread(@NonNull Runnable task) {
                Thread thread = new Thread(task, "stremio-manifest-refresh-" + (++index));
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    static OkHttpClient newHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(MAX_SOURCE_WAIT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(MAX_SOURCE_WAIT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    ManifestResult inspectManifest(String manifestUrl) {
        ManifestDocument document = loadManifest(manifestUrl, null, null);
        if (document.manifest == null) {
            return new ManifestResult(false, "", document.state);
        }
        JSONObject manifest = document.manifest;
        if (!hasStreamResource(manifest)) {
            return new ManifestResult(false, "", "missing_stream_resource");
        }
        String name = manifest.optString("name", "").trim();
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        return new ManifestResult(true, name, "loaded");
    }

    private ManifestDocument loadManifest(
            String manifestUrl,
            @Nullable StremioRequestCancellation cancellation,
            @Nullable StremioManifestCache.Entry validators) {
        return loadManifest(manifestUrl, cancellation, validators, SOURCE_TIMEOUT_MS);
    }

    private ManifestDocument loadManifest(
            String manifestUrl,
            @Nullable StremioRequestCancellation cancellation,
            @Nullable StremioManifestCache.Entry validators,
            long timeoutMs) {
        HttpUrl url = parseManifestUrl(manifestUrl);
        if (url == null) {
            return new ManifestDocument(null, "invalid_url");
        }
        Request request = request(url, validators);
        Call call = httpClient.newCall(request);
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS);
        if (cancellation != null && !cancellation.register(call)) {
            return new ManifestDocument(null, "cancelled");
        }
        try (Response response = call.execute()) {
            if (response.code() == 304 && validators != null) {
                return new ManifestDocument(
                        null,
                        "not_modified",
                        response.header("ETag"),
                        response.header("Last-Modified"));
            }
            if (!response.isSuccessful()) {
                return new ManifestDocument(null, "http_" + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                return new ManifestDocument(null, "invalid_body");
            }
            JSONObject manifest = new JSONObject(
                    BoundedResponseBody.readUtf8(body, MAX_MANIFEST_BYTES));
            return new ManifestDocument(
                    manifest,
                    "loaded",
                    response.header("ETag"),
                    response.header("Last-Modified"));
        } catch (InterruptedIOException error) {
            return new ManifestDocument(null,
                    call.isCanceled()
                            || (cancellation != null && cancellation.isCancelled())
                            ? "cancelled" : "timeout");
        } catch (BoundedResponseBody.ResponseTooLargeException error) {
            return new ManifestDocument(null, "response_too_large");
        } catch (IOException | JSONException | RuntimeException error) {
            return new ManifestDocument(null,
                    call.isCanceled()
                            || (cancellation != null && cancellation.isCancelled())
                            ? "cancelled" : "invalid_response");
        } finally {
            if (cancellation != null) {
                cancellation.unregister(call);
            }
        }
    }

    StreamResult loadStreams(StremioStreamSourceStore.Source source,
                             String type,
                             String id) {
        return loadStreams(
                source,
                type,
                id,
                TimeUnit.SECONDS.toMillis(
                        StremioAggregationPreferences.DEFAULT_SOURCE_WAIT_SECONDS),
                null);
    }

    StreamResult loadStreams(StremioStreamSourceStore.Source source,
                             String type,
                             String id,
                             @Nullable StremioRequestCancellation cancellation) {
        return loadStreams(
                source,
                type,
                id,
                TimeUnit.SECONDS.toMillis(
                        StremioAggregationPreferences.DEFAULT_SOURCE_WAIT_SECONDS),
                cancellation);
    }

    StreamResult loadStreams(StremioStreamSourceStore.Source source,
                             String type,
                             String id,
                             long sourceWaitMs,
                             @Nullable StremioRequestCancellation cancellation) {
        long startedAt = System.currentTimeMillis();
        long boundedSourceWaitMs = Math.max(
                MIN_SOURCE_WAIT_MS, Math.min(MAX_SOURCE_WAIT_MS, sourceWaitMs));
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(boundedSourceWaitMs);
        String cacheKey = streamCacheKey(source, type, id);
        StreamResult cachedResult = findStreamCache(
                source, cacheKey, System.currentTimeMillis());
        if (cachedResult != null) {
            return cachedResult;
        }

        JSONObject manifest;
        long nowMs = System.currentTimeMillis();
        StremioManifestCache.Entry cachedManifest = manifestCache == null
                ? null : manifestCache.find(source);
        if (cachedManifest != null
                && StremioManifestCache.isFresh(cachedManifest.validatedAtMs, nowMs)) {
            manifest = cachedManifest.manifest;
        } else if (cachedManifest != null
                && StremioManifestCache.isUsableStale(
                        cachedManifest.validatedAtMs, nowMs)) {
            scheduleManifestRefresh(source, cachedManifest);
            manifest = cachedManifest.manifest;
        } else if (cachedManifest != null) {
            scheduleManifestRefresh(source, cachedManifest);
            return new StreamResult(source, Collections.emptyList(),
                    "manifest_cache_expired", elapsed(startedAt));
        } else {
            long manifestTimeoutMs = remainingTimeoutMs(deadlineNanos);
            if (manifestTimeoutMs <= 0L) {
                return new StreamResult(source, Collections.emptyList(),
                        "timeout", elapsed(startedAt));
            }
            ManifestDocument document = loadManifest(
                    source.manifestUrl,
                    cancellation,
                    null,
                    manifestTimeoutMs);
            if (document.manifest == null) {
                invalidateManifestOnHardFailure(source, document.state);
                return new StreamResult(source, Collections.emptyList(),
                        "manifest_" + document.state, elapsed(startedAt));
            }
            manifest = document.manifest;
            if (hasStreamResource(manifest)) {
                if (manifestCache != null) {
                    manifestCache.replace(
                            source,
                            manifest,
                            document.etag,
                            document.lastModified,
                            System.currentTimeMillis());
                }
            } else if (manifestCache != null) {
                manifestCache.invalidate(source);
            }
        }
        String support = streamSupport(manifest, type, id);
        if (!"supported".equals(support)) {
            return new StreamResult(source, Collections.emptyList(),
                    support, elapsed(startedAt));
        }
        HttpUrl streamUrl = buildStreamUrl(source.manifestUrl, type, id);
        if (streamUrl == null) {
            return new StreamResult(source, Collections.emptyList(),
                    "invalid_url", elapsed(startedAt));
        }
        Request request = request(streamUrl);
        long remainingTimeoutMs = remainingTimeoutMs(deadlineNanos);
        if (remainingTimeoutMs <= 0L) {
            return new StreamResult(source, Collections.emptyList(),
                    "timeout", elapsed(startedAt));
        }
        Call call = httpClient.newCall(request);
        call.timeout().timeout(remainingTimeoutMs, TimeUnit.MILLISECONDS);
        if (cancellation != null && !cancellation.register(call)) {
            return new StreamResult(source, Collections.emptyList(),
                    "cancelled", elapsed(startedAt));
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                return new StreamResult(source, Collections.emptyList(),
                        "http_" + response.code(), elapsed(startedAt));
            }
            ResponseBody body = response.body();
            if (body == null) {
                return new StreamResult(source, Collections.emptyList(),
                        "invalid_body", elapsed(startedAt));
            }
            JSONObject responseJson = new JSONObject(
                    BoundedResponseBody.readUtf8(body, MAX_STREAM_RESPONSE_BYTES));
            JSONArray streams = responseJson.optJSONArray("streams");
            if (streams == null) {
                return new StreamResult(source, Collections.emptyList(),
                        "invalid_response", elapsed(startedAt));
            }
            List<JSONObject> parsed = new ArrayList<>();
            int limit = Math.min(streams.length(), MAX_STREAMS_PER_RESPONSE);
            for (int index = 0; index < limit; index++) {
                JSONObject stream = streams.optJSONObject(index);
                if (stream != null) {
                    parsed.add(stream);
                }
            }
            StreamResult result = new StreamResult(
                    source, parsed, "loaded", elapsed(startedAt));
            putStreamCache(cacheKey, result, System.currentTimeMillis());
            return result;
        } catch (InterruptedIOException error) {
            return new StreamResult(source, Collections.emptyList(),
                    call.isCanceled()
                            || (cancellation != null && cancellation.isCancelled())
                            ? "cancelled" : "timeout",
                    elapsed(startedAt));
        } catch (BoundedResponseBody.ResponseTooLargeException error) {
            return new StreamResult(source, Collections.emptyList(),
                    "response_too_large", elapsed(startedAt));
        } catch (IOException | JSONException | RuntimeException error) {
            return new StreamResult(source, Collections.emptyList(),
                    call.isCanceled()
                            || (cancellation != null && cancellation.isCancelled())
                            ? "cancelled" : "invalid_response",
                    elapsed(startedAt));
        } finally {
            if (cancellation != null) {
                cancellation.unregister(call);
            }
        }
    }

    private static long remainingTimeoutMs(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    void shutdown() {
        streamCache.clear();
        manifestRefreshes.clear();
        if (manifestRefreshExecutor != null) {
            manifestRefreshExecutor.shutdownNow();
        }
    }

    @Nullable
    static HttpUrl parseManifestUrl(String value) {
        if (value == null || value.length() > 8_192) {
            return null;
        }
        HttpUrl url = HttpUrl.parse(value.trim());
        if (url == null || !("http".equals(url.scheme()) || "https".equals(url.scheme()))) {
            return null;
        }
        String host = url.host();
        if (url.port() == StremioConnectorService.PORT
                && ("127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host))) {
            // Adding the Connector to itself would recursively exhaust all request workers.
            return null;
        }
        List<String> segments = url.pathSegments();
        if (segments.isEmpty()
                || !"manifest.json".equalsIgnoreCase(segments.get(segments.size() - 1))) {
            return null;
        }
        return url;
    }

    @Nullable
    static HttpUrl buildStreamUrl(String manifestUrl, String type, String id) {
        HttpUrl manifest = parseManifestUrl(manifestUrl);
        if (manifest == null || id == null
                || !("movie".equals(type) || "series".equals(type))) {
            return null;
        }
        HttpUrl.Builder builder = manifest.newBuilder();
        builder.removePathSegment(manifest.pathSegments().size() - 1);
        return builder.addPathSegment("stream")
                .addPathSegment(type)
                .addPathSegment(id + ".json")
                .build();
    }

    private static Request request(HttpUrl url) {
        return request(url, null);
    }

    private static Request request(
            HttpUrl url,
            @Nullable StremioManifestCache.Entry validators) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayer Plus Connector");
        if (validators != null) {
            if (validators.etag != null) {
                builder.header("If-None-Match", validators.etag);
            }
            if (validators.lastModified != null) {
                builder.header("If-Modified-Since", validators.lastModified);
            }
        }
        return builder.build();
    }

    static boolean hasStreamResource(JSONObject manifest) {
        JSONArray resources = manifest.optJSONArray("resources");
        if (resources == null) {
            return false;
        }
        for (int index = 0; index < resources.length(); index++) {
            Object resource = resources.opt(index);
            if (resource instanceof String && "stream".equals(resource)) {
                return true;
            }
            if (resource instanceof JSONObject
                    && "stream".equals(((JSONObject) resource).optString("name", ""))) {
                return true;
            }
        }
        return false;
    }

    private void scheduleManifestRefresh(
            StremioStreamSourceStore.Source source,
            StremioManifestCache.Entry cached) {
        if (manifestCache == null || manifestRefreshExecutor == null) {
            return;
        }
        String refreshKey = source.id + '|'
                + StremioManifestCache.fingerprint(source.manifestUrl);
        if (!manifestRefreshes.add(refreshKey)) {
            return;
        }
        try {
            manifestRefreshExecutor.execute(() -> {
                try {
                    ManifestDocument document = loadManifest(
                            source.manifestUrl, null, cached);
                    long refreshedAtMs = System.currentTimeMillis();
                    if ("not_modified".equals(document.state)) {
                        manifestCache.touch(
                                source,
                                cached,
                                document.etag,
                                document.lastModified,
                                refreshedAtMs);
                    } else if (document.manifest != null) {
                        if (hasStreamResource(document.manifest)) {
                            manifestCache.replace(
                                    source,
                                    document.manifest,
                                    document.etag,
                                    document.lastModified,
                                    refreshedAtMs);
                        } else {
                            manifestCache.invalidate(source);
                        }
                    } else {
                        invalidateManifestOnHardFailure(source, document.state);
                    }
                } finally {
                    manifestRefreshes.remove(refreshKey);
                }
            });
        } catch (RejectedExecutionException error) {
            manifestRefreshes.remove(refreshKey);
        }
    }

    private void invalidateManifestOnHardFailure(
            StremioStreamSourceStore.Source source,
            String state) {
        if (manifestCache != null && isHardManifestFailure(state)) {
            manifestCache.invalidate(source);
        }
    }

    static boolean isHardManifestFailure(String state) {
        return "http_401".equals(state)
                || "http_403".equals(state)
                || "http_404".equals(state)
                || "http_410".equals(state);
    }

    @Nullable
    private StreamResult findStreamCache(
            StremioStreamSourceStore.Source source,
            String key,
            long nowMs) {
        StreamCacheEntry entry = streamCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.createdAtMs < 0L
                || nowMs < entry.createdAtMs
                || nowMs - entry.createdAtMs > STREAM_CACHE_AGE_MS) {
            streamCache.remove(key, entry);
            return null;
        }
        return new StreamResult(
                source, copyStreams(entry.streams), "loaded", 0L);
    }

    private void putStreamCache(String key, StreamResult result, long createdAtMs) {
        if (!"loaded".equals(result.state)) {
            return;
        }
        if (streamCache.size() >= MAX_STREAM_CACHE_ENTRIES
                && !streamCache.containsKey(key)) {
            String oldestKey = null;
            long oldestTimestamp = Long.MAX_VALUE;
            for (Map.Entry<String, StreamCacheEntry> entry : streamCache.entrySet()) {
                if (entry.getValue().createdAtMs < oldestTimestamp) {
                    oldestTimestamp = entry.getValue().createdAtMs;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                streamCache.remove(oldestKey);
            }
        }
        streamCache.put(key, new StreamCacheEntry(
                copyStreams(result.streams), createdAtMs));
    }

    private static String streamCacheKey(
            StremioStreamSourceStore.Source source,
            String type,
            String id) {
        return source.id + '|'
                + StremioManifestCache.fingerprint(source.manifestUrl)
                + '|' + type + '|' + id;
    }

    private static List<JSONObject> copyStreams(List<JSONObject> streams) {
        List<JSONObject> copied = new ArrayList<>();
        for (JSONObject stream : streams) {
            try {
                copied.add(new JSONObject(stream.toString()));
            } catch (JSONException ignored) {
                // A parsed object can only fail here if it was concurrently corrupted.
            }
        }
        return copied;
    }

    /** Applies the same manifest resource/type/id-prefix routing described by the Stremio SDK. */
    static String streamSupport(JSONObject manifest, String type, String id) {
        if (manifest == null || type == null || id == null) {
            return "invalid_request";
        }
        JSONArray resources = manifest.optJSONArray("resources");
        if (resources == null) {
            return "missing_stream_resource";
        }
        boolean foundStream = false;
        boolean foundType = false;
        for (int index = 0; index < resources.length(); index++) {
            Object raw = resources.opt(index);
            JSONArray types;
            JSONArray idPrefixes;
            boolean restrictIds;
            if (raw instanceof String) {
                if (!"stream".equals(raw)) {
                    continue;
                }
                foundStream = true;
                types = manifest.optJSONArray("types");
                restrictIds = manifest.has("idPrefixes");
                idPrefixes = manifest.optJSONArray("idPrefixes");
            } else if (raw instanceof JSONObject) {
                JSONObject resource = (JSONObject) raw;
                if (!"stream".equals(resource.optString("name", ""))) {
                    continue;
                }
                foundStream = true;
                types = resource.optJSONArray("types");
                restrictIds = resource.has("idPrefixes");
                idPrefixes = resource.optJSONArray("idPrefixes");
            } else {
                continue;
            }
            if (!contains(types, type)) {
                continue;
            }
            foundType = true;
            if (!restrictIds || startsWithAny(id, idPrefixes)) {
                return "supported";
            }
        }
        if (!foundStream) {
            return "missing_stream_resource";
        }
        return foundType ? "unsupported_id" : "unsupported_type";
    }

    private static boolean contains(@Nullable JSONArray values, String expected) {
        if (values == null) {
            return false;
        }
        for (int index = 0; index < values.length(); index++) {
            if (expected.equals(values.optString(index, null))) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String id, @Nullable JSONArray prefixes) {
        if (prefixes == null) {
            return false;
        }
        for (int index = 0; index < prefixes.length(); index++) {
            String prefix = prefixes.optString(index, null);
            if (prefix != null && id.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    static final class ManifestResult {
        final boolean success;
        @NonNull final String name;
        @NonNull final String state;

        ManifestResult(boolean success, String name, String state) {
            this.success = success;
            this.name = name;
            this.state = state;
        }
    }

    private static final class ManifestDocument {
        @Nullable final JSONObject manifest;
        @NonNull final String state;
        @Nullable final String etag;
        @Nullable final String lastModified;

        ManifestDocument(@Nullable JSONObject manifest, String state) {
            this(manifest, state, null, null);
        }

        ManifestDocument(
                @Nullable JSONObject manifest,
                String state,
                @Nullable String etag,
                @Nullable String lastModified) {
            this.manifest = manifest;
            this.state = state;
            this.etag = etag;
            this.lastModified = lastModified;
        }
    }

    private static final class StreamCacheEntry {
        @NonNull final List<JSONObject> streams;
        final long createdAtMs;

        StreamCacheEntry(List<JSONObject> streams, long createdAtMs) {
            this.streams = streams;
            this.createdAtMs = createdAtMs;
        }
    }

    static final class StreamResult {
        @NonNull final StremioStreamSourceStore.Source source;
        @NonNull final List<JSONObject> streams;
        @NonNull final String state;
        final long durationMs;

        StreamResult(StremioStreamSourceStore.Source source,
                     List<JSONObject> streams,
                     String state,
                     long durationMs) {
            this.source = source;
            this.streams = streams;
            this.state = state;
            this.durationMs = durationMs;
        }
    }
}
