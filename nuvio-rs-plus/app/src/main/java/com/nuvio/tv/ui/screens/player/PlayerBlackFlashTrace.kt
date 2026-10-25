package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log

/** Diagnostic-only timing markers. No effect on playback or surface lifecycle. */
internal object PlayerBlackFlashTrace {
    fun log(message: String) {
        Log.i("NRSP_BLACKFLASH", "${SystemClock.elapsedRealtime()}ms ${message}")
    }
}
