package com.zcc09.hermespocket

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.zcc09.hermespocket.bridge.BridgeConfig
import com.zcc09.hermespocket.bridge.Notifier
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * One native place for everything that is not the chat.
 *
 * The server row hands off to the same setup panel the launch path uses, so
 * there is exactly one implementation of "change the address". Everything else
 * here is a preference the WebView cannot reach: notifications, the
 * device-control entry, and the version.
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HermesPocket"
        private const val EXTRA_FOCUS = "focus"
        private const val FOCUS_SERVER = "server"

        /** Deep link used by the error panel: open straight into the server row. */
        fun intentForServer(activity: ComponentActivity): Intent =
            Intent(activity, SettingsActivity::class.java).putExtra(EXTRA_FOCUS, FOCUS_SERVER)
    }

    private lateinit var config: BridgeConfig
    private lateinit var notificationPermission: ActivityResultLauncher<String>
    private val probeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocket-settings-probe").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        config = BridgeConfig(this)

        notificationPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) Toast.makeText(this, R.string.notify_permission_needed, Toast.LENGTH_LONG).show()
            render()
        }

        findViewById<ImageView>(R.id.button_back).setOnClickListener { finish() }
        findViewById<View>(R.id.row_server).setOnClickListener { changeServer() }
        findViewById<View>(R.id.row_probe).setOnClickListener { probe() }
        findViewById<View>(R.id.row_signout).setOnClickListener { confirmSignOut() }
        findViewById<View>(R.id.row_device).setOnClickListener {
            startActivity(Intent(this, DeviceControlActivity::class.java))
        }
        findViewById<View>(R.id.row_test_notify).setOnClickListener { sendTestNotification() }
        findViewById<View>(R.id.row_battery).setOnClickListener { requestBackgroundPermission() }
        findViewById<View>(R.id.row_update).setOnClickListener {
            Toast.makeText(this, R.string.settings_checking_updates, Toast.LENGTH_SHORT).show()
            Updater(this).checkNow()
        }
        findViewById<Switch>(R.id.switch_notify).setOnCheckedChangeListener { _, checked ->
            config.replyNotifications = checked
            if (checked && !Notifier.canPost(this)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            Log.i(TAG, "SETTINGS notify=$checked")
            render()
        }

        if (intent?.getStringExtra(EXTRA_FOCUS) == FOCUS_SERVER) changeServer()
    }

    override fun onResume() {
        super.onResume()
        Notifier.appVisible = true
        render()
    }

    override fun onPause() {
        super.onPause()
        Notifier.appVisible = false
    }

    // region rendering

    private fun render() {
        val server = ServerStore(this).get()
        findViewById<TextView>(R.id.text_server).text =
            server?.toString() ?: getString(R.string.settings_server_none)

        findViewById<TextView>(R.id.text_permission).text = when {
            !config.replyNotifications -> getString(R.string.settings_permission_off)
            Notifier.canPost(this) -> getString(R.string.settings_permission_allowed)
            else -> getString(R.string.settings_permission_blocked)
        }

        findViewById<TextView>(R.id.text_battery).text =
            if (ignoringBatteryOptimizations()) {
                getString(R.string.settings_battery_allowed)
            } else {
                getString(R.string.settings_battery_blocked)
            }

        findViewById<TextView>(R.id.text_device).text =
            if (!config.enabled) {
                getString(R.string.settings_device_off)
            } else {
                getString(R.string.settings_device_on, config.port)
            }

        findViewById<TextView>(R.id.text_version).text =
            runCatching {
                val info = packageManager.getPackageInfo(packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION") info.versionCode.toLong()
                }
                getString(R.string.settings_version_value, info.versionName, code)
            }.getOrDefault(getString(R.string.settings_version_unknown))

        val toggle = findViewById<Switch>(R.id.switch_notify)
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = config.replyNotifications
        toggle.setOnCheckedChangeListener { _, checked ->
            config.replyNotifications = checked
            if (checked && !Notifier.canPost(this)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            Log.i(TAG, "SETTINGS notify=$checked")
            render()
        }
    }

    /**
     * The verdict is a sentence, not a coloured dot: "No answer" and
     * "Reachable · 38 ms" already tell the user which is which, so the colour
     * only reinforces what the words say.
     */
    private fun showProbe(text: String, tone: Int) {
        runOnUiThread {
            findViewById<TextView>(R.id.text_probe).apply {
                setText(text)
                setTextColor(getColor(tone))
            }
        }
    }

    // endregion

    private fun changeServer() {
        // The setup panel lives in MainActivity; reuse it instead of growing a
        // second address editor with its own validation rules.
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_CHANGE_SERVER
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    private fun probe() {
        val server = ServerStore(this).get()
        if (server == null) {
            showProbe(getString(R.string.settings_probe_no_server), R.color.hp_error)
            return
        }
        showProbe(getString(R.string.settings_probe_running), R.color.hp_text_secondary)
        val target = server.toString()
        probeExecutor.execute {
            val started = System.currentTimeMillis()
            val outcome = runCatching {
                val connection = URL(target).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "hermes-pocket-settings")
                try {
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }
            val elapsed = System.currentTimeMillis() - started
            outcome.fold(
                onSuccess = { code ->
                    Log.i(TAG, "SETTINGS probe ${code} in ${elapsed}ms")
                    if (code in 200..399) {
                        showProbe(getString(R.string.settings_probe_ok, elapsed), R.color.hp_accent_teal)
                    } else {
                        showProbe(getString(R.string.settings_probe_http, code), R.color.hp_error)
                    }
                },
                onFailure = { error ->
                    Log.i(TAG, "SETTINGS probe failed: ${error.message}")
                    showProbe(getString(R.string.settings_probe_failed), R.color.hp_error)
                },
            )
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.settings_sign_out)
            .setMessage(R.string.settings_sign_out_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_sign_out) { _, _ ->
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_CLEAR_SESSION
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                )
                finish()
            }
            .show()
    }

    private fun ignoringBatteryOptimizations(): Boolean = runCatching {
        getSystemService(android.os.PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    /**
     * Ask Android to leave this app alone. Without it the system is free to kill
     * the bridge while the screen is off, which looks exactly like "device
     * control stopped working". The request is per-app and reversible in system
     * settings; Honor phones add a second switch, named in the row's footer.
     */
    private fun requestBackgroundPermission() {
        if (ignoringBatteryOptimizations()) {
            Toast.makeText(this, R.string.settings_battery_granted, Toast.LENGTH_SHORT).show()
            render()
            return
        }
        val direct = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$packageName"),
        )
        val result = runCatching { startActivity(direct) }
        if (result.isFailure) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        Log.i(TAG, "SETTINGS battery exemption requested")
    }

    private fun sendTestNotification() {
        if (!Notifier.canPost(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!config.replyNotifications) {
            // Turning the test into an implicit opt-in would hide the switch's
            // effect; tell the user which control to change instead.
            Toast.makeText(this, R.string.settings_test_needs_switch, Toast.LENGTH_LONG).show()
            return
        }
        val result = Notifier.post(
            this,
            getString(R.string.notify_test_title),
            getString(R.string.notify_test_body),
            tag = "test",
            force = true,
        )
        Toast.makeText(
            this,
            if (result.posted) R.string.notify_test_sent else R.string.notify_test_off,
            Toast.LENGTH_SHORT,
        ).show()
        Log.i(TAG, "SETTINGS test notify posted=${result.posted} reason=${result.reason}")
    }
}
