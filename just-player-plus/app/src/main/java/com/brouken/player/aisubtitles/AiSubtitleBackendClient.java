package com.brouken.player.aisubtitles;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Implements the fixed JustPlayer Plus translation API, including bounded polling. */
public final class AiSubtitleBackendClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    static final long POLL_INTERVAL_MS = 2_000L;
    static final long MAX_POLL_DURATION_MS = TimeUnit.MINUTES.toMillis(5L);
    private static final int MAX_RESPONSE_BYTES = 3 * 1024 * 1024;

    public enum Failure {
        TIMEOUT,
        UNAVAILABLE,
        TRANSLATION_FAILED
    }

    public interface Listener {
        void onProgress(int progress);
        void onReady(AiSubtitleResult result);
        void onFailure(Failure failure, @Nullable String message);
    }

    private final OkHttpClient httpClient;
    private final HttpUrl baseUrl;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private Call activeCall;
    @Nullable private Listener listener;
    @Nullable private String targetLanguage;
    private long deadlineMs;
    private long operationToken;
    private boolean released;

    public AiSubtitleBackendClient(OkHttpClient httpClient, String backendUrl) {
        this.httpClient = httpClient;
        this.baseUrl = requireBaseUrl(backendUrl);
    }

    public static boolean isValidBackendUrl(@Nullable String value) {
        try {
            requireBaseUrl(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public void start(AiSubtitleJob job, Listener listener) {
        if (released) {
            return;
        }
        cancel();
        long token = operationToken;
        this.listener = listener;
        targetLanguage = job.targetLanguage;
        deadlineMs = SystemClock.elapsedRealtime() + MAX_POLL_DURATION_MS;

        JSONObject body = new JSONObject();
        try {
            body.put("subtitleText", job.subtitleText);
            body.put("sourceFormat", job.sourceFormat);
            putNullable(body, "sourceLanguage", job.sourceLanguage);
            body.put("targetLanguage", job.targetLanguage);
            putNullable(body, "title", job.title);
            putNullable(body, "contentType", job.contentType);
            putNullable(body, "contentId", job.contentId);
            putNullable(body, "season", job.season);
            putNullable(body, "episode", job.episode);
            body.put("client", "justplayer-plus");
            body.put("clientVersion", job.clientVersion);
        } catch (JSONException error) {
            fail(token, Failure.TRANSLATION_FAILED, null);
            return;
        }

        Request request = new Request.Builder()
                .url(endpoint("v1", "translations"))
                .post(RequestBody.create(body.toString(), JSON))
                .header("Accept", "application/json")
                .build();
        execute(request, true, token);
    }

    public void cancel() {
        operationToken++;
        handler.removeCallbacksAndMessages(null);
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
        listener = null;
        targetLanguage = null;
    }

    public void release() {
        released = true;
        cancel();
    }

    private void execute(Request request, boolean initial, long token) {
        if (!isCurrent(token)) {
            return;
        }
        activeCall = httpClient.newCall(request);
        activeCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                handler.post(() -> {
                    if (!call.isCanceled()) {
                        fail(token, Failure.UNAVAILABLE, null);
                    }
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    ResponseBody responseBody = closeable.body();
                    if (responseBody == null
                            || responseBody.contentLength() > MAX_RESPONSE_BYTES
                            || initial && closeable.code() != 200 && closeable.code() != 202
                            || !initial && closeable.code() != 200) {
                        handler.post(() -> fail(token, Failure.UNAVAILABLE, null));
                        return;
                    }
                    String json = responseBody.string();
                    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            > MAX_RESPONSE_BYTES) {
                        handler.post(() -> fail(token, Failure.TRANSLATION_FAILED, null));
                        return;
                    }
                    JSONObject payload = new JSONObject(json);
                    handler.post(() -> handlePayload(token, payload));
                } catch (IOException | JSONException error) {
                    handler.post(() -> fail(token, Failure.TRANSLATION_FAILED, null));
                }
            }
        });
    }

    private void handlePayload(long token, JSONObject payload) {
        if (!isCurrent(token)) {
            return;
        }
        String status = payload.optString("status", "");
        if ("ready".equals(status)) {
            AiSubtitleResult result = parseReady(payload, targetLanguage);
            if (result == null) {
                fail(token, Failure.TRANSLATION_FAILED, null);
                return;
            }
            Listener callback = listener;
            listener = null;
            callback.onReady(result);
            return;
        }
        if ("failed".equals(status)) {
            fail(token, Failure.TRANSLATION_FAILED, payload.optString("message", null));
            return;
        }
        if (!"pending".equals(status)) {
            fail(token, Failure.TRANSLATION_FAILED, null);
            return;
        }
        listener.onProgress(Math.max(0, Math.min(100, payload.optInt("progress", 0))));
        String jobId = payload.optString("jobId", "").trim();
        if (jobId.isEmpty() || SystemClock.elapsedRealtime() >= deadlineMs) {
            fail(token, Failure.TIMEOUT, null);
            return;
        }
        handler.postDelayed(() -> poll(token, jobId), POLL_INTERVAL_MS);
    }

    private void poll(long token, String jobId) {
        if (!isCurrent(token)) {
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadlineMs) {
            fail(token, Failure.TIMEOUT, null);
            return;
        }
        Request request = new Request.Builder()
                .url(endpoint("v1", "translations", jobId))
                .get()
                .header("Accept", "application/json")
                .build();
        execute(request, false, token);
    }

    private void fail(long token, Failure failure, @Nullable String message) {
        if (!isCurrent(token)) {
            return;
        }
        Listener callback = listener;
        cancel();
        callback.onFailure(failure, message);
    }

    private boolean isCurrent(long token) {
        return !released && listener != null && token == operationToken;
    }

    private HttpUrl endpoint(String... segments) {
        HttpUrl.Builder builder = baseUrl.newBuilder();
        for (String segment : segments) {
            builder.addPathSegment(segment);
        }
        return builder.build();
    }

    private static HttpUrl requireBaseUrl(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty backend URL");
        }
        HttpUrl url;
        try {
            url = new Request.Builder().url(value.trim()).build().url();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid backend URL", error);
        }
        if (!("http".equals(url.scheme()) || "https".equals(url.scheme()))
                || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("Unsupported backend URL");
        }
        return url;
    }

    @Nullable
    static AiSubtitleResult parseReady(JSONObject payload, @Nullable String targetLanguage) {
        String cacheKey = payload.optString("cacheKey", "").trim();
        String outputFormat = payload.optString("outputFormat", "").trim();
        String language = payload.optString("language", "").trim();
        String label = payload.optString("label", "").trim();
        String subtitleText = payload.optString("subtitleText", "");
        if (!cacheKey.matches("[A-Za-z0-9_-]{8,128}")
                || !"srt".equals(outputFormat)
                || targetLanguage == null || !targetLanguage.equals(language)
                || label.isEmpty() || subtitleText.trim().isEmpty()
                || subtitleText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > AiSubtitleSourceLoader.MAX_INPUT_BYTES) {
            return null;
        }
        return new AiSubtitleResult(
                cacheKey, outputFormat, language, label, subtitleText,
                payload.optBoolean("cached", false));
    }

    private static void putNullable(JSONObject object, String key, @Nullable Object value)
            throws JSONException {
        object.put(key, value == null ? JSONObject.NULL : value);
    }
}
