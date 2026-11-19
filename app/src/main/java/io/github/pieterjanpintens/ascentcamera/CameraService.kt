package io.github.pieterjanpintens.ascentcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService

/**
 * Runs while the phone sits in its holder with the screen off: a foreground service (type camera) so Android lets it use the
 * camera without the screen on, a partial wake lock so the network listener keeps answering, and the photo server.
 * It must be started from the app's screen (Android only allows camera services that were started while the app was visible).
 */
class CameraService : LifecycleService() {
    companion object {
        const val PORT = 8765
        private const val CHANNEL = "table-camera"
        private const val NOTIFICATION_ID = 1

        @Volatile var running = false
            private set
        @Volatile var lastEvent = ""
            private set
    }

    private var server: PhotoServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AscentCamera:server")
            .apply { acquire() }
        val taker = PhotoTaker(this)
        try {
            server = PhotoServer(PORT, { Pairing.code(this) }, { taker.take() }, { lastEvent = it }).also { it.start() }
            running = true
            lastEvent = "Waiting for Ascent"
        } catch (e: Exception) {
            lastEvent = "Could not open port $PORT: ${e.message}"
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY // Android would not let a restarted camera service use the camera anyway
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        running = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
    }
}
