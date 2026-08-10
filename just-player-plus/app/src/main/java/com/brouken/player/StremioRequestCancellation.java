package com.brouken.player;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;

/** Cancels only the upstream calls owned by one aggregation flight. */
final class StremioRequestCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Call> calls = Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>());

    boolean register(Call call) {
        if (cancelled.get()) {
            call.cancel();
            return false;
        }
        calls.add(call);
        if (cancelled.get()) {
            calls.remove(call);
            call.cancel();
            return false;
        }
        return true;
    }

    void unregister(Call call) {
        calls.remove(call);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    void cancel() {
        cancelled.set(true);
        cancelCalls();
    }

    void cancelCalls() {
        for (Call call : calls) {
            call.cancel();
        }
    }
}
