package com.brouken.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Resolves the next released IMDb/Cinemeta episode, its name, and artwork. */
final class NextEpisodeMetadataResolver {
    private static final Pattern IMDB_ID = Pattern.compile("tt\\d+");
    private static final String CINEMETA =
            "https://v3-cinemeta.strem.io/meta/series/%s.json";

    interface Listener {
        void onResolved(@Nullable NextEpisodeInfo info);
    }

    private final OkHttpClient client;

    NextEpisodeMetadataResolver(OkHttpClient client) {
        this.client = client;
    }

    void resolve(StremioEpisodeId current, Listener listener) {
        if (!IMDB_ID.matcher(current.metaId).matches()) {
            listener.onResolved(null);
            return;
        }
        Request request = new Request.Builder()
                .url(String.format(java.util.Locale.US, CINEMETA, current.metaId))
                .header("Accept", "application/json")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                listener.onResolved(null);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    ResponseBody body = closeable.body();
                    if (!closeable.isSuccessful() || body == null) {
                        listener.onResolved(null);
                        return;
                    }
                    listener.onResolved(parse(current, body.string(), System.currentTimeMillis()));
                } catch (IOException | JSONException error) {
                    listener.onResolved(null);
                }
            }
        });
    }

    /** Derives the next released episode when Stremio observed only the current stream. */
    @Nullable
    static NextEpisodeInfo parse(StremioEpisodeId current, String json, long nowMs)
            throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject meta = root.optJSONObject("meta");
        if (meta == null) {
            return null;
        }
        JSONArray videos = meta.optJSONArray("videos");
        if (videos == null) {
            return null;
        }

        StremioEpisodeId next = null;
        JSONObject nextVideo = null;
        for (int index = 0; index < videos.length(); index++) {
            JSONObject video = videos.optJSONObject(index);
            if (video == null) {
                continue;
            }
            StremioEpisodeId candidate = StremioEpisodeId.parse(video.optString("id", null));
            if (candidate == null
                    || !current.belongsToSameSeries(candidate)
                    || !isAfter(current, candidate)
                    || next != null && !isAfter(candidate, next)) {
                continue;
            }
            next = candidate;
            nextVideo = video;
        }
        if (next == null || nextVideo == null
                || isUpcoming(nextVideo.optString("released", null), nowMs)) {
            return null;
        }

        String fallbackArtwork = firstNonEmpty(
                meta.optString("background", null), meta.optString("poster", null));
        String artwork = firstNonEmpty(
                nextVideo.optString("thumbnail", null), fallbackArtwork);
        return new NextEpisodeInfo(
                current,
                next,
                meta.optString("name", null),
                firstNonEmpty(
                        nextVideo.optString("title", null),
                        nextVideo.optString("name", null)),
                artwork);
    }

    private static boolean isAfter(StremioEpisodeId current, StremioEpisodeId candidate) {
        return candidate.season > current.season
                || candidate.season == current.season && candidate.episode > current.episode;
    }

    private static boolean isUpcoming(String released, long nowMs) {
        if (released == null || released.isEmpty()) {
            return false;
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd"
        };
        for (String pattern : patterns) {
            SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
            parser.setLenient(false);
            parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                Date date = parser.parse(released);
                return date != null && date.getTime() > nowMs;
            } catch (ParseException ignored) {
            }
        }
        return false;
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : second;
    }
}
