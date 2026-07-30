package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

public class BoundedResponseBodyTest {
    @Test
    public void readsResponseWithinLimit() throws Exception {
        assertEquals("hello", BoundedResponseBody.readUtf8(unknownLength("hello"), 5));
    }

    @Test
    public void stopsUnknownLengthResponseAtLimit() throws Exception {
        try {
            BoundedResponseBody.readUtf8(unknownLength("too large"), 3);
            fail("Expected bounded reader to reject oversized response");
        } catch (BoundedResponseBody.ResponseTooLargeException expected) {
            // Expected.
        }
    }

    private static ResponseBody unknownLength(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new ResponseBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("text/plain; charset=utf-8");
            }

            @Override
            public long contentLength() {
                return -1L;
            }

            @Override
            public BufferedSource source() {
                return Okio.buffer(Okio.source(new ByteArrayInputStream(bytes)));
            }
        };
    }
}
