package com.zcc09.hermespocket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zcc09.hermespocket.bridge.BridgeConfig
import com.zcc09.hermespocket.bridge.Notifier
import com.zcc09.hermespocket.bridge.PocketAccessibility
import com.zcc09.hermespocket.bridge.Privilege

/**
 * The switchboard for device control: on/off, the pairing details the agent
 * needs, which privilege tier commands run under, and a test that reports what
 * actually works on this phone.
 *
 * Everything here is state the user owns; the app never turns the bridge on by
 * itself and never widens a tier silently.
 */
class DeviceControlActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HermesPocket"
    }

    private lateinit var config: BridgeConfig
    private lateinit var switch: Switch
    private lateinit var state: TextView
    private lateinit var address: TextView
    private lateinit var token: TextView
    private lateinit var accessibilityState: TextView
    private lateinit var testResult: TextView
    private val probeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "pocket-probe").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_control)
        applyInsets()

        config = BridgeConfig(this)
        switch = findViewById(R.id.switch_control)

        state = findViewById(R.id.text_state)
        address = findViewById(R.id.text_address)
        token = findViewById(R.id.text_token)
        accessibilityState = findViewById(R.id.text_accessibility)
        testResult = findViewById(R.id.text_test_result)

        switch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                DeviceControlService.start(this)
                Log.i(TAG, "BRIDGE switch on")
            } else {
                DeviceControlService.stop(this)
                Log.i(TAG, "BRIDGE switch off")
            }
            render()
        }

        findViewById<View>(R.id.link_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.button_copy).setOnClickListener { copyPairing() }
        findViewById<View>(R.id.button_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.button_test).setOnClickListener { runTest() }
        findViewById<View>(R.id.button_new_token).setOnClickListener { regenerateToken() }

        val group = findViewById<RadioGroup>(R.id.tier_group)
        group.setOnCheckedChangeListener { _, checkedId ->
            config.tierPreference = when (checkedId) {
                R.id.radio_root -> "root"
                R.id.radio_shizuku -> "shizuku"
                R.id.radio_app -> "app"
                else -> "auto"
            }
            Log.i(TAG, "BRIDGE tier preference=${config.tierPreference}")
            render()
        }
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

    private fun applyInsets() {
        val root = findViewById<View>(android.R.id.content)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun render() {
        val serving = DeviceControlService.isServing()
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = serving
        switch.setOnCheckedChangeListener { _, checked ->
            if (checked) DeviceControlService.start(this) else DeviceControlService.stop(this)
            render()
        }

        val url = DeviceControlService.currentBridge()?.lanUrl() ?: "http://<phone-ip>:${config.port}"
        state.text = if (serving) {
            getString(R.string.bridge_state_on, url)
        } else {
            getString(R.string.bridge_state_off)
        }
        address.text = url
        token.text = config.token

        // Probing the tier can block for seconds (a root prompt, a Shizuku binder
        // round trip), so it never runs on the UI thread.
        accessibilityState.text = getString(R.string.bridge_probing)
        probeExecutor.execute {
            val tier = Privilege(this).detect(force = true)
            val a11y = PocketAccessibility.isRunning()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                accessibilityState.text = getString(
                    if (a11y) R.string.bridge_a11y_on else R.string.bridge_a11y_off,
                    tier.id,
                )
            }
        }

        val group = findViewById<RadioGroup>(R.id.tier_group)
        val wanted = when (config.tierPreference) {
            "root" -> R.id.radio_root
            "shizuku" -> R.id.radio_shizuku
            "app" -> R.id.radio_app
            else -> R.id.radio_auto
        }
        if (group.checkedRadioButtonId != wanted) group.check(wanted)
    }

    private fun copyPairing() {
        val url = DeviceControlService.currentBridge()?.lanUrl() ?: "http://<phone-ip>:${config.port}"
        val text = "POCKET_URL=$url\nPOCKET_TOKEN=${config.token}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Hermes Pocket device control", text))
        Toast.makeText(this, R.string.bridge_copied, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "BRIDGE pairing copied")
    }

    private fun regenerateToken() {
        config.regenerateToken()
        render()
        Toast.makeText(this, R.string.bridge_token_regenerated, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "BRIDGE token regenerated")
    }

    private fun runTest() {
        testResult.visibility = View.VISIBLE
        testResult.text = getString(R.string.bridge_probing)
        probeExecutor.execute { reportTest(Privilege(this).test()) }
    }

    private fun reportTest(result: Map<String, Any>) {
        val text = buildString {
            append("tier=").append(result["tier"]).append('\n')
            append("exit=").append(result["exit"]).append('\n')
            append(result["output"] ?: "").append('\n')
            append("caps=").append(result["caps"])
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                append("\naccessibility=").append(PocketAccessibility.isRunning())
            }
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            testResult.text = text
        }
        Log.i(TAG, "BRIDGE test tier=${result["tier"]} exit=${result["exit"]}")
    }
}
