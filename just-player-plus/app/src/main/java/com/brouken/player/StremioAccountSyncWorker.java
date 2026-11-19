package com.brouken.player;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Persistent network-constrained drain for the optional Stremio account sync queue. */
public final class StremioAccountSyncWorker extends Worker {
    public StremioAccountSyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParameters) {
        super(appContext, workerParameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        StremioAccountSyncCoordinator.DrainResult result =
                StremioAccountSyncCoordinator.drainQueue(getApplicationContext());
        return result == StremioAccountSyncCoordinator.DrainResult.COMPLETE
                ? Result.success()
                : Result.retry();
    }

    @Override
    public void onStopped() {
        StremioAccountSyncCoordinator.cancelCurrentAttempt();
        super.onStopped();
    }
}
