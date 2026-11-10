package com.zcc09.hermespocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.zcc09.hermespocket.bridge.BridgeConfig
import com.zcc09.hermespocket.bridge.HttpBridge
import com.zcc09.hermespocket.bridge.Privilege

/**
 * Runs the device-control bridge. A foreground service because the whole point
 * is to stay reachable while the user is doing something else — and because a
 * running bridge must never be invisible: the notification is the "this phone
 * can be controlled right now" indicator, and it stops the bridge on tap.
 */
class DeviceControlService : Service() {

    companion object {
        private const val TAG = "HermesPocket"
        private const val CHANNEL_ID = "device-control"
        private const val NOTIFICATION_ID = 4711

        const val ACTION_START = "com.zcc09.hermespocket.bridge.START"
        const val ACTION_STOP = "com.zcc09.hermespocket.bridge.STOP"

        @Volatile
        private var bridge: HttpBridge? = null

        fun isServing(): Boolean = bridge?.isRunning() == true

        fun currentBridge(): HttpBridge? = bridge

        fun start(context: Context) {
            val intent = Intent(context, DeviceControlService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DeviceControlService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var config: BridgeConfig

    override fun onCreate() {
        super.onCreate()
        config = BridgeConfig(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                bridge?.stop()
                bridge = null
                config.enabled = false
                Log.i(TAG, "BRIDGE service stopped by request")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                val server = bridge ?: HttpBridge(this, config, Privilege(this)).also { bridge = it }
                if (!server.isRunning() && !server.start()) {
                    Log.w(TAG, "BRIDGE could not bind port ${config.port}")
                }
                config.enabled = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bridge?.stop()
        bridge = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bridge_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.bridge_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DeviceControlActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DeviceControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val address = currentBridge()?.lanUrl() ?: "port ${config.port}"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bridge_notification_title))
            .setContentText(getString(R.string.bridge_notification_text, address))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.bridge_notification_stop), stop).build())
            .build()
    }
}
