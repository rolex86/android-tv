package com.brouken.player;

import androidx.annotation.NonNull;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Performs the bounded OpenSubtitles listing while Stremio is still resolving its add-ons. */
final class StremioConnectorOpenSubtitles {
    static final long LOOKUP_TIMEOUT_MS = 1_500L;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final OkHttpClient httpClient;

    StremioConnectorOpenSubtitles(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    static OkHttpClient newHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    Result load(StremioSubtitleRequest request, String[] preferredLanguages) {
        if (preferredLanguages == null || preferredLanguages.length == 0) {
            return new Result(new ArrayList<>(), "no_languages");
        }
        Request httpRequest = new Request.Builder()
                .url(OpenSubtitlesV3Client.genericUrl(
                        request.type, request.videoId, request.filename))
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayer Plus Connector")
                .build();
        Call call = httpClient.newCall(httpRequest);
        call.timeout().timeout(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                return new Result(new ArrayList<>(), "http_" + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                return new Result(new ArrayList<>(), "invalid_body");
            }
            String json = BoundedResponseBody.readUtf8(body, MAX_RESPONSE_BYTES);
            return new Result(
                    OpenSubtitlesV3Client.parseCandidates(json, preferredLanguages),
                    "loaded");
        } catch (java.io.InterruptedIOException timeout) {
            return new Result(new ArrayList<>(), "timeout");
        } catch (BoundedResponseBody.ResponseTooLargeException error) {
            return new Result(new ArrayList<>(), "response_too_large");
        } catch (IOException | JSONException | RuntimeException error) {
            return new Result(new ArrayList<>(), "invalid_response");
        }
    }

    static final class Result {
        @NonNull final List<OpenSubtitlesV3Client.Candidate> candidates;
        @NonNull final String state;

        Result(@NonNull List<OpenSubtitlesV3Client.Candidate> candidates,
               @NonNull String state) {
            this.candidates = candidates;
            this.state = state;
        }
    }
}
