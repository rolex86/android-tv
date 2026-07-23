package com.brouken.player.aisubtitles;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/** Implements the JustPlayer Plus translation API, including auth, polling and cancellation. */
public final class AiSubtitleBackendClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    static final long POLL_INTERVAL_MS = 2_000L;
    static final long MAX_POLL_DURATION_MS = TimeUnit.HOURS.toMillis(1L);
    private static final int MAX_RESPONSE_BYTES = 3 * 1024 * 1024;
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final int MAX_LABEL_LENGTH = 120;
    private static final OkHttpClient CANCELLATION_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build();

    public enum Failure {
        TIMEOUT,
        UNAVAILABLE,
        UNAUTHORIZED,
        INVALID_REQUEST,
        TOO_LARGE,
        TRANSLATION_FAILED
    }

    public interface Listener {
        void onProgress(int progress);
        void onReady(AiSubtitleResult result);
        void onFailure(Failure failure, @Nullable String message);
    }

    private final OkHttpClient httpClient;
    private final HttpUrl baseUrl;
    private final String apiToken;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private Call activeCall;
    @Nullable private Listener listener;
    @Nullable private String targetLanguage;
    @Nullable private String activeJobId;
    private long deadlineMs;
    private long operationToken;
    private int lastProgress = -1;
    private boolean released;

    public AiSubtitleBackendClient(OkHttpClient httpClient,
                                   String backendUrl,
                                   @Nullable String apiToken) {
        this.httpClient = httpClient;
        this.baseUrl = requireBaseUrl(backendUrl);
        this.apiToken = normalizeApiToken(apiToken);
    }

    public static boolean isValidBackendUrl(@Nullable String value) {
        try {
            requireBaseUrl(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static boolean isValidApiToken(@Nullable String value) {
        try {
            normalizeApiToken(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public void start(AiSubtitleJob job, Listener listener) {
        if (released) {
            return;
        }
        detach();
        long token = operationToken;
        this.listener = listener;
        targetLanguage = job.targetLanguage;
        deadlineMs = SystemClock.elapsedRealtime() + MAX_POLL_DURATION_MS;
        lastProgress = -1;

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

        Request.Builder builder = new Request.Builder()
                .url(endpoint("v1", "translations"))
                .post(RequestBody.create(body.toString(), JSON));
        execute(withCommonHeaders(builder).build(), true, token);
    }

    /** Cancels locally and asks the backend to abort a submitted job when possible. */
    public void cancel() {
        String jobId = activeJobId;
        detach();
        if (jobId != null) {
            cancelRemoteJob(jobId);
        }
    }

    /** Stops polling without cancelling the server-side job. */
    public void detach() {
        operationToken++;
        handler.removeCallbacksAndMessages(null);
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
        activeJobId = null;
        listener = null;
        targetLanguage = null;
        lastProgress = -1;
    }

    public void release() {
        if (released) {
            return;
        }
        detach();
        released = true;
    }

    private void execute(Request request, boolean initial, long token) {
        if (!isCurrent(token)) {
            return;
        }
        Call call = httpClient.newCall(request);
        activeCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call failedCall, @NonNull IOException error) {
                handler.post(() -> {
                    if (!failedCall.isCanceled()) {
                        fail(token, Failure.UNAVAILABLE, null);
                    }
                });
            }

            @Override
            public void onResponse(@NonNull Call responseCall, @NonNull Response response) {
                try (Response closeable = response) {
                    int code = closeable.code();
                    Failure httpFailure = mapHttpFailure(code, initial);
                    if (httpFailure != null) {
                        handler.post(() -> fail(token, httpFailure, null));
                        return;
                    }
                    ResponseBody responseBody = closeable.body();
                    if (responseBody == null || responseBody.contentLength() > MAX_RESPONSE_BYTES) {
                        handler.post(() -> fail(token, Failure.TRANSLATION_FAILED, null));
                        return;
                    }
                    String json = responseBody.string();
                    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
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
            clearCompletedState();
            callback.onReady(result);
            return;
        }
        if ("failed".equals(status)) {
            String errorCode = payload.optString("errorCode", "");
            Failure failure = "TRANSLATION_TIMEOUT".equals(errorCode)
                    ? Failure.TIMEOUT : Failure.TRANSLATION_FAILED;
            fail(token, failure, payload.optString("message", null));
            return;
        }
        if ("cancelled".equals(status)) {
            fail(token, Failure.TRANSLATION_FAILED, null);
            return;
        }
        if (!"pending".equals(status)) {
            fail(token, Failure.TRANSLATION_FAILED, null);
            return;
        }

        String jobId = payload.optString("jobId", "").trim();
        if (!jobId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || SystemClock.elapsedRealtime() >= deadlineMs) {
            fail(token, Failure.TIMEOUT, null);
            return;
        }
        activeJobId = jobId;
        int progress = Math.max(0, Math.min(100, payload.optInt("progress", 0)));
        if (progress != lastProgress) {
            lastProgress = progress;
            listener.onProgress(progress);
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
        Request.Builder builder = new Request.Builder()
                .url(endpoint("v1", "translations", jobId))
                .get();
        execute(withCommonHeaders(builder).build(), false, token);
    }

    private void cancelRemoteJob(String jobId) {
        Request.Builder builder = new Request.Builder()
                .url(endpoint("v1", "translations", jobId))
                .delete();
        CANCELLATION_CLIENT.newCall(withCommonHeaders(builder).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                // Best effort only. The local operation is already cancelled.
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                response.close();
            }
        });
    }

    private Request.Builder withCommonHeaders(Request.Builder builder) {
        builder.header("Accept", "application/json");
        if (!apiToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiToken);
        }
        return builder;
    }

    private void fail(long token, Failure failure, @Nullable String message) {
        if (!isCurrent(token)) {
            return;
        }
        Listener callback = listener;
        clearCompletedState();
        callback.onFailure(failure, message);
    }

    private void clearCompletedState() {
        handler.removeCallbacksAndMessages(null);
        activeCall = null;
        listener = null;
        targetLanguage = null;
        activeJobId = null;
        lastProgress = -1;
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

    @Nullable
    static Failure mapHttpFailure(int statusCode, boolean initial) {
        if (statusCode == 401 || statusCode == 403) {
            return Failure.UNAUTHORIZED;
        }
        if (statusCode == 413) {
            return Failure.TOO_LARGE;
        }
        if (statusCode == 400 || statusCode == 422) {
            return Failure.INVALID_REQUEST;
        }
        if (statusCode >= 500) {
            return Failure.UNAVAILABLE;
        }
        if (initial ? statusCode != 200 && statusCode != 202 : statusCode != 200) {
            return Failure.TRANSLATION_FAILED;
        }
        return null;
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
                || url.query() != null || url.fragment() != null
                || !(url.encodedPath().isEmpty() || "/".equals(url.encodedPath()))) {
            throw new IllegalArgumentException("Unsupported backend URL");
        }
        return url.newBuilder().encodedPath("/").build();
    }

    private static String normalizeApiToken(@Nullable String value) {
        String token = value == null ? "" : value.trim();
        if (token.length() > MAX_TOKEN_LENGTH
                || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid API token");
        }
        return token;
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
                || label.isEmpty() || label.length() > MAX_LABEL_LENGTH
                || label.indexOf('\r') >= 0 || label.indexOf('\n') >= 0
                || subtitleText.trim().isEmpty() || !subtitleText.contains(" --> ")
                || subtitleText.getBytes(StandardCharsets.UTF_8).length
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
