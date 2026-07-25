package com.brouken.player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.ResponseBody;

/** Reads an HTTP response without ever buffering more than the configured limit. */
public final class BoundedResponseBody {
    private static final int BUFFER_BYTES = 8 * 1024;

    private BoundedResponseBody() {
    }

    public static String readUtf8(ResponseBody body, int maximumBytes) throws IOException {
        return new String(readBytes(body, maximumBytes), StandardCharsets.UTF_8);
    }

    public static byte[] readBytes(ResponseBody body, int maximumBytes) throws IOException {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must be non-negative");
        }
        long declaredLength = body.contentLength();
        if (declaredLength > maximumBytes) {
            throw new ResponseTooLargeException();
        }

        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream(Math.min(maximumBytes, BUFFER_BYTES))) {
            byte[] buffer = new byte[Math.min(BUFFER_BYTES, Math.max(1, maximumBytes + 1))];
            int total = 0;
            while (true) {
                int count = input.read(buffer);
                if (count == -1) {
                    break;
                }
                total += count;
                if (total > maximumBytes) {
                    throw new ResponseTooLargeException();
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    public static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() {
            super("HTTP response exceeds configured limit");
        }
    }
}
