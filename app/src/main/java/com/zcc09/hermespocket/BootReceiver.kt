package com.zcc09.hermespocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zcc09.hermespocket.bridge.BridgeConfig

/**
 * Brings the bridge back after a reboot.
 *
 * The switch on the device-control screen is the user's standing instruction:
 * if it was on when the phone went down, it comes back up without anyone opening
 * the app. Nothing starts when the user had it off.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val enabled = runCatching { BridgeConfig(context).enabled }.getOrDefault(false)
        if (!enabled) {
            Log.i("HermesPocket", "BRIDGE boot: switch is off, staying down")
            return
        }
        Log.i("HermesPocket", "BRIDGE boot: restarting the service the user left on")
        runCatching { DeviceControlService.start(context) }.onFailure {
            Log.w("HermesPocket", "BRIDGE boot: could not start", it)
        }
    }
}
