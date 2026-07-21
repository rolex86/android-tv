package com.brouken.player.aisubtitles;

import android.content.ContentResolver;
import android.content.Context;

import com.sigpwned.chardet4j.Chardet;
import com.sigpwned.chardet4j.io.DecodedInputStreamReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Loads a selected external subtitle into bounded UTF-8 text without exposing its URI. */
public final class AiSubtitleSourceLoader {
    public static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;

    public enum Failure {
        TOO_LARGE,
        UNREADABLE
    }

    public static final class LoadException extends IOException {
        public final Failure failure;

        LoadException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }
    }

    private final Context context;
    private final OkHttpClient httpClient;

    public AiSubtitleSourceLoader(Context context, OkHttpClient httpClient) {
        this.context = context.getApplicationContext();
        this.httpClient = httpClient;
    }

    public String load(AiSubtitleSource source) throws IOException {
        String scheme = source.uri.getScheme();
        if (scheme == null) {
            throw new LoadException(Failure.UNREADABLE, "Missing URI scheme");
        }
        byte[] bytes;
        if (scheme.toLowerCase(Locale.ROOT).startsWith("http")) {
            bytes = loadHttp(source);
        } else {
            bytes = loadContent(source);
        }
        return decode(bytes);
    }

    private byte[] loadHttp(AiSubtitleSource source) throws IOException {
        Request request;
        try {
            request = new Request.Builder().url(source.uri.toString()).build();
        } catch (IllegalArgumentException error) {
            throw new LoadException(Failure.UNREADABLE, "Invalid subtitle URL");
        }
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new LoadException(Failure.UNREADABLE, "Subtitle download failed");
            }
            if (body.contentLength() > MAX_INPUT_BYTES) {
                throw new LoadException(Failure.TOO_LARGE, "Subtitle is too large");
            }
            return readBounded(body.byteStream());
        }
    }

    private byte[] loadContent(AiSubtitleSource source) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(source.uri)) {
            if (input == null) {
                throw new LoadException(Failure.UNREADABLE, "Subtitle cannot be opened");
            }
            return readBounded(input);
        } catch (SecurityException error) {
            throw new LoadException(Failure.UNREADABLE, "Subtitle permission denied");
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_INPUT_BYTES) {
                throw new LoadException(Failure.TOO_LARGE, "Subtitle is too large");
            }
            output.write(buffer, 0, count);
        }
        if (total == 0) {
            throw new LoadException(Failure.UNREADABLE, "Subtitle is empty");
        }
        return output.toByteArray();
    }

    private static String decode(byte[] bytes) throws IOException {
        try (DecodedInputStreamReader reader = Chardet.decode(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder(bytes.length);
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                text.append(buffer, 0, count);
            }
            if (text.length() > 0 && text.charAt(0) == '\ufeff') {
                text.deleteCharAt(0);
            }
            return text.toString();
        }
    }
}
