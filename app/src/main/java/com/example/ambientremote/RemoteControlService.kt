package com.example.ambientremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Ongoing notification with VOL −, PLAY/PAUSE and VOL + buttons. As a foreground
 * service it also keeps the Bluetooth remote connected while the app is closed.
 */
class RemoteControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: RemoteSettings
    private lateinit var router: CommandRouter

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = RemoteSettings(this)
        router = CommandRouter(settings, UdpRemoteClient(scope) { settings.ip to settings.port })
        if (settings.transport == Transport.BLUETOOTH) BluetoothHidRemote.start(this)
        ensureChannel(this)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not start notification controls", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_COMMAND)?.let { cmd ->
            if (settings.transport == Transport.BLUETOOTH) BluetoothHidRemote.start(this)
            router.send(cmd)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        router.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun commandIntent(cmd: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RemoteControlService::class.java).putExtra(EXTRA_COMMAND, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AmbientRemoteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_remote)
            .setContentTitle("Ambient Remote")
            .setContentText("Tap to open the remote")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "VOL −", commandIntent("VOL_DOWN", 1))
            .addAction(0, "PLAY / PAUSE", commandIntent("PLAY_PAUSE", 2))
            .addAction(0, "VOL +", commandIntent("VOL_UP", 3))
            .build()
    }

    companion object {
        private const val TAG = "RemoteControlService"
        private const val CHANNEL_ID = "remote_controls"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_COMMAND = "command"

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Remote controls", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Play/pause and volume buttons for the TV" }
                )
            }
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteControlService::class.java),
                )
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteControlService::class.java))
        }
    }
}
