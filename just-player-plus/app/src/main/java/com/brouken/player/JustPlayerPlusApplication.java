package com.brouken.player;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.work.Configuration;

/** Supplies WorkManager configuration only when background account work is first requested. */
public final class JustPlayerPlusApplication extends Application
        implements Configuration.Provider {

    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().build();
    }
}
