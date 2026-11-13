package com.zcc09.hermespocket

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.zcc09.hermespocket.bridge.BridgeConfig

/**
 * Brings the bridge back when the system kills it.
 *
 * `START_STICKY` alone is not enough on phones with aggressive power management:
 * measured on a Magic V3, `am kill` left the bridge down indefinitely. An
 * inexact repeating alarm is the lightest tool that survives process death
 * without a dependency, and it only ever starts the service the user left on.
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HermesPocket"
        private const val INTERVAL_MS = 15L * 60L * 1000L
        private const val REQUEST_CODE = 4102

        /** (Re)arms the watchdog. Safe to call from anywhere; it is idempotent. */
        fun schedule(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pendingIntent(context),
            )
            Log.i(TAG, "BRIDGE watchdog armed every ${INTERVAL_MS / 60000} min")
        }

        fun cancel(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            alarm.cancel(pendingIntent(context))
            Log.i(TAG, "BRIDGE watchdog cancelled")
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val enabled = runCatching { BridgeConfig(context).enabled }.getOrDefault(false)
        if (!enabled) {
            Log.i(TAG, "BRIDGE watchdog: switch is off, nothing to do")
            cancel(context)
            return
        }
        if (DeviceControlService.isServing()) {
            Log.i(TAG, "BRIDGE watchdog: still up")
            return
        }
        Log.i(TAG, "BRIDGE watchdog: service was gone, restarting")
        runCatching { DeviceControlService.start(context) }.onFailure {
            Log.w(TAG, "BRIDGE watchdog could not restart the service", it)
        }
    }
}
