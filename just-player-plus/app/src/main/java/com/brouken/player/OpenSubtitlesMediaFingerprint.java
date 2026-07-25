package com.brouken.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Computes the OpenSubtitles movie hash by reading only the first and last 64 KiB. */
final class OpenSubtitlesMediaFingerprint {
    static final int HASH_CHUNK_BYTES = 64 * 1024;
    static final long MIN_HASHABLE_BYTES = HASH_CHUNK_BYTES * 2L;
    private static final int PLAYER_WAIT_ATTEMPTS = 40;
    private static final long PLAYER_WAIT_MS = 100L;

    interface CallObserver {
        void onCallStarted(Call call);
        void onCallFinished(Call call);
    }

    static final class Result {
        final String hash;
        final long size;
        final String filename;

        Result(String hash, long size, String filename) {
            this.hash = hash;
            this.size = size;
            this.filename = filename;
        }
    }

    private OpenSubtitlesMediaFingerprint() {
    }

    @Nullable
    static Result fromCurrentPlayer(OkHttpClient httpClient, CallObserver observer)
            throws IOException {
        MediaItem mediaItem = waitForCurrentMediaItem();
        if (mediaItem == null || mediaItem.localConfiguration == null) {
            return null;
        }
        Uri uri = mediaItem.localConfiguration.uri;
        String filename = filename(uri, mediaItem);
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return fromHttp(httpClient, uri, filename, observer);
        }
        if ("file".equalsIgnoreCase(scheme) && uri.getPath() != null) {
            return fromFile(new File(uri.getPath()), filename);
        }
        return null;
    }

    @Nullable
    private static MediaItem waitForCurrentMediaItem() throws IOException {
        for (int attempt = 0; attempt < PLAYER_WAIT_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("cancelled");
            }
            Player player = PlayerActivity.player;
            MediaItem mediaItem = player == null ? null : player.getCurrentMediaItem();
            if (mediaItem != null && mediaItem.localConfiguration != null) {
                return mediaItem;
            }
            try {
                Thread.sleep(PLAYER_WAIT_MS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("cancelled", error);
            }
        }
        return null;
    }

    @Nullable
    private static Result fromFile(File file, String filename) throws IOException {
        if (!file.isFile()) {
            return null;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length();
            if (size < MIN_HASHABLE_BYTES) {
                return null;
            }
            byte[] head = new byte[HASH_CHUNK_BYTES];
            byte[] tail = new byte[HASH_CHUNK_BYTES];
            input.seek(0L);
            input.readFully(head);
            input.seek(size - HASH_CHUNK_BYTES);
            input.readFully(tail);
            return new Result(computeHash(size, head, tail), size, filename);
        }
    }

    @Nullable
    private static Result fromHttp(OkHttpClient httpClient,
                                   Uri uri,
                                   String filename,
                                   CallObserver observer) throws IOException {
        Request headRequest = request(uri, 0L, HASH_CHUNK_BYTES - 1L);
        byte[] head;
        long size;
        Call headCall = httpClient.newCall(headRequest);
        observer.onCallStarted(headCall);
        try (Response response = headCall.execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            size = totalSize(response);
            if (size < MIN_HASHABLE_BYTES) {
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            head = readExactly(body.byteStream(), HASH_CHUNK_BYTES);
        } finally {
            observer.onCallFinished(headCall);
        }

        long tailStart = size - HASH_CHUNK_BYTES;
        Request tailRequest = request(uri, tailStart, size - 1L);
        byte[] tail;
        Call tailCall = httpClient.newCall(tailRequest);
        observer.onCallStarted(tailCall);
        try (Response response = tailCall.execute()) {
            if (!response.isSuccessful() || response.code() != 206) {
                return null;
            }
            long returnedStart = contentRangeStart(response.header("Content-Range"));
            if (returnedStart != tailStart) {
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            tail = readExactly(body.byteStream(), HASH_CHUNK_BYTES);
        } finally {
            observer.onCallFinished(tailCall);
        }
        return new Result(computeHash(size, head, tail), size, filename);
    }

    private static Request request(Uri uri, long start, long end) {
        Request.Builder builder = new Request.Builder()
                .url(uri.toString())
                .header("Range", "bytes=" + start + "-" + end)
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "JustPlayer Plus");
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int separator = userInfo.indexOf(':');
            if (separator > 0) {
                builder.header("Authorization", Credentials.basic(
                        userInfo.substring(0, separator), userInfo.substring(separator + 1)));
            }
        }
        return builder.build();
    }

    private static long totalSize(Response response) {
        long rangeTotal = contentRangeTotal(response.header("Content-Range"));
        if (rangeTotal > 0L) {
            return rangeTotal;
        }
        ResponseBody body = response.body();
        long bodyLength = body == null ? -1L : body.contentLength();
        if (response.code() == 200 && bodyLength > 0L) {
            return bodyLength;
        }
        String length = response.header("Content-Length");
        if (response.code() == 200 && length != null) {
            try {
                return Long.parseLong(length);
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return -1L;
    }

    static long contentRangeTotal(@Nullable String value) {
        if (value == null) {
            return -1L;
        }
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash == value.length() - 1 || "*".equals(value.substring(slash + 1))) {
            return -1L;
        }
        try {
            return Long.parseLong(value.substring(slash + 1));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    static long contentRangeStart(@Nullable String value) {
        if (value == null) {
            return -1L;
        }
        int space = value.indexOf(' ');
        int dash = value.indexOf('-', space + 1);
        if (space < 0 || dash <= space + 1) {
            return -1L;
        }
        try {
            return Long.parseLong(value.substring(space + 1, dash));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static byte[] readExactly(InputStream input, int expected) throws IOException {
        byte[] result = new byte[expected];
        int offset = 0;
        while (offset < expected) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("cancelled");
            }
            int count = input.read(result, offset, expected - offset);
            if (count == -1) {
                throw new IOException("Range response ended early");
            }
            offset += count;
        }
        return result;
    }

    static String computeHash(long size, byte[] head, byte[] tail) {
        if (head.length != HASH_CHUNK_BYTES || tail.length != HASH_CHUNK_BYTES) {
            throw new IllegalArgumentException("OpenSubtitles hash requires two 64 KiB chunks");
        }
        long hash = size;
        hash = addChunk(hash, head);
        hash = addChunk(hash, tail);
        return String.format(Locale.US, "%016x", hash);
    }

    private static long addChunk(long hash, byte[] chunk) {
        for (int offset = 0; offset < chunk.length; offset += 8) {
            long value = 0L;
            for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
                value |= ((long) chunk[offset + byteIndex] & 0xffL) << (byteIndex * 8);
            }
            hash += value;
        }
        return hash;
    }

    private static String filename(Uri uri, MediaItem mediaItem) {
        String lastSegment = uri.getLastPathSegment();
        if (lastSegment != null && !lastSegment.trim().isEmpty()) {
            return Uri.decode(lastSegment.trim());
        }
        CharSequence title = mediaItem.mediaMetadata.title;
        if (title != null && title.length() > 0) {
            return title.toString().trim();
        }
        return "video";
    }
}
