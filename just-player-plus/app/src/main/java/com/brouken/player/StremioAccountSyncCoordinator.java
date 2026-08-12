package com.brouken.player;

import android.content.Context;
import android.preference.PreferenceManager;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** The single runtime gate for all Stremio account progress and watched writes. */
final class StremioAccountSyncCoordinator {
    static final long CHECKPOINT_INTERVAL_MS = 90_000L;

    private static final String UNIQUE_WORK_NAME =
            "justplayer_plus_stremio_account_sync";
    private static final long RETRY_BACKOFF_SECONDS = 30L;
    private static final Object DRAIN_LOCK = new Object();
    private static final StremioAccountClient CLIENT = new StremioAccountClient();
    private static final ExecutorService FLUSH_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "stremio-account-flush");
                thread.setDaemon(true);
                return thread;
            });

    enum DrainResult {
        COMPLETE,
        RETRY
    }

    private StremioAccountSyncCoordinator() {
    }

    static boolean isEnabled(Context context) {
        Context app = context.getApplicationContext();
        return PreferenceManager.getDefaultSharedPreferences(app).getBoolean(
                PlusPrefs.KEY_STREMIO_ACCOUNT_SYNC_ENABLED, false)
                && new StremioAuthKeyStore(app).isConfigured();
    }

    static void checkpoint(
            Context context,
            StremioEpisodeId episode,
            long positionMs,
            long durationMs,
            boolean completed) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app) || episode == null || durationMs <= 0L) {
            return;
        }
        long clampedPosition = Math.max(0L, Math.min(positionMs, durationMs));
        StremioLibraryItemPatch.Checkpoint checkpoint =
                new StremioLibraryItemPatch.Checkpoint(
                        episode,
                        clampedPosition,
                        durationMs,
                        completed,
                        System.currentTimeMillis());
        if (!checkpoint.isValid()) return;
        if (new StremioAccountSyncQueue(app).upsert(checkpoint)) {
            enqueue(app, ExistingWorkPolicy.REPLACE);
        }
    }

    static void flush(Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app) || new StremioAccountSyncQueue(app).size() == 0) return;
        enqueue(app, ExistingWorkPolicy.KEEP);
    }

    /** Checks and schedules a persisted queue without doing Keystore I/O on the caller thread. */
    static void flushAsync(Context context) {
        Context app = context.getApplicationContext();
        try {
            FLUSH_EXECUTOR.execute(() -> flush(app));
        } catch (RejectedExecutionException error) {
            new ExternalPlayerDiagnostics(app).recordStremioConnector(
                    "account_sync_flush_dispatch_failed",
                    error.getClass().getSimpleName());
        }
    }

    static DrainResult drainQueue(Context context) {
        Context app = context.getApplicationContext();
        synchronized (DRAIN_LOCK) {
            StremioAccountSyncQueue queue = new StremioAccountSyncQueue(app);
            ExternalPlayerDiagnostics diagnostics = new ExternalPlayerDiagnostics(app);
            while (isEnabled(app)) {
                if (Thread.currentThread().isInterrupted()) return DrainResult.RETRY;
                StremioLibraryItemPatch.Checkpoint checkpoint = queue.peek();
                if (checkpoint == null) return DrainResult.COMPLETE;
                String authKey = new StremioAuthKeyStore(app).load();
                if (authKey == null) return DrainResult.RETRY;
                StremioAccountClient.SyncResult result = CLIENT.sync(authKey, checkpoint);
                diagnostics.recordStremioConnector(
                        "account_sync_" + result.reason,
                        checkpoint.episode.raw
                                + " completed=" + checkpoint.completed
                                + " positionMs=" + checkpoint.positionMs);
                if (!result.success) return DrainResult.RETRY;
                queue.removeIfCurrent(checkpoint);
            }
            return DrainResult.COMPLETE;
        }
    }

    static void cancelCurrentAttempt() {
        CLIENT.cancelAll();
    }

    static void enable(Context context) {
        CLIENT.allowAccountWrites();
        flush(context);
    }

    static void disable(Context context) {
        Context app = context.getApplicationContext();
        CLIENT.blockAccountWritesAndCancel();
        try {
            WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_WORK_NAME);
        } catch (RuntimeException error) {
            new ExternalPlayerDiagnostics(app).recordStremioConnector(
                    "account_sync_cancel_failed",
                    error.getClass().getSimpleName());
        }
        new StremioAccountSyncQueue(app).clear();
    }

    static int pendingCount(Context context) {
        return new StremioAccountSyncQueue(context).size();
    }

    private static void enqueue(Context app, ExistingWorkPolicy policy) {
        if (!isEnabled(app)) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(StremioAccountSyncWorker.class)
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                RETRY_BACKOFF_SECONDS,
                                TimeUnit.SECONDS)
                        .build();
        try {
            WorkManager.getInstance(app).enqueueUniqueWork(
                    UNIQUE_WORK_NAME, policy, request);
        } catch (RuntimeException error) {
            new ExternalPlayerDiagnostics(app).recordStremioConnector(
                    "account_sync_schedule_failed",
                    error.getClass().getSimpleName());
        }
    }
}
