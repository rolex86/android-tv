package com.brouken.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores explicitly enabled Stremio background work after reboot or an app update. */
public final class StremioConnectorBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!restoresConnectorForAction(action)) return;
        if (new PlusPrefs(context).stremioConnectorEnabled) {
            StremioConnectorService.start(context);
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            StremioAccountSyncCoordinator.flush(context);
        }
    }

    static boolean restoresConnectorForAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }
}
