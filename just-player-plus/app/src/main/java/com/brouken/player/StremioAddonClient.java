package com.brouken.player;

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
    private static final int MAX_MANIFEST_BYTES = 1 * 1024 * 1024;
    private static final int MAX_STREAM_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_STREAMS_PER_RESPONSE = 500;

    private final OkHttpClient httpClient;

    StremioAddonClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    static OkHttpClient newHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    ManifestResult inspectManifest(String manifestUrl) {
        HttpUrl url = parseManifestUrl(manifestUrl);
        if (url == null) {
            return new ManifestResult(false, "", "invalid_url");
        }
        Request request = request(url);
        Call call = httpClient.newCall(request);
        call.timeout().timeout(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                return new ManifestResult(false, "", "http_" + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                return new ManifestResult(false, "", "invalid_body");
            }
            JSONObject manifest = new JSONObject(
                    BoundedResponseBody.readUtf8(body, MAX_MANIFEST_BYTES));
            if (!hasStreamResource(manifest)) {
                return new ManifestResult(false, "", "missing_stream_resource");
            }
            String name = manifest.optString("name", "").trim();
            if (name.length() > 120) {
                name = name.substring(0, 120);
            }
            return new ManifestResult(true, name, "loaded");
        } catch (InterruptedIOException error) {
            return new ManifestResult(false, "", "timeout");
        } catch (BoundedResponseBody.ResponseTooLargeException error) {
            return new ManifestResult(false, "", "response_too_large");
        } catch (IOException | JSONException | RuntimeException error) {
            return new ManifestResult(false, "", "invalid_response");
        }
    }

    StreamResult loadStreams(StremioStreamSourceStore.Source source,
                             String type,
                             String id) {
        return loadStreams(source, type, id, null);
    }

    StreamResult loadStreams(StremioStreamSourceStore.Source source,
                             String type,
                             String id,
                             @Nullable StremioRequestCancellation cancellation) {
        long startedAt = System.currentTimeMillis();
        HttpUrl streamUrl = buildStreamUrl(source.manifestUrl, type, id);
        if (streamUrl == null) {
            return new StreamResult(source, Collections.emptyList(),
                    "invalid_url", elapsed(startedAt));
        }
        Request request = request(streamUrl);
        Call call = httpClient.newCall(request);
        call.timeout().timeout(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
            return new StreamResult(source, parsed, "loaded", elapsed(startedAt));
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
        return new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayer Plus Connector")
                .build();
    }

    private static boolean hasStreamResource(JSONObject manifest) {
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
