package com.brouken.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores explicitly enabled Stremio background work after a Shield/TV reboot. */
public final class StremioConnectorBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (new PlusPrefs(context).stremioConnectorEnabled) {
            StremioConnectorService.start(context);
        }
        StremioAccountSyncCoordinator.flush(context);
    }
}
