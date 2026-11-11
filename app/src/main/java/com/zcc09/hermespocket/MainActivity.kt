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
import android.os.Message
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

        /** Settings fires this so cookie clearing keeps one implementation. */
        const val ACTION_CLEAR_SESSION = "com.zcc09.hermespocket.CLEAR_SESSION"

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

        renderHost()
        widenSettingsTarget()
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
        if (intent.action == ACTION_CLEAR_SESSION) {
            clearSession()
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
            trackSignIn(url)
            val redirect = android.os.Build.VERSION.SDK_INT >= 24 && request.isRedirect
            val leaves = NavigationPolicy.leavesApp(
                url = url.toString(),
                serverHost = serverUrlHost(),
                hasGesture = request.hasGesture(),
                isRedirect = redirect,
                signInInFlight = signInInFlight,
            )
            Log.i(
                TAG,
                "NAV host=${url.host} gesture=${request.hasGesture()} redirect=$redirect " +
                    "signIn=$signInInFlight leaves=$leaves",
            )
            if (!leaves) return false
            openInBrowser(url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            trackSignIn(Uri.parse(url))
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

        /**
         * A popup is how some sign-in providers open their step. Without this the
         * click looks dead; with it the popup's first URL loads in the same
         * WebView, so the whole round trip keeps one cookie jar.
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val probe = WebView(this@MainActivity)
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(inner: WebView, request: WebResourceRequest): Boolean {
                    Log.i(TAG, "NAV popup -> ${request.url}")
                    trackSignIn(request.url)
                    webView.loadUrl(request.url.toString())
                    inner.destroy()
                    return true
                }
            }
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }


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
        findViewById<View>(R.id.button_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
        renderHost()
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
        renderHost()
        setupPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    // endregion

    private fun showSessionMenu() {
        // Settings owns the server, the sign-out and device control; the menu
        // keeps only what belongs to the page in front of you.
        val items = arrayOf(
            getString(R.string.menu_reload),
            getString(R.string.menu_settings),
            getString(R.string.open_in_browser)
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.session_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> reloadPage()
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                    else -> openInBrowser(currentUrl())
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

    /**
     * True from the moment a sign-in starts until a page of the app itself comes
     * back. While it is set nothing may leave for the browser, because the next
     * hop of the round trip needs the cookie only this WebView has.
     */
    private var signInInFlight = false

    private fun serverUrlHost(): String? =
        store.get()?.let { runCatching { java.net.URI(it).host?.lowercase() }.getOrNull() }

    private fun trackSignIn(url: Uri) {
        val host = url.host?.lowercase().orEmpty()
        val serverHost = serverUrlHost() ?: return
        if (host != serverHost) return
        val path = url.path
        signInInFlight = if (NavigationPolicy.isSignInPath(path)) {
            Log.i(TAG, "SIGNIN started path=$path")
            true
        } else {
            if (signInInFlight) Log.i(TAG, "SIGNIN back on the app at path=$path")
            false
        }
    }

    /**
     * The session bar always says which server this app is pointed at. A plain
     * http deployment is called out, because on a phone "no lock" is the whole
     * difference between the two and the host alone would hide it.
     */
    private fun renderHost() {
        val view = findViewById<TextView>(R.id.text_host)
        val stored = store.get()
        view.text = when {
            stored == null -> getString(R.string.settings_server_none)
            ServerUrl.isPlainHttp(stored) -> getString(R.string.session_host_plain, ServerUrl.hostPortOf(stored))
            else -> ServerUrl.hostPortOf(stored)
        }
    }

    /**
     * The gear is 32dp wide inside a 28dp bar; a TouchDelegate widens it to the
     * 48dp target a thumb expects without growing the chrome.
     */
    private fun widenSettingsTarget() {
        val bar = findViewById<View>(R.id.session_bar)
        val gear = findViewById<View>(R.id.button_settings)
        bar.post {
            val rect = android.graphics.Rect()
            gear.getHitRect(rect)
            val dx = (48 - rect.width()).coerceAtLeast(0) / 2
            val dy = (48 - rect.height()).coerceAtLeast(0) / 2
            rect.inset(-dx, -dy)
            bar.touchDelegate = android.view.TouchDelegate(rect, gear)
        }
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
        // While a sign-in round trip is live the app holds the whole chain, so
        // offering "open in your browser" here would hand the flow to a browser
        // that has none of its cookies. The bar returns for foreign pages the
        // app is merely showing, where the escape hatch is useful.
        val show = runCatching {
            if (signInInFlight) return@runCatching false
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
