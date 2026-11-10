# hermes-pocket

**Install:** grab [`hermes-pocket-release.apk`](https://github.com/Zcc09/hermes-pocket/releases/latest)
and open it on the phone, allowing "install unknown apps" when asked. The app
updates itself from GitHub Releases afterwards.

hermes-pocket is a small native Android app that hosts **your own** Hermes
Agent web app in a single full-screen WebView. It ships no server and no
address: on the first launch you type yours (an IP and port on your network, or
a public URL), it is remembered, and everything after that is one tap. It is a
thin shell, not a reimplementation — every feature lives in your web app. The
app adds a dark, branded frame around it:

- Setup screen on first launch: type `192.168.1.20:9120` or `https://example.com`,
  Connect. Private addresses are opened over `http`, public ones over `https`;
  a full URL with a path (`https://example.com/hermes`) is kept as the base
- Thin 2dp load progress bar with an accent tint
- Native "Can't reach Hermes" error panel with Retry, Open in browser and
  Change server
- Bottom hint bar whenever the page is **not** on your server (i.e. a sign-in
  gate or SSO provider), which is where a WebView can get stuck
- Links stay in the app on your own server and through any redirect (a sign-in
  chain is never interrupted); a **tapped** link to another site opens in the
  phone's **default browser** — Firefox, Chrome, Brave, whatever you set. No
  browser is bundled, and none is preferred by the app
- Pull-to-refresh, runtime permission prompts for camera and microphone,
  file picker, download handling, and state save/restore on process death
- Background self-update check against the GitHub releases API
- Long-press the bottom hint bar for the session menu: Change server, Open in
  browser, or Clear session (cookies, storage, cache) — no need to clear app
  data in Settings when a session goes stale. **Change server** is also a
  launcher shortcut: long-press the app icon

## Project layout

```
hermes-pocket/
├── build.gradle.kts          # root: AGP 9.4.0 plugin only
├── settings.gradle.kts
├── gradle.properties         # android.builtInKotlin=true, JVM args
└── app/
    ├── build.gradle.kts      # module config, release signing, deps
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/zcc09/hermespocket/
        │   │   ├── MainActivity.kt   # WebView shell, setup screen, native chrome
        │   │   ├── ServerUrl.kt      # address normalisation (pure, unit-tested)
        │   │   ├── ServerStore.kt    # the saved address + update-check stamp
        │   │   ├── Updater.kt        # GitHub release self-update
        │   │   └── VersionTools.kt   # pure-Kotlin version compare
        │   └── res/                  # layouts, colors, strings, drawables
        └── test/java/com/zcc09/hermespocket/
            └── VersionToolsTest.kt   # JUnit4 unit tests
```

## Requirements

- JDK 21
- Android SDK (compileSdk 37)
- Gradle 9.x (this machine uses `C:/Users/Zcc09/tools/gradle-9.7.1/bin/gradle.bat`)

Kotlin is built into AGP 9 (`android.builtInKotlin=true` in
`gradle.properties`), so no separate Kotlin Gradle plugin is applied.

## Building locally

From the project root, in git-bash:

```bash
export JAVA_HOME=C:/Users/Zcc09/tools/re/jdk21
export ANDROID_HOME=C:/Users/Zcc09/tools/android-sdk
C:/Users/Zcc09/tools/gradle-9.7.1/bin/gradle.bat -p C:/Users/Zcc09/hermes-pocket :app:assembleDebug
```

Release build:

```bash
C:/Users/Zcc09/tools/gradle-9.7.1/bin/gradle.bat -p C:/Users/Zcc09/hermes-pocket :app:assembleRelease
```

Unit tests:

```bash
C:/Users/Zcc09/tools/gradle-9.7.1/bin/gradle.bat -p C:/Users/Zcc09/hermes-pocket :app:testDebugUnitTest
```

### Release signing

`app/build.gradle.kts` reads the keystore from environment variables so no
secret ever lands on disk or in the repo:

| Variable               | Meaning                                        |
|------------------------|------------------------------------------------|
| `KEYSTORE_BASE64`      | Base64-encoded PKCS12 keystore (required for a real release) |
| `KEYSTORE_PASSWORD`    | Keystore password (falls back to `KEYSTORE_PASS`, then `""`) |
| `KEY_ALIAS`            | Key alias (default `hermespocket`)             |
| `KEY_PASSWORD`         | Key password (falls back to `KEYSTORE_PASS`)   |

The keystore is decoded into the build directory as
`hermes-pocket-release.p12`. If `KEYSTORE_BASE64` is not set, the release
build falls back to the debug signing config so a fork without secrets still
produces an installable APK.

## Building in CI

`.github/workflows/android.yml` is the real pipeline (`gradle 9.7.1` via
`gradle/actions/setup-gradle`, JDK 21, `platforms;android-37` installed on the
runner). It runs in three jobs:

| Job | What it does |
|---|---|
| **Build + unit tests** | `:app:test`, `:app:assembleRelease`, `:app:assembleDebug`, then `apksigner verify --print-certs` and a `grep` on `CN=Hermes Pocket` so a debug-signed release can never ship silently |
| **Emulator smoke** | installs the debug APK on an API 34 emulator with a cleared app state, then asserts from the app's own logcat that the first launch shows the setup screen, that a server supplied as an intent extra is accepted and loaded, that the *next* launch goes straight to the saved server, that the user agent is not a WebView agent, and that nothing crashed or ANR'd. A neutral public page (`https://example.com/`) stands in as the server, so the suite never needs anyone's private address; screenshots come back as the `e2e-evidence` artifact |
| **Publish release** | on a `v*` tag or a dispatch with `tag_name`, attaches `hermes-pocket-release.apk` + `hermes-pocket-debug.apk` to the release |

The self-updater prefers the asset whose name contains `release`, so the
sideloaded APK updates from the release asset and never from the debug one.

## How the address and the sign-in flow work

1. No server is compiled in. With nothing saved, the app opens its setup screen;
   `ServerUrl.normalize` turns what you type into a base URL (scheme inferred,
   port kept, path kept, trailing slash added) and `ServerStore` saves it.
2. Every later launch loads that address directly. **Change server** (long-press
   the hint bar, the error panel, or the app icon's shortcut) opens the setup
   screen again, and switching server drops the old cookies first.
3. Whatever your server's auth gate does — a redirect to an SSO provider, a
   `/login` page on the same host, an email code — stays **inside** the WebView:
   same-host navigation is always internal and so is any redirect
   (`WebResourceRequest.hasGesture()` is false). Cookies are accepted
   third-party so the round trip completes.
   It is the user's own server or provider, so nothing provider-specific is
   hardcoded.
4. The user agent is scrubbed of the `"; wv"` token and any `"Version/x.y"`
   token, because identity providers refuse obvious WebView agents.
5. Whenever the page is not on your server, the bottom hint bar appears
   ("Trouble signing in? Open in your browser"); tapping it hands the URL to
   the phone's default browser, which is the escape hatch if a provider blocks
   embedded browsers.

## Known WebView limitations

- A provider that hard-blocks non-Chrome clients, or fingerprints WebView
  internals beyond the UA (canvas/TLS fingerprints, `navigator.webdriver`), may
  still refuse. The hint bar's default-browser handoff is the escape hatch.
- Cookies are not shared between the app's WebView and any external browser, so
  a session started in the browser does not carry over and vice versa.
- JavaScript popup windows (`window.open`) are not supported; flows that rely on
  popups fall back to same-page navigation.
- WebAuthn/passkey (biometric) sign-in options are generally unavailable in a
  WebView.
- Mixed-content pages are refused (`MIXED_CONTENT_NEVER_ALLOW`) on purpose.
- A plain `http://` address works but is unencrypted; use `https://` for
  anything beyond your own network (the setup helper says so).

## Self-update

On each launch (at most one check per 10 minutes) `Updater` queries
`https://api.github.com/repos/Zcc09/hermes-pocket/releases/latest`, compares
`tag_name` against `BuildConfig.VERSION_NAME` using numeric per-component
comparison (`1.10.0 > 1.9.0`), and offers an "Update available" dialog.
Installing downloads the APK into `cacheDir/updates/` (following up to six
redirects manually) and launches the system installer via FileProvider with
`application/vnd.android.package-archive`. Every step is wrapped in
try/catch; an update failure never crashes the app.
