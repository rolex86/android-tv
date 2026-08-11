package com.brouken.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import okhttp3.OkHttpClient;

/** Loopback-only Stremio addon that observes identity and preloads subtitles and stream lists. */
public final class StremioConnectorService extends Service {
    static final int PORT = 16745;
    static final String LEGACY_STREAM_RESPONSE = "{\"streams\":[]}";
    static final String HTTP_MANIFEST_URL =
            "http://127.0.0.1:" + PORT + "/manifest.json";
    static final String STREMIO_ADDONS_URL = "stremio:///addons/series";

    private static final int MAX_REQUEST_LINE_LENGTH = 4_096;
    private static final String ACTION_PREFETCH_STREAMS =
            BuildConfig.APPLICATION_ID + ".action.PREFETCH_STREMIO_STREAMS";
    private static final String EXTRA_PREFETCH_TYPE = "prefetch_type";
    private static final String EXTRA_PREFETCH_ID = "prefetch_id";
    private static final String CHANNEL_ID = "stremio_connector";
    private static final int NOTIFICATION_ID = 16745;
    private static final String MANIFEST = "{"
            + "\"id\":\"com.justplayerplus.connector\","
            + "\"version\":\"1.11.0\","
            + "\"name\":\"JustPlayer Plus Connector\","
            + "\"description\":\"Local metadata bridge for JustPlayer Plus\","
            + "\"resources\":["
            + "{\"name\":\"stream\",\"types\":[\"series\",\"movie\"]},"
            + "{\"name\":\"subtitles\",\"types\":[\"series\",\"movie\"]}"
            + "],"
            + "\"types\":[\"series\",\"movie\"],"
            + "\"catalogs\":[],"
            + "\"behaviorHints\":{\"configurable\":false}"
            + "}";

    private final ExecutorService clients = Executors.newFixedThreadPool(4);
    private volatile boolean running;
    private volatile boolean destroyed;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private StremioConnectorStore store;
    private ExternalPlayerDiagnostics diagnostics;
    private OkHttpClient subtitleHttpClient;
    private StremioConnectorOpenSubtitles openSubtitles;
    @Nullable private StremioStreamAggregator streamAggregator;
    @Nullable private SharedPreferences aggregationPreferences;
    @Nullable private SharedPreferences.OnSharedPreferenceChangeListener aggregationListener;

