package tw.rokidha.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import tw.rokidha.app.RokidHaApplication
import tw.rokidha.app.ui.MainActivity
import tw.rokidha.homeassistant.connection.HaConnectionState

/**
 * Optional foreground service ("Keep connected in background").
 *
 * The connection itself lives in [tw.rokidha.homeassistant.connection.HaConnectionManager];
 * this service only keeps the process at foreground priority so Android is less likely to
 * kill it or freeze its network while the glasses are in use. Type `connectedDevice`: the
 * Rokid glasses are a connected device. It shows a permanent notification (Android requirement)
 * with the HA connection status — never the URL or token.
 *
 * It is started from the UI only (Android 12+ forbids starting foreground services from the
 * background). START_STICKY asks the system to recreate it after a kill; whether that works
 * is device-dependent (vendor battery managers).
 */
class HaConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val container = (application as RokidHaApplication).container
        ensureChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (container.haManager.state.value == HaConnectionState.Stopped) container.connectStored()
        job?.cancel()
        job = scope.launch {
            combine(container.haManager.state, container.glasses.state) { ha, g ->
                val haText = when (ha) {
                    is HaConnectionState.Connected -> "Home Assistant connected"
                    is HaConnectionState.Retrying, is HaConnectionState.Connecting -> "Reconnecting to Home Assistant…"
                    is HaConnectionState.CredentialsRejected -> "Home Assistant login rejected"
                    else -> "Home Assistant not connected"
                }
                haText + if (g.linkReady) " · glasses connected" else ""
            }.collect { text -> getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text)) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Background connection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HaConnectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Rokid HA")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "tw.rokidha.app.STOP"

        fun start(context: Context) = context.startForegroundService(Intent(context, HaConnectionService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, HaConnectionService::class.java))
    }
}
