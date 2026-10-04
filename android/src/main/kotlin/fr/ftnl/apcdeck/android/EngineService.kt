package fr.ftnl.apcdeck.android

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
import kotlin.system.exitProcess

/**
 * Garde le moteur actif hors de l'écran (son, MIDI, contrôle à distance), avec une notification permanente.
 * « Quitter » dans la notification arrête tout.
 */
class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            (application as ApcApp).engine.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            exitProcess(0)
        }
        (application as ApcApp).engine // démarre le moteur s'il ne l'est pas
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "APC Deck actif", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Le moteur d'APC Deck tourne (son, APC, contrôle à distance)"
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val quit = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("APC Deck")
            .setContentText("Actif : touche pour ouvrir")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Quitter", quit).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "engine"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_QUIT = "fr.ftnl.apcdeck.QUIT"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }
    }
}
