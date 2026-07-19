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

/** Resolves IMDb/Cinemeta episode names and artwork; gracefully falls back to observed IDs. */
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

    void resolve(StremioConnectorStore.Pair pair, Listener listener) {
        if (!IMDB_ID.matcher(pair.current.metaId).matches()) {
            listener.onResolved(generic(pair));
            return;
        }
        Request request = new Request.Builder()
                .url(String.format(java.util.Locale.US, CINEMETA, pair.current.metaId))
                .header("Accept", "application/json")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                listener.onResolved(generic(pair));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    ResponseBody body = closeable.body();
                    if (!closeable.isSuccessful() || body == null) {
                        listener.onResolved(generic(pair));
                        return;
                    }
                    listener.onResolved(parse(pair, body.string(), System.currentTimeMillis()));
                } catch (IOException | JSONException error) {
                    listener.onResolved(generic(pair));
                }
            }
        });
    }

    private static NextEpisodeInfo generic(StremioConnectorStore.Pair pair) {
        return new NextEpisodeInfo(pair.current, pair.next, null, null, null);
    }

    @Nullable
    static NextEpisodeInfo parse(StremioConnectorStore.Pair pair, String json, long nowMs)
            throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject meta = root.optJSONObject("meta");
        if (meta == null) {
            return generic(pair);
        }
        String seriesTitle = meta.optString("name", null);
        String fallbackArtwork = firstNonEmpty(
                meta.optString("background", null), meta.optString("poster", null));
        JSONArray videos = meta.optJSONArray("videos");
        if (videos == null) {
            return new NextEpisodeInfo(
                    pair.current, pair.next, seriesTitle, null, fallbackArtwork);
        }
        for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video == null || !pair.next.raw.equals(video.optString("id"))) {
                continue;
            }
            if (isUpcoming(video.optString("released", null), nowMs)) {
                return null;
            }
            String artwork = firstNonEmpty(
                    video.optString("thumbnail", null), fallbackArtwork);
            return new NextEpisodeInfo(
                    pair.current,
                    pair.next,
                    seriesTitle,
                    video.optString("title", null),
                    artwork);
        }
        // The connector observed the exact next id even if Cinemeta is temporarily incomplete.
        return new NextEpisodeInfo(
                pair.current, pair.next, seriesTitle, null, fallbackArtwork);
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
