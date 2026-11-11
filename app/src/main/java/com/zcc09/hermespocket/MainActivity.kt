package com.zcc09.hermespocket

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebStorage
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Single-activity shell that hosts the user's own Hermes web app in a WebView.
 *
 * There is no built-in server: the address is whatever the user enters on the
 * setup screen (an IP and port on their network, or a public URL), and it is
 * the only thing this app knows about their deployment.
 *
 * Design rules:
 * - Anything on the configured host stays in the WebView, and so does every
 *   redirect (an auth flow must not be interrupted) — see
 *   [shouldOverrideUrlLoading].
 * - A tapped link to any other host goes to the system's default browser. No
 *   browser is bundled or preferred here; the user's Android default decides.
 * - All logging uses the "HermesPocket" tag.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HermesPocket"
        private const val BACK_EXIT_WINDOW_MS = 2000L

        /** Launcher shortcut / internal intent: open the server setup screen. */
        const val ACTION_CHANGE_SERVER = "com.zcc09.hermespocket.CHANGE_SERVER"

        /** Launcher shortcut: reload the page (pull-to-refresh is gone by design). */
        const val ACTION_RELOAD = "com.zcc09.hermespocket.RELOAD"

        /** Test hook: `am start … -e server https://example.com` sets the server once. */
        private const val EXTRA_SERVER = "server"

        /** Test hook: `-e bridge 1` starts device control so CI can drive the bridge. */
        private const val EXTRA_BRIDGE = "bridge"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorReason: TextView
    private lateinit var hintBar: View
    private lateinit var setupPanel: View
    private lateinit var serverInput: EditText
    private lateinit var serverError: TextView
    private lateinit var store: ServerStore
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    /** The configured base URL, or null while the setup screen is up. */
    private var serverUrl: String? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var lastBackPressAt = 0L

    private val downloadManager: DownloadManager by lazy {
        getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applyWindowInsets()

        webView = findViewById(R.id.web_view)
        progressBar = findViewById(R.id.progress_bar)
        errorPanel = findViewById(R.id.error_panel)
        errorReason = findViewById(R.id.error_reason)
        hintBar = findViewById(R.id.hint_bar)
        setupPanel = findViewById(R.id.setup_panel)
        serverInput = findViewById(R.id.server_input)
        serverError = findViewById(R.id.server_error)
        store = ServerStore(this)

        setupWebView()
        setupChrome()
        setupSetupPanel()

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result -> onFileChooserResult(result) }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> onPermissionResult(granted) }

        if (savedInstanceState != null && store.get() != null) {
            Log.i(TAG, "Restoring saved WebView state")
            serverUrl = store.get()
            hideSetup()
            webView.restoreState(savedInstanceState)
        } else if (intent?.action == ACTION_CHANGE_SERVER) {
            showSetup(store.get())
        } else {
            val configured = applyTestServer(intent) ?: store.get()
            if (configured == null) {
                Log.i(TAG, "SERVER not configured, showing setup")
                showSetup(null)
            } else {
                startLoading(configured)
            }
        }

        if (intent?.getStringExtra(EXTRA_BRIDGE) == "1") {
            DeviceControlService.start(this)
            Log.i(TAG, "BRIDGE started by test hook")
        }

        Updater(this).start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_CHANGE_SERVER) {
            showSetup(store.get())
            return
        }
        if (intent.action == ACTION_RELOAD) {
            reloadPage()
            return
        }
        val data = intent.data ?: return
        intent.data = null
        Log.i(TAG, "onNewIntent url ${data.toString()}")
        webView.loadUrl(data.toString())
    }

    override fun onResume() {
        super.onResume()
        com.zcc09.hermespocket.bridge.Notifier.appVisible = true
    }

    override fun onPause() {
        super.onPause()
        com.zcc09.hermespocket.bridge.Notifier.appVisible = false
        try {
            CookieManager.getInstance().flush()
        } catch (t: Throwable) {
            Log.w(TAG, "Cookie flush failed", t)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    /**
     * Android 15+ draws every app edge-to-edge, so the WebView would otherwise
     * sit under the status bar and the hint bar under the gesture area. Pad the
     * root by the system bars (and the keyboard, so the composer is never
     * covered) and keep the status-bar icons light on this dark surface.
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    // region WebView setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // The portal gate blocks obvious WebView user agents, so strip the
        // "; wv" token and any "Version/x.y" token to look like plain
        // Chrome mobile.
        val userAgent = buildUserAgent(settings)
        settings.userAgentString = userAgent
        Log.i(TAG, "UA=$userAgent")

        webView.webViewClient = buildWebViewClient()
        webView.webChromeClient = buildWebChromeClient()
        webView.setDownloadListener(buildDownloadListener())
    }

    private fun buildUserAgent(settings: WebSettings): String {
        val raw = settings.userAgentString
            ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        return raw
            .replace("; wv", "")
            .replace(Regex("""Version/\d+(?:\.\d+)*"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun buildWebViewClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (isInternalHost(url.host?.lowercase().orEmpty())) {
                // The user's own server always stays in place.
                return false
            }
            // A redirect (sign-in, SSO, a payment step) must not be interrupted,
            // so only a link the person actually tapped leaves the app.
            if (!request.hasGesture()) return false
            openInBrowser(url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            hideErrorPanel()
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            updateHintBar(url)
            Log.i(TAG, "onPageStarted host=${Uri.parse(url).host} url=$url")
        }

        override fun onPageFinished(view: WebView, url: String) {
            progressBar.visibility = View.GONE
            updateHintBar(url)
            Log.i(TAG, "PAGE_FINISHED url=$url")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            Log.w(TAG, "Main frame error: ${error.description} (${error.errorCode})")
            showErrorPanel(error.description)
        }
    }

    private fun buildWebChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, progress: Int) {
            progressBar.progress = progress
        }

        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            this@MainActivity.filePathCallback = filePathCallback
            val intent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "File chooser could not be started", t)
                this@MainActivity.filePathCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val resource = request.resources.firstOrNull {
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            val permission = when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> null
            }
            if (permission == null) {
                try {
                    request.grant(request.resources)
                } catch (t: Throwable) {
                    Log.w(TAG, "Granting permission request failed", t)
                }
                return
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, permission)
                == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    request.grant(request.resources)
                } catch (t: Throwable) {
                    Log.w(TAG, "Granting permission request failed", t)
                }
                return
            }
            pendingPermissionRequest = request
            try {
                permissionLauncher.launch(permission)
            } catch (t: Throwable) {
                Log.w(TAG, "Permission request launch failed", t)
                pendingPermissionRequest = null
                try {
                    request.deny()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun buildDownloadListener(): DownloadListener =
        DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }

    // endregion

    // region Chrome UI wiring

    private fun setupChrome() {
        findViewById<View>(R.id.error_retry).setOnClickListener {
            hideErrorPanel()
            webView.reload()
        }
        findViewById<View>(R.id.error_browser).setOnClickListener {
            hideErrorPanel()
            openInBrowser(currentUrl())
        }
        findViewById<View>(R.id.error_change_server).setOnClickListener {
            showSetup(serverUrl)
        }
        hintBar.setOnClickListener { openInBrowser(currentUrl()) }
        // The hint bar is only shown while a sign-in is under way, which is
        // exactly where a stale or wrong session shows up. Long-press it to
        // open the session menu instead of clearing app data in Settings.
        hintBar.setOnLongClickListener {
            showSessionMenu()
            true
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
                return@addCallback
            }
            val now = System.currentTimeMillis()
            if (now - lastBackPressAt < BACK_EXIT_WINDOW_MS) {
                finish()
            } else {
                lastBackPressAt = now
                Toast.makeText(this@MainActivity, R.string.back_again_to_exit, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // region Server setup

    private fun setupSetupPanel() {
        findViewById<View>(R.id.server_connect).setOnClickListener { connect() }
        serverInput.setOnEditorActionListener { _, _, _ ->
            connect()
            true
        }
    }

    /** Applies the `server` intent extra (CI/test hook) and persists it. */
    private fun applyTestServer(intent: Intent?): String? {
        val raw = intent?.getStringExtra(EXTRA_SERVER)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val normalized = ServerUrl.normalize(raw)
        if (normalized == null) {
            Log.w(TAG, "SERVER extra ignored, not a usable address")
            return null
        }
        store.set(normalized)
        Log.i(TAG, "SERVER set from intent host=${ServerUrl.hostOf(normalized)}")
        return normalized
    }

    private fun connect() {
        val normalized = ServerUrl.normalize(serverInput.text.toString())
        if (normalized == null) {
            serverError.visibility = View.VISIBLE
            Log.w(TAG, "SERVER rejected input")
            return
        }
        serverError.visibility = View.GONE
        val previous = store.get()
        if (previous != null && previous != normalized) {
            // A different server is a different identity; do not carry a session over.
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
        store.set(normalized)
        Log.i(TAG, "SERVER saved host=${ServerUrl.hostOf(normalized)} scheme=${if (ServerUrl.isPlainHttp(normalized)) "http" else "https"}")
        startLoading(normalized)
    }

    private fun startLoading(url: String) {
        serverUrl = url
        hideSetup()
        hideErrorPanel()
        Log.i(TAG, "LOAD url=$url")
        webView.loadUrl(url)
    }

    private fun showSetup(prefill: String?) {
        setupPanel.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        webView.visibility = View.GONE
        hintBar.visibility = View.GONE
        progressBar.visibility = View.GONE
        serverError.visibility = View.GONE
        // Show the address exactly as it is stored: reconnecting must not silently
        // switch an http deployment to https through scheme inference.
        serverInput.setText(prefill.orEmpty())
        serverInput.setSelection(serverInput.text.length)
        Log.i(TAG, "SETUP shown")
    }

    private fun hideSetup() {
        setupPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    // endregion

    private fun showSessionMenu() {
        val items = arrayOf(
            getString(R.string.menu_reload),
            getString(R.string.menu_change_server),
            getString(R.string.open_in_browser),
            getString(R.string.shortcut_device_control),
            getString(R.string.clear_session)
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.session_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> reloadPage()
                    1 -> showSetup(serverUrl)
                    2 -> openInBrowser(currentUrl())
                    3 -> startActivity(Intent(this, DeviceControlActivity::class.java))
                    else -> clearSession()
                }
            }
            .show()
    }

    private fun reloadPage() {
        if (serverUrl == null) return
        hideErrorPanel()
        Log.i(TAG, "RELOAD requested")
        webView.reload()
    }

    /** Forgets every cookie, form entry and cached byte of this site, then starts over. */
    private fun clearSession() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearFormData()
            Log.i(TAG, "SESSION cleared")
        } catch (t: Throwable) {
            Log.w(TAG, "Session clear failed", t)
        }
        serverUrl?.let { webView.loadUrl(it) }
    }

    private fun showErrorPanel(description: CharSequence?) {
        errorReason.text =
            if (description.isNullOrBlank()) getString(R.string.unknown_error) else description
        errorPanel.visibility = View.VISIBLE
        webView.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun hideErrorPanel() {
        errorPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    /**
     * The hint bar appears whenever the WebView is showing a page that is not on
     * the configured server — i.e. a sign-in gate, an SSO provider or a consent
     * screen, which is exactly where a WebView can get stuck.
     */
    private fun updateHintBar(url: String) {
        val show = runCatching {
            val host = Uri.parse(url).host?.lowercase().orEmpty()
            host.isNotEmpty() && !isInternalHost(host)
        }.getOrDefault(false)
        val desired = if (show) View.VISIBLE else View.GONE
        if (hintBar.visibility != desired) {
            hintBar.visibility = desired
        }
    }

    private fun currentUrl(): String =
        runCatching { webView.url ?: serverUrl }.getOrNull() ?: serverUrl.orEmpty()

    /** The configured server and anything under it stays inside the app. */
    private fun isInternalHost(host: String): Boolean {
        val configured = ServerUrl.hostOf(serverUrl.orEmpty()) ?: return false
        return host.isNotEmpty() && (host == configured || host.endsWith(".$configured"))
    }

    // endregion

    // region Callback results

    private fun onFileChooserResult(result: ActivityResult) {
        val callback = filePathCallback ?: return
        filePathCallback = null
        val uris: Array<Uri>? =
            if (result.resultCode == Activity.RESULT_OK) extractUris(result.data) else null
        try {
            callback.onReceiveValue(uris)
        } catch (t: Throwable) {
            Log.w(TAG, "File chooser callback failed", t)
        }
    }

    private fun extractUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(i).uri)
            }
        }
        data.data?.let { uris.add(it) }
        return if (uris.isEmpty()) null else uris.toTypedArray()
    }

    private fun onPermissionResult(granted: Boolean) {
        val request = pendingPermissionRequest ?: return
        pendingPermissionRequest = null
        try {
            if (granted) request.grant(request.resources) else request.deny()
        } catch (t: Throwable) {
            Log.w(TAG, "Applying permission result failed", t)
        }
    }

    // endregion

    // region Downloads and external navigation

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (!mimeType.isNullOrBlank()) {
                request.setMimeType(mimeType)
            }
            if (!userAgent.isNullOrBlank()) {
                request.addRequestHeader("User-Agent", userAgent)
            }
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrBlank()) {
                request.addRequestHeader("Cookie", cookies)
            }
            downloadManager.enqueue(request)
            Log.i(TAG, "Download queued: $filename")
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(TAG, "Download failed", t)
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Hands the URL to the system's own default browser — whatever the user
     * chose in Android settings (Firefox, Chrome, Brave, ...). This app ships
     * no browser of its own and never prefers one: if a default is set, that
     * app opens; if not, Android shows its own "Open with" chooser.
     */
    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            startActivity(intent)
            val resolved = intent.resolveActivity(packageManager)?.packageName
            Log.i(TAG, "Opened $url in the default browser ($resolved)")
        } catch (t: Throwable) {
            Log.w(TAG, "No app could open $url", t)
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // endregion
}
