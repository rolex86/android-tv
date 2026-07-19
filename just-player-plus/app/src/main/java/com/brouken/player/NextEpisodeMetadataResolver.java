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

/** Resolves IMDb/Cinemeta display metadata and the next released series episode. */
final class NextEpisodeMetadataResolver {
    private static final Pattern IMDB_ID = Pattern.compile("tt\\d+");
    private static final String CINEMETA_SERIES =
            "https://v3-cinemeta.strem.io/meta/series/%s.json";
    private static final String CINEMETA_MOVIE =
            "https://v3-cinemeta.strem.io/meta/movie/%s.json";

    interface Listener {
        void onResolved(@Nullable Result result);
    }

    interface MovieTitleListener {
        void onResolved(@Nullable String title);
    }

    static final class Result {
        final StremioEpisodeId current;
        @Nullable final String seriesTitle;
        @Nullable final String currentEpisodeTitle;
        @Nullable final NextEpisodeInfo nextEpisode;

        Result(StremioEpisodeId current,
               @Nullable String seriesTitle,
               @Nullable String currentEpisodeTitle,
               @Nullable NextEpisodeInfo nextEpisode) {
            this.current = current;
            this.seriesTitle = emptyToNull(seriesTitle);
            this.currentEpisodeTitle = emptyToNull(currentEpisodeTitle);
            this.nextEpisode = nextEpisode;
        }
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
                .url(String.format(java.util.Locale.US, CINEMETA_SERIES, current.metaId))
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

    /** Resolves a movie title once at launch; no playback-position polling is involved. */
    void resolveMovieTitle(String movieId, MovieTitleListener listener) {
        if (movieId == null || !IMDB_ID.matcher(movieId).matches()) {
            listener.onResolved(null);
            return;
        }
        Request request = new Request.Builder()
                .url(String.format(java.util.Locale.US, CINEMETA_MOVIE, movieId))
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
                    listener.onResolved(parseMovieTitle(body.string()));
                } catch (IOException | JSONException error) {
                    listener.onResolved(null);
                }
            }
        });
    }

    @Nullable
    static String parseMovieTitle(String json) throws JSONException {
        JSONObject meta = new JSONObject(json).optJSONObject("meta");
        if (meta == null) {
            return null;
        }
        return emptyToNull(firstNonEmpty(
                meta.optString("name", null), meta.optString("title", null)));
    }

    /** Derives the next released episode when Stremio observed only the current stream. */
    @Nullable
    static Result parse(StremioEpisodeId current, String json, long nowMs)
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
        String currentEpisodeTitle = null;
        for (int index = 0; index < videos.length(); index++) {
            JSONObject video = videos.optJSONObject(index);
            if (video == null) {
                continue;
            }
            StremioEpisodeId candidate = StremioEpisodeId.parse(video.optString("id", null));
            if (current.equals(candidate)) {
                currentEpisodeTitle = firstNonEmpty(
                        video.optString("title", null), video.optString("name", null));
                continue;
            }
            if (candidate == null
                    || !current.belongsToSameSeries(candidate)
                    || !isAfter(current, candidate)
                    || next != null && !isAfter(candidate, next)) {
                continue;
            }
            next = candidate;
            nextVideo = video;
        }
        String seriesTitle = meta.optString("name", null);
        NextEpisodeInfo nextEpisode = null;
        if (next != null && nextVideo != null
                && !isUpcoming(nextVideo.optString("released", null), nowMs)) {
            String fallbackArtwork = firstNonEmpty(
                    meta.optString("background", null), meta.optString("poster", null));
            String artwork = firstNonEmpty(
                    nextVideo.optString("thumbnail", null), fallbackArtwork);
            nextEpisode = new NextEpisodeInfo(
                    current,
                    next,
                    seriesTitle,
                    firstNonEmpty(
                            nextVideo.optString("title", null),
                            nextVideo.optString("name", null)),
                    artwork);
        }
        return new Result(current, seriesTitle, currentEpisodeTitle, nextEpisode);
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

    private static String emptyToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
