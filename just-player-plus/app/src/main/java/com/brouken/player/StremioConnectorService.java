package com.brouken.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import okhttp3.OkHttpClient;

/** Loopback-only Stremio addon that observes content identity and preloads subtitle listings. */
public final class StremioConnectorService extends Service {
    static final int PORT = 16745;
    static final String HTTP_MANIFEST_URL =
            "http://127.0.0.1:" + PORT + "/manifest.json";
    static final String STREMIO_ADDONS_URL = "stremio:///addons/series";

    private static final int MAX_REQUEST_LINE_LENGTH = 4_096;
    private static final String CHANNEL_ID = "stremio_connector";
    private static final int NOTIFICATION_ID = 16745;
    private static final String MANIFEST = "{"
            + "\"id\":\"com.justplayerplus.connector\","
            + "\"version\":\"1.7.0\","
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
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private StremioConnectorStore store;
    private ExternalPlayerDiagnostics diagnostics;
    private OkHttpClient subtitleHttpClient;
    private StremioConnectorOpenSubtitles openSubtitles;

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
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new StremioConnectorStore(this);
        diagnostics = new ExternalPlayerDiagnostics(this);
        subtitleHttpClient = StremioConnectorOpenSubtitles.newHttpClient();
        openSubtitles = new StremioConnectorOpenSubtitles(subtitleHttpClient);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!new PlusPrefs(this).stremioConnectorEnabled) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            startServer();
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
        stopServer();
        clients.shutdownNow();
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

    private synchronized void startServer() {
        if (running) {
            return;
        }
        try {
            serverSocket = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
            serverSocket.setReuseAddress(true);
            running = true;
            acceptThread = new Thread(this::acceptLoop, "stremio-connector-accept");
            acceptThread.start();
            diagnostics.recordStremioConnector("listening", HTTP_MANIFEST_URL);
        } catch (IOException error) {
            running = false;
            diagnostics.recordStremioConnector(
                    "listen_failed", error.getClass().getSimpleName());
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
            } catch (SocketException error) {
                if (running) {
                    stopSelf();
                }
                return;
            } catch (IOException ignored) {
                // A malformed/aborted local request must not terminate the connector.
                continue;
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
                recordStreamRequest(path, "series");
                writeResponse(writer, 200, "application/json", "{\"streams\":[]}");
            } else if (path.startsWith("/stream/movie/") && path.endsWith(".json")) {
                recordStreamRequest(path, "movie");
                writeResponse(writer, 200, "application/json", "{\"streams\":[]}");
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

    private void recordStreamRequest(String path, String type) throws IOException {
        String prefix = "/stream/" + type + "/";
        String encodedId = path.substring(prefix.length(), path.length() - ".json".length());
        String id = URLDecoder.decode(encodedId, StandardCharsets.UTF_8.name());
        store.record(type, id, System.currentTimeMillis());
        diagnostics.recordStremioConnector("stream_request", type + "/" + id);
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
