package com.example.ambientremote

import android.content.Context

/**
 * Tracks the visible parts of the app (the remote screen and the screensaver).
 * When the last one closes, everything is shut down: Bluetooth keyboard mode,
 * the notification controls and their service, so nothing keeps running.
 */
object AppSession {
    private var users = 0

    fun enter() {
        users++
    }

    fun leave(context: Context) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) shutdown(context)
    }

    fun shutdown(context: Context) {
        users = 0
        RemoteControlService.stop(context)
        BluetoothHidRemote.shutdown(context)
    }
}
