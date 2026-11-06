package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class StremioRequestCancellationTest {

    @Test
    public void cancellationStopsRegisteredAndFutureCalls() {
        OkHttpClient client = new OkHttpClient();
        StremioRequestCancellation cancellation = new StremioRequestCancellation();
        Call registered = client.newCall(new Request.Builder()
                .url("https://example.com/registered")
                .build());

        assertTrue(cancellation.register(registered));
        assertFalse(registered.isCanceled());
        cancellation.cancel();
        assertTrue(cancellation.isCancelled());
        assertTrue(registered.isCanceled());

        Call late = client.newCall(new Request.Builder()
                .url("https://example.com/late")
                .build());
        assertFalse(cancellation.register(late));
        assertTrue(late.isCanceled());
    }
}