    static boolean start(Context context) {
        try {
            ContextCompat.startForegroundService(
                    context, new Intent(context, StremioConnectorService.class));
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, StremioConnectorService.class));
        new StremioProtectedPrefetchCache(context).clear();
    }

    static boolean prefetchNextEpisode(Context context, StremioEpisodeId episode) {
        if (episode == null
                || !new PlusPrefs(context).stremioConnectorEnabled
                || !StremioAggregationPreferences.isEnabled(context)) {
            return false;
        }
        Intent intent = new Intent(context, StremioConnectorService.class)
                .setAction(ACTION_PREFETCH_STREAMS)
                .putExtra(EXTRA_PREFETCH_TYPE, "series")
                .putExtra(EXTRA_PREFETCH_ID, episode.raw);
        try {
            ContextCompat.startForegroundService(context, intent);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static boolean isValidPrefetchRequest(@Nullable String type, @Nullable String id) {
        return "series".equals(type) && StremioEpisodeId.parse(id) != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        store = new StremioConnectorStore(this);
        diagnostics = new ExternalPlayerDiagnostics(this);
        subtitleHttpClient = StremioConnectorOpenSubtitles.newHttpClient();
        openSubtitles = new StremioConnectorOpenSubtitles(subtitleHttpClient);
        aggregationPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        aggregationListener = (preferences, key) -> {
            if (StremioAggregationPreferences.KEY_ENABLED.equals(key)
                    && !preferences.getBoolean(
                    StremioAggregationPreferences.KEY_ENABLED, false)) {
                releaseStreamAggregator();
                new StremioProtectedPrefetchCache(this).clear();
            }
        };
        aggregationPreferences.registerOnSharedPreferenceChangeListener(aggregationListener);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        diagnostics.recordStremioConnector(
                "service_created", "version=" + BuildConfig.VERSION_NAME);
        startServer("service_create");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        diagnostics.recordStremioConnector(
                "service_start_command",
                "startId=" + startId
                        + " flags=" + flags
                        + " action=" + (intent == null || intent.getAction() == null
                        ? "none" : intent.getAction())
                        + " serverRunning=" + running);
        if (!new PlusPrefs(this).stremioConnectorEnabled) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            startServer("start_command");
        }
        if (intent != null && ACTION_PREFETCH_STREAMS.equals(intent.getAction())) {
            String type = intent.getStringExtra(EXTRA_PREFETCH_TYPE);
            String id = intent.getStringExtra(EXTRA_PREFETCH_ID);
            if (StremioAggregationPreferences.isEnabled(this)
                    && isValidPrefetchRequest(type, id)) {
                getStreamAggregator().prefetch(type, id);
            } else {
                diagnostics.recordStremioConnector(
                        "aggregation_prefetch_skipped",
                        "reason=disabled_or_invalid_request");
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (diagnostics != null) {
            diagnostics.recordStremioConnector(
                    "service_destroyed", "serverRunning=" + running);
        }
        stopServer();
        clients.shutdownNow();
        if (aggregationPreferences != null && aggregationListener != null) {
            aggregationPreferences.unregisterOnSharedPreferenceChangeListener(
                    aggregationListener);
        }
        aggregationPreferences = null;
        aggregationListener = null;
        releaseStreamAggregator();
        openSubtitles = null;
        if (subtitleHttpClient != null) {
            subtitleHttpClient.dispatcher().cancelAll();
            subtitleHttpClient.connectionPool().evictAll();
            subtitleHttpClient = null;
        }
        super.onDestroy();
    }

    private synchronized void stopServer() {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        closeQuietly(socket);
        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private synchronized void startServer(String reason) {
        if (running || destroyed) {
            return;
        }
        ServerSocket candidate = null;
        try {
            candidate = new ServerSocket();
            candidate.setReuseAddress(true);
            candidate.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), PORT), 16);
            serverSocket = candidate;
            running = true;
            acceptThread = new Thread(this::acceptLoop, "stremio-connector-accept");
            acceptThread.start();
            diagnostics.recordStremioConnector(
                    "listening", HTTP_MANIFEST_URL + " reason=" + reason);
        } catch (IOException error) {
            closeQuietly(candidate);
            serverSocket = null;
            running = false;
            diagnostics.recordStremioConnector(
                    "listen_failed",
                    "reason=" + reason + " error=" + error.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket listener = serverSocket;
            if (listener == null) {
                return;
            }
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException error) {
                recoverServerAfterAcceptFailure(listener, error);
                return;
            }
            if (!running) {
                closeQuietly(socket);
                return;
            }
            if (!dispatchClient(clients, () -> handle(socket))) {
                closeQuietly(socket);
                return;
            }
        }
    }

    private void recoverServerAfterAcceptFailure(ServerSocket failedListener, IOException error) {
        synchronized (this) {
            if (!running || destroyed || serverSocket != failedListener) {
                return;
            }
            diagnostics.recordStremioConnector(
                    "server_accept_failed", error.getClass().getSimpleName());
            running = false;
            serverSocket = null;
            acceptThread = null;
            closeQuietly(failedListener);
        }
        // Keep the service and its in-memory aggregator alive. If rebinding still fails,
        // startServer records the failure and lets Android recreate this START_STICKY service;
        // the completed protected prefetch remains available from its private disk cache.
        startServer("accept_recovery");
    }

    static boolean dispatchClient(ExecutorService executor, Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private static void closeQuietly(@Nullable ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(@Nullable Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     client.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     client.getOutputStream(), StandardCharsets.UTF_8))) {
            client.setSoTimeout(5_000);
            String requestLine = readRequestLine(reader);
            if (requestLine == null) {
                writeResponse(writer, 400, "application/json", "{\"error\":\"bad request\"}");
                return;
            }
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) {
                writeResponse(writer, 400, "application/json", "{\"error\":\"bad request\"}");
                return;
            }
            String method = parts[0];
            String requestTarget = parts[1];
            String path = requestTarget;
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            if ("OPTIONS".equals(method)) {
                writeResponse(writer, 204, "text/plain", "");
            } else if (!"GET".equals(method)) {
                writeResponse(writer, 405, "application/json", "{\"error\":\"method\"}");
            } else if ("/manifest.json".equals(path) || "/".equals(path)) {
                writeResponse(writer, 200, "application/json", MANIFEST);
            } else if (path.startsWith("/stream/series/") && path.endsWith(".json")) {
                handleStreamRequest(writer, path, "series");
            } else if (path.startsWith("/stream/movie/") && path.endsWith(".json")) {
                handleStreamRequest(writer, path, "movie");
            } else if (path.startsWith("/subtitles/") && path.endsWith(".json")) {
                StremioSubtitleRequest request = recordSubtitleRequest(requestTarget);
                String body;
                if (request == null) {
                    body = "{\"subtitles\":[]}";
                } else {
                    long startedAt = System.currentTimeMillis();
                    PlusPrefs plusPrefs = new PlusPrefs(this);
                    String[] preferredLanguages = plusPrefs.getPreferredSubtitleLanguages();
                    StremioConnectorOpenSubtitles.Result preload = openSubtitles == null
                            ? new StremioConnectorOpenSubtitles.Result(
                            java.util.Collections.emptyList(), "service_unavailable")
                            : openSubtitles.load(
                                    request, preferredLanguages);
                    if ("loaded".equals(preload.state)) {
                        store.recordPreloadedSubtitles(
                                request,
                                preferredLanguages,
                                preload.candidates,
                                System.currentTimeMillis());
                    }
                    diagnostics.recordStremioConnector(
                            "opensubtitles_preload_" + preload.state,
                            request.type + "/" + request.videoId
                                    + " count=" + preload.candidates.size()
                                    + " durationMs="
                                    + (System.currentTimeMillis() - startedAt));
                    body = StremioIdentitySubtitle.responseJson(
                            request, preload.candidates);
                }
                writeResponse(writer, 200, "application/json", body,
                        request == null ? "no-store" : "private, max-age=31536000, immutable");
            } else if (StremioPreloadedSubtitle.isPath(path)) {
                StremioPreloadedSubtitle.Parsed subtitle =
                        StremioPreloadedSubtitle.parseRequestTarget(requestTarget);
                if (subtitle == null) {
                    writeResponse(writer, 404, "application/json", "{\"error\":\"not found\"}");
                } else {
                    writeRedirect(writer, subtitle.sourceUrl);
                }
            } else if (StremioIdentitySubtitle.isMarkerPath(path)) {
                writeResponse(writer, 200, "text/vtt", "WEBVTT\n\n",
                        "private, max-age=31536000, immutable");
            } else {
                writeResponse(writer, 404, "application/json", "{\"error\":\"not found\"}");
            }
        } catch (IOException | RuntimeException ignored) {
            // The endpoint is advisory; connector failure must never affect playback.
        }
    }

    @Nullable
    private static String readRequestLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() <= MAX_REQUEST_LINE_LENGTH) {
            int value = reader.read();
            if (value == -1) {
                return line.length() == 0 ? null : line.toString();
            }
            if (value == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            line.append((char) value);
        }
        return null;
    }

    private void handleStreamRequest(BufferedWriter writer, String path, String type)
            throws IOException {
        String id = recordStreamRequest(path, type);
        long startedAt = System.currentTimeMillis();
        boolean aggregationEnabled = StremioAggregationPreferences.isEnabled(this);
        String response = streamResponse(
                aggregationEnabled,
                () -> getStreamAggregator().aggregate(type, id),
                error -> diagnostics.recordStremioConnector(
                        "aggregation_failed",
                        type + "/" + id + " error="
                                + error.getClass().getSimpleName()));
        if (aggregationEnabled && !StremioAggregationPreferences.isEnabled(this)) {
            // The preference listener cancels in-flight calls; never publish a result that won
            // the race with the kill switch.
            response = LEGACY_STREAM_RESPONSE;
            diagnostics.recordStremioConnector(
                    "aggregation_kill_switch", type + "/" + id);
        }
        int streamCount = streamCount(response);
        int responseBytes = response.getBytes(StandardCharsets.UTF_8).length;
        diagnostics.recordStremioConnector(
                "aggregation_response_ready",
                type + "/" + id + " streams=" + streamCount
                        + " bytes=" + responseBytes);
        try {
            writeResponse(writer, 200, "application/json", response);
        } catch (IOException error) {
            diagnostics.recordStremioConnector(
                    "aggregation_response_failed",
                    type + "/" + id + " streams=" + streamCount
                            + " bytes=" + responseBytes
                            + " error=" + error.getClass().getSimpleName());
            throw error;
        }
        diagnostics.recordStremioConnector(
                "aggregation_response_written",
                type + "/" + id + " streams=" + streamCount
                        + " bytes=" + responseBytes
                        + " durationMs="
                        + Math.max(0L, System.currentTimeMillis() - startedAt));
    }

    static String streamResponse(boolean aggregationEnabled, StreamResponseProvider provider) {
        return streamResponse(aggregationEnabled, provider, null);
    }

    static String streamResponse(boolean aggregationEnabled,
                                 StreamResponseProvider provider,
                                 @Nullable StreamResponseErrorHandler errorHandler) {
        if (!aggregationEnabled) {
            return LEGACY_STREAM_RESPONSE;
        }
        try {
            String response = provider.load();
            return response == null ? LEGACY_STREAM_RESPONSE : response;
        } catch (RuntimeException error) {
            if (errorHandler != null) {
                errorHandler.onError(error);
            }
            return LEGACY_STREAM_RESPONSE;
        }
    }

    static int streamCount(String response) {
        try {
            org.json.JSONArray streams = new org.json.JSONObject(response)
                    .optJSONArray("streams");
            return streams == null ? -1 : streams.length();
        } catch (org.json.JSONException | RuntimeException error) {
            return -1;
        }
    }

    private synchronized StremioStreamAggregator getStreamAggregator() {
        if (streamAggregator == null) {
            streamAggregator = new StremioStreamAggregator(this, diagnostics);
        }
        return streamAggregator;
    }

    private synchronized void releaseStreamAggregator() {
        StremioStreamAggregator aggregator = streamAggregator;
        streamAggregator = null;
        if (aggregator != null) {
            aggregator.shutdown();
        }
    }

    private String recordStreamRequest(String path, String type) throws IOException {
        String prefix = "/stream/" + type + "/";
        String encodedId = path.substring(prefix.length(), path.length() - ".json".length());
        String id = URLDecoder.decode(encodedId, StandardCharsets.UTF_8.name());
        store.record(type, id, System.currentTimeMillis());
        diagnostics.recordStremioConnector("stream_request", type + "/" + id);
        return id;
    }

    interface StreamResponseProvider {
        String load();
    }

    interface StreamResponseErrorHandler {
        void onError(RuntimeException error);
    }

    @Nullable
    private StremioSubtitleRequest recordSubtitleRequest(String requestTarget) {
        StremioSubtitleRequest request = StremioSubtitleRequest.parse(requestTarget);
        if (request == null) {
            diagnostics.recordStremioConnector(
                    "subtitle_request_ignored", "missing_or_invalid_video_id");
            return null;
        }
        long now = System.currentTimeMillis();
        store.recordContentAssociation(
                request.type, request.videoId, request.filename, now);
        diagnostics.recordStremioConnector(
                "subtitle_request",
                request.type + "/" + request.videoId
                        + " filename=" + (request.filename == null
                        ? "unavailable" : "available"));
        return request;
    }

    private static void writeResponse(BufferedWriter writer, int code, String type, String body)
            throws IOException {
        writeResponse(writer, code, type, body, "no-store");
    }

    private static void writeResponse(BufferedWriter writer,
                                      int code,
                                      String type,
                                      String body,
                                      String cacheControl) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 204 ? "No Content"
                : code == 400 ? "Bad Request" : code == 405 ? "Method Not Allowed" : "Not Found";
        writer.write("HTTP/1.1 " + code + " " + status + "\r\n");
        writer.write("Content-Type: " + type + "; charset=utf-8\r\n");
        writer.write("Content-Length: " + bytes.length + "\r\n");
        writer.write("Access-Control-Allow-Origin: *\r\n");
        writer.write("Access-Control-Allow-Methods: GET, OPTIONS\r\n");
        writer.write("Cache-Control: " + cacheControl + "\r\n");
        writer.write("Connection: close\r\n\r\n");
        writer.write(body);
        writer.flush();
    }

    private static void writeRedirect(BufferedWriter writer, String location) throws IOException {
        writer.write("HTTP/1.1 307 Temporary Redirect\r\n");
        writer.write("Location: " + location + "\r\n");
        writer.write("Access-Control-Allow-Origin: *\r\n");
        writer.write("Cache-Control: private, max-age=31536000, immutable\r\n");
        writer.write("Content-Length: 0\r\n");
        writer.write("Connection: close\r\n\r\n");
        writer.flush();
    }

    private Notification buildNotification() {
        Intent settingsIntent = new Intent(this, SettingsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play_arrow_24dp)
                .setContentTitle(getString(R.string.stremio_connector_notification_title))
                .setContentText(getString(R.string.stremio_connector_notification_text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.stremio_connector_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.stremio_connector_notification_channel_summary));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
