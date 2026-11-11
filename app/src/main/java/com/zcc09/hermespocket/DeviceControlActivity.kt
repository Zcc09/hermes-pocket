package com.zcc09.hermespocket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import com.zcc09.hermespocket.bridge.ShizukuState
import com.zcc09.hermespocket.bridge.Tier

/**
 * The switchboard for device control: on/off, the pairing details the agent
 * needs, which privilege tier commands run under, the Shizuku setup, and a
 * test that reports what actually works on this phone.
 *
 * Everything here is state the user owns; the app never turns the bridge on by
 * itself and never widens a tier silently.
 */
class DeviceControlActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HermesPocket"

        /** Request code Shizuku echoes back with its answer. */
        private const val SHIZUKU_REQUEST_CODE = 7411
    }

    private lateinit var config: BridgeConfig
    private lateinit var switch: Switch
    private lateinit var state: TextView
    private lateinit var address: TextView
    private lateinit var token: TextView
    private lateinit var accessibilityState: TextView
    private lateinit var shizukuState: TextView
    private lateinit var testResult: TextView

    private val privilege by lazy { Privilege(this) }
    private var unwatchShizuku: (() -> Unit)? = null
    private var lastShizukuAnswerAt = 0L

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
        shizukuState = findViewById(R.id.text_shizuku_state)
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

        findViewById<View>(R.id.button_shizuku_connect).setOnClickListener { connectShizuku() }
        findViewById<View>(R.id.row_open_shizuku).setOnClickListener { openShizuku() }
        findViewById<View>(R.id.row_wireless_debugging).setOnClickListener { openDeveloperSettings() }

        // Shizuku answers on its own listener from version 11 on. Registering it
        // costs nothing while the service is down, and it lets a grant update the
        // tier line without the user pressing anything again.
        unwatchShizuku = privilege.watchShizukuPermission { granted -> onShizukuAnswer(granted) }

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

    override fun onDestroy() {
        unwatchShizuku?.invoke()
        probeExecutor.shutdown()
        super.onDestroy()
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
        refreshProbe()

        val group = findViewById<RadioGroup>(R.id.tier_group)
        val wanted = when (config.tierPreference) {
            "root" -> R.id.radio_root
            "shizuku" -> R.id.radio_shizuku
            "app" -> R.id.radio_app
            else -> R.id.radio_auto
        }
        if (group.checkedRadioButtonId != wanted) group.check(wanted)
    }

    /**
     * The same pass answers both lines, so the reported tier and the Shizuku
     * facts come from the phone and never from the radio group the user picked.
     */
    private fun refreshProbe() {
        postProbe {
            val tier = privilege.detect(force = true)
            val a11y = PocketAccessibility.isRunning()
            val shizuku = privilege.shizukuState()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyProbe(tier, a11y, shizuku)
            }
        }
    }

    /** One queue for anything that shells out; closed with the activity. */
    private fun postProbe(block: () -> Unit) {
        if (probeExecutor.isShutdown) return
        probeExecutor.execute(block)
    }

    private fun applyProbe(tier: Tier, a11y: Boolean, shizuku: ShizukuState) {
        accessibilityState.text = getString(
            if (a11y) R.string.bridge_a11y_on else R.string.bridge_a11y_off,
            tier.id,
        )
        shizukuState.text = getString(shizukuSummary(shizuku))
        shizukuState.setTextColor(
            getColor(if (shizuku.ready) R.color.hp_accent_teal else R.color.hp_text_secondary)
        )
    }

    /** Three facts on one line: installed, running, allowed. */
    private fun shizukuSummary(state: ShizukuState): Int = when {
        !state.installed -> R.string.bridge_shizuku_state_missing
        !state.running -> R.string.bridge_shizuku_state_stopped
        !state.granted -> R.string.bridge_shizuku_state_denied
        else -> R.string.bridge_shizuku_state_ready
    }

    /**
     * The one button in this section. It either asks Shizuku for permission or
     * names the step that is missing, and it never claims a tier on its own.
     */
    private fun connectShizuku() {
        val status = privilege.shizukuState()
        when {
            !status.installed -> {
                Toast.makeText(this, R.string.bridge_shizuku_install_first, Toast.LENGTH_LONG).show()
                openShizukuDownload()
            }
            !status.running -> {
                Toast.makeText(this, R.string.bridge_shizuku_start_first, Toast.LENGTH_LONG).show()
            }
            status.granted -> {
                Toast.makeText(this, R.string.bridge_shizuku_already_allowed, Toast.LENGTH_SHORT).show()
                // Re-probe so the two status lines are current, not remembered.
                render()
            }
            else -> {
                if (privilege.requestShizukuPermission(SHIZUKU_REQUEST_CODE)) {
                    Log.i(TAG, "BRIDGE shizuku permission requested")
                } else {
                    Toast.makeText(this, R.string.bridge_shizuku_start_first, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Shizuku 11 and newer answer on its own listener; older clients route the
     * same answer through the activity. Both land here, so a second report
     * inside half a second is the same answer and is dropped.
     */
    private fun onShizukuAnswer(granted: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShizukuAnswerAt < 500) return
        lastShizukuAnswerAt = now
        Log.i(TAG, "BRIDGE shizuku permission granted=$granted")
        Toast.makeText(
            this,
            if (granted) R.string.bridge_shizuku_granted else R.string.bridge_shizuku_denied,
            Toast.LENGTH_LONG,
        ).show()
        // A grant only becomes a tier when the probe proves it, so re-detect it.
        render()
    }

    /**
     * Android deprecates the permission callback in favour of the activity-result
     * API, but Shizuku asks its question over its own binder and has no contract
     * to register, so the callback stays the honest place to catch that answer.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SHIZUKU_REQUEST_CODE) return
        onShizukuAnswer(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    /**
     * Opens the Shizuku app. Android 11 hides packages this app did not declare
     * in `<queries>`, so when the launcher lookup comes back empty the explicit
     * package intent still starts it.
     */
    private fun openShizuku() {
        if (!privilege.shizukuState().installed) {
            openShizukuDownload()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(Privilege.SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(Privilege.SHIZUKU_PACKAGE)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (t: Throwable) {
            Log.w(TAG, "BRIDGE could not open Shizuku, opening the download page", t)
            openShizukuDownload()
        }
    }

    /** The app ships no browser, so a missing one is normal and gets a plain line. */
    private fun openShizukuDownload() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(Privilege.SHIZUKU_DOWNLOAD_URL))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            )
            Log.i(TAG, "BRIDGE opened the Shizuku download page")
        } catch (t: Throwable) {
            Log.w(TAG, "BRIDGE no browser for the Shizuku download page", t)
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (t: Throwable) {
            Log.w(TAG, "BRIDGE developer settings unavailable, opening settings", t)
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (t2: Throwable) {
                Log.w(TAG, "BRIDGE no settings screen to open", t2)
                Toast.makeText(this, R.string.bridge_shizuku_no_settings, Toast.LENGTH_SHORT).show()
            }
        }
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
        postProbe { reportTest(privilege.test()) }
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
