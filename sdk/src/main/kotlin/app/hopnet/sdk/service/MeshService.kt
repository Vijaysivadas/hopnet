package app.hopnet.sdk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.hopnet.sdk.Hopnet
import app.hopnet.sdk.MeshStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the radio and relay running while the app is in the background, so this phone keeps
 * carrying other people's messages. Foreground service type "connectedDevice" (Nearby links),
 * plus "location" when allowed, so Find Me keeps getting GPS fixes with the screen off.
 */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false

    override fun onCreate() {
        super.onCreate()
        Hopnet.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Offline relay", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while Hopnet relays messages for nearby phones"
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Hopnet.hasPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startInForeground()
        } catch (e: Exception) {
            Hopnet.log("could not start background relay: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            Hopnet.onServiceStarted()
            val nm = getSystemService(NotificationManager::class.java)
            scope.launch {
                Hopnet.status.map(::summary).distinctUntilChanged().collect {
                    nm.notify(NOTIFICATION_ID, notification(Hopnet.status.value))
                }
            }
        }
        return START_STICKY
    }

    /**
     * Android only grants the "location" type while the app may use location in the foreground
     * (a restart from the background can be refused), so fall back to the relay alone.
     */
    private fun startInForeground() {
        val notification = notification(Hopnet.status.value)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            return
        }
        val relay = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (location) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, relay or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                return
            } catch (e: Exception) {
                Hopnet.log("background location not allowed right now (${e.javaClass.simpleName}); relaying without it")
            }
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, relay)
    }

    override fun onDestroy() {
        if (running) Hopnet.onServiceStopped()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun summary(s: MeshStatus): String = when {
        s.gateway -> "Online gateway · ${s.peers.size} nearby"
        s.peers.isNotEmpty() -> "Offline · ${s.peers.size} phone${if (s.peers.size == 1) "" else "s"} nearby"
        else -> "Looking for nearby phones"
    } + if (s.stored > 0) " · carrying ${s.stored}" else ""

    private fun notification(s: MeshStatus): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Hopnet is relaying")
            .setContentText(summary(s))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "hopnet_mesh"
        private const val NOTIFICATION_ID = 4210
        private const val ACTION_STOP = "app.hopnet.sdk.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeshService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeshService::class.java).setAction(ACTION_STOP))
        }
    }
}
