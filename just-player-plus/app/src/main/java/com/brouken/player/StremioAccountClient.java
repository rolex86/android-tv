package com.brouken.player;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Minimal Stremio account API client used only by the optional sync gate. */
final class StremioAccountClient {
    private static final String API_BASE = "https://api.strem.io/api/";
    private static final String LINK_BASE = "https://link.stremio.com/api/v2/";
    private static final String CINEMETA_BASE =
            "https://v3-cinemeta.strem.io/meta/series/";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern RFC3339_TIMESTAMP = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})"
                    + "(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})$");

    static final class Link {
        final String code;
        final String url;

        Link(String code, String url) {
            this.code = code;
            this.url = url;
        }
    }

    static final class SyncResult {
        final boolean success;
        final String reason;

        SyncResult(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }
    }

    static final class ApiException extends IOException {
        final int apiCode;

        ApiException(int apiCode, String message) {
            super(message == null ? "Stremio API error" : message);
            this.apiCode = apiCode;
        }
    }

    private final OkHttpClient httpClient;
    private final Object accountWriteLock = new Object();
    private boolean accountWritesAllowed = true;
    @Nullable
    private Call activeAccountWrite;

    StremioAccountClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    Link createLink() throws IOException, JSONException {
        JSONObject result = requireObject(apiResult(getJson(
                LINK_BASE + "create?type=Create")));
        String code = result.optString("code", "").trim();
        String url = result.optString("link", "").trim();
        if (code.isEmpty() || code.length() > 128 || !isApprovedLinkUrl(url)) {
            throw new IOException("Stremio link response is incomplete");
        }
        return new Link(code, url);
    }

    @Nullable
    String readLink(String code) throws IOException, JSONException {
        JSONObject response = getJson(LINK_BASE + "read?type=Read&code="
                + java.net.URLEncoder.encode(code, "UTF-8"));
        JSONObject error = response.optJSONObject("error");
        if (error != null) {
            int apiCode = error.optInt("code", -1);
            if (apiCode == 101) return null;
            throw new ApiException(apiCode, error.optString("message", "Link failed"));
        }
        JSONObject result = response.optJSONObject("result");
        String authKey = result == null ? "" : result.optString("authKey", "").trim();
        return authKey.isEmpty() ? null : authKey;
    }

    boolean validateAuthKey(String authKey) throws IOException, JSONException {
        Object result = apiResult(postJson(
                "datastoreGet",
                new JSONObject()
                        .put("authKey", authKey)
                        .put("collection", "libraryItem")
                        .put("ids", new JSONArray())
                        .put("all", true)));
        return result instanceof JSONArray;
    }

    SyncResult sync(
            String authKey,
            StremioLibraryItemPatch.Checkpoint checkpoint) {
        try {
            JSONObject before = getLibraryItem(authKey, checkpoint.episode.metaId);
            JSONObject meta = getJson(CINEMETA_BASE
                    + java.net.URLEncoder.encode(checkpoint.episode.metaId, "UTF-8")
                    + ".json");
            List<String> videoIds = StremioWatchedBitfield.orderedVideoIds(meta);
            String capturedTimestamp = isoTimestamp(checkpoint.capturedAtMs);
            if (isNewerPlaybackState(before, checkpoint.capturedAtMs)) {
                return new SyncResult(true, "newer_server_state_kept");
            }
            JSONObject intended = StremioLibraryItemPatch.build(
                    before,
                    checkpoint,
                    videoIds,
                    capturedTimestamp,
                    isoTimestamp(System.currentTimeMillis()));

            JSONObject latest = getLibraryItem(authKey, checkpoint.episode.metaId);
            if (!StremioLibraryItemPatch.jsonEquals(before, latest)) {
                return new SyncResult(false, "concurrent_change");
            }
            putLibraryItem(authKey, intended);
            JSONObject after = getLibraryItem(authKey, checkpoint.episode.metaId);
            if (!StremioLibraryItemPatch.verify(
                    before, intended, after, checkpoint, videoIds)) {
                return new SyncResult(false, "verification_failed");
            }
            return new SyncResult(true, checkpoint.completed
                    ? "watched_synced" : "progress_synced");
        } catch (ApiException error) {
            return new SyncResult(false, "api_" + error.apiCode);
        } catch (IOException | JSONException | RuntimeException error) {
            return new SyncResult(false, error.getClass().getSimpleName());
        }
    }

    void cancelAll() {
        httpClient.dispatcher().cancelAll();
    }

    void allowAccountWrites() {
        synchronized (accountWriteLock) {
            accountWritesAllowed = true;
        }
    }

    void blockAccountWritesAndCancel() {
        synchronized (accountWriteLock) {
            accountWritesAllowed = false;
            if (activeAccountWrite != null) activeAccountWrite.cancel();
        }
        cancelAll();
    }

    private JSONObject getLibraryItem(String authKey, String metaId)
            throws IOException, JSONException {
        JSONArray result = requireArray(apiResult(postJson(
                "datastoreGet",
                new JSONObject()
                        .put("authKey", authKey)
                        .put("collection", "libraryItem")
                        .put("ids", new JSONArray().put(metaId))
                        .put("all", false))));
        if (result.length() != 1 || result.optJSONObject(0) == null) {
            throw new IOException("Series is absent from the Stremio library");
        }
        JSONObject item = result.getJSONObject(0);
        if (!metaId.equals(item.optString("_id", ""))
                || !"series".equals(item.optString("type", ""))) {
            throw new IOException("Unexpected Stremio library item");
        }
        return item;
    }

    private void putLibraryItem(String authKey, JSONObject item)
            throws IOException, JSONException {
        JSONObject body = new JSONObject()
                .put("authKey", authKey)
                .put("collection", "libraryItem")
                .put("changes", new JSONArray().put(item));
        Request request = new Request.Builder()
                .url(API_BASE + "datastorePut")
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayerPlus-StremioSync/1.0")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        JSONObject result = requireObject(apiResult(executeAccountWrite(request)));
        if (!result.optBoolean("success", false)) {
            throw new IOException("Stremio did not confirm datastorePut");
        }
    }

    private JSONObject getJson(String url) throws IOException, JSONException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayerPlus-StremioSync/1.0")
                .build();
        return execute(request);
    }

    private JSONObject postJson(String path, JSONObject body)
            throws IOException, JSONException {
        Request request = new Request.Builder()
                .url(API_BASE + path)
                .header("Accept", "application/json")
                .header("User-Agent", "JustPlayerPlus-StremioSync/1.0")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        return execute(request);
    }

    private JSONObject execute(Request request) throws IOException, JSONException {
        try (Response response = httpClient.newCall(request).execute()) {
            return readResponse(response);
        }
    }

    private JSONObject executeAccountWrite(Request request) throws IOException, JSONException {
        Call call;
        synchronized (accountWriteLock) {
            if (!accountWritesAllowed) {
                throw new IOException("Stremio account sync is disabled");
            }
            call = httpClient.newCall(request);
            activeAccountWrite = call;
        }
        try (Response response = call.execute()) {
            return readResponse(response);
        } finally {
            synchronized (accountWriteLock) {
                if (activeAccountWrite == call) activeAccountWrite = null;
            }
        }
    }

    private static JSONObject readResponse(Response response)
            throws IOException, JSONException {
        ResponseBody body = response.body();
        if (body == null) throw new IOException("Empty Stremio response");
        String encoded = BoundedResponseBody.readUtf8(body, MAX_RESPONSE_BYTES);
        if (!response.isSuccessful()) {
            throw new IOException("Stremio HTTP " + response.code());
        }
        return new JSONObject(encoded);
    }

    private static Object apiResult(JSONObject response) throws ApiException, IOException {
        JSONObject error = response.optJSONObject("error");
        if (error != null) {
            throw new ApiException(
                    error.optInt("code", -1),
                    error.optString("message", "Stremio API error"));
        }
        if (!response.has("result")) {
            throw new IOException("Stremio response has no result");
        }
        return response.opt("result");
    }

    private static JSONObject requireObject(Object value) throws IOException {
        if (!(value instanceof JSONObject)) throw new IOException("Expected JSON object");
        return (JSONObject) value;
    }

    private static JSONArray requireArray(Object value) throws IOException {
        if (!(value instanceof JSONArray)) throw new IOException("Expected JSON array");
        return (JSONArray) value;
    }

    private static boolean isApprovedLinkUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "link.stremio.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static String isoTimestamp(long timestampMs) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestampMs));
    }

    static boolean isNewerPlaybackState(JSONObject item, long capturedAtMs) {
        JSONObject state = item.optJSONObject("state");
        if (state == null) return false;
        String value = state.optString("lastWatched", "");
        if (value.isEmpty()) return false;
        Long timestampMs = parseRfc3339Timestamp(value);
        return timestampMs != null && timestampMs > capturedAtMs;
    }

    @Nullable
    private static Long parseRfc3339Timestamp(String value) {
        Matcher matcher = RFC3339_TIMESTAMP.matcher(value);
        if (!matcher.matches()) return null;

        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(matcher.group(1), position);
        if (parsed == null || position.getIndex() != matcher.group(1).length()) return null;

        String fraction = matcher.group(2);
        int milliseconds = 0;
        if (fraction != null) {
            String millis = (fraction + "000").substring(0, 3);
            milliseconds = Integer.parseInt(millis);
        }

        String zone = matcher.group(3);
        int offsetMs = 0;
        if (!"Z".equals(zone)) {
            int hours = Integer.parseInt(zone.substring(1, 3));
            int minutes = Integer.parseInt(zone.substring(zone.length() - 2));
            if (hours > 23 || minutes > 59) return null;
            offsetMs = (hours * 60 + minutes) * 60 * 1000;
            if (zone.charAt(0) == '-') offsetMs = -offsetMs;
        }
        return parsed.getTime() + milliseconds - offsetMs;
    }
}
