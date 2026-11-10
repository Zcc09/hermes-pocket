package com.zcc09.hermespocket.bridge

import android.content.Context
import com.zcc09.hermespocket.BuildConfig
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The HTTP/JSON control surface the Hermes agent talks to.
 *
 * Deliberately small and dependency-free: a plain ServerSocket, one thread per
 * connection, JSON in and out, and a PNG response for screenshots. Every route
 * is documented in docs/DEVICE-CONTROL.md and wrapped by tools/pocket.py.
 *
 * Security posture: bearer token on every request, bound to the phone's LAN
 * interfaces only while the user has switched it on, and a foreground
 * notification for as long as it runs. There is no unauthenticated route except
 * the health probe below, which returns no device detail.
 */
class HttpBridge(
    private val context: Context,
    private val config: BridgeConfig,
    private val privilege: Privilege,
) {

    companion object {
        private const val TAG = "HermesPocket"
        private const val MAX_BODY_BYTES = 16 * 1024 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private val STARTED_AT = System.currentTimeMillis()
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "pocket-bridge").apply { isDaemon = true }
    }

    fun isRunning(): Boolean = running.get()

    fun start(): Boolean {
        if (running.get()) return true
        return runCatching {
            val socket = ServerSocket(config.port, 16, null)
            serverSocket = socket
            running.set(true)
            pool.execute { acceptLoop(socket) }
            Log.i(TAG, "BRIDGE listening on ${config.port}")
            true
        }.getOrElse {
            Log.w(TAG, "BRIDGE failed to bind ${config.port}", it)
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(TAG, "BRIDGE stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            pool.execute { handle(client) }
        }
    }

    // region connection handling

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 20_000
            val request = readRequest(client.getInputStream()) ?: return
            val response = route(request)
            writeResponse(client.getOutputStream(), response)
        } catch (t: Throwable) {
            Log.w(TAG, "BRIDGE request failed", t)
        } finally {
            runCatching { client.close() }
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private data class Response(val code: Int, val type: String, val bytes: ByteArray, val headers: Map<String, String> = emptyMap())

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArray(MAX_HEADER_BYTES)
        var length = 0
        // Read byte by byte up to the blank line: a buffered reader would swallow
        // the first bytes of the body.
        while (length < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            head[length++] = b.toByte()
            if (length >= 4 && head[length - 4] == 13.toByte() && head[length - 3] == 10.toByte() &&
                head[length - 2] == 13.toByte() && head[length - 1] == 10.toByte()
            ) break
        }
        val headText = String(head, 0, length, Charsets.ISO_8859_1)
        val lines = headText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestLine = lines.first().split(" ")
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val target = requestLine[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                Uri.decode(key) to Uri.decode(value)
            }
        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null
            else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
        }.toMap()
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val capped = contentLength.coerceAtMost(MAX_BODY_BYTES)
            val buffer = ByteArray(capped)
            var read = 0
            while (read < capped) {
                val n = input.read(buffer, read, capped - read)
                if (n <= 0) break
                read += n
            }
            buffer.copyOf(read)
        } else ByteArray(0)
        return Request(method, path, query, headers, body)
    }

    private fun writeResponse(output: OutputStream, response: Response) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(response.code).append(' ').append(statusText(response.code)).append("\r\n")
        builder.append("Content-Type: ").append(response.type).append("\r\n")
        builder.append("Content-Length: ").append(response.bytes.size).append("\r\n")
        builder.append("Cache-Control: no-store\r\n")
        builder.append("Connection: close\r\n")
        response.headers.forEach { (k, v) -> builder.append(k).append(": ").append(v).append("\r\n") }
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(response.bytes)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    // endregion

    // region routing

    private fun route(request: Request): Response {
        // Health probe stays open so a client can tell "wrong token" from "not running".
        if (request.method == "GET" && request.path == "/v1/health") {
            return json(200, JSONObject().put("ok", true).put("app", "hermes-pocket"))
        }
        if (!authorized(request)) {
            return json(401, JSONObject().put("ok", false).put("error", "bad or missing token"))
        }
        val tier = effectiveTier()
        return try {
            when {
                request.method == "GET" && request.path == "/v1/status" -> status(tier)
                request.method == "POST" && request.path == "/v1/shell" -> shell(request.body)
                request.method == "GET" && request.path == "/v1/fs" -> fsRead(request.query)
                request.method == "PUT" && request.path == "/v1/fs" -> fsWrite(request.body)
                request.method == "GET" && request.path == "/v1/screenshot" -> screenshot(request.query)
                request.method == "GET" && request.path == "/v1/ui" -> ui()
                request.method == "POST" && request.path == "/v1/input" -> input(request.body)
                request.method == "POST" && request.path == "/v1/app" -> apps(request.body)
                request.method == "POST" && request.path == "/v1/intent" -> intent(request.body)
                else -> json(404, JSONObject().put("ok", false).put("error", "no such route ${request.method} ${request.path}"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BRIDGE handler threw", t)
            json(500, JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName))
        }
    }

    private fun authorized(request: Request): Boolean {
        val header = request.headers["authorization"] ?: return false
        val presented = header.removePrefix("Bearer ").removePrefix("bearer ").trim()
        val expected = config.token
        if (presented.length != expected.length) return false
        var diff = 0
        for (i in presented.indices) diff = diff or (presented[i].code xor expected[i].code)
        return diff == 0
    }

    private fun effectiveTier(): Tier {
        val preferred = Tier.fromId(config.tierPreference)
        return preferred ?: privilege.detect()
    }

    // endregion

    // region handlers

    private fun status(tier: Tier): Response {
        val payload = JSONObject()
            .put("ok", true)
            .put("app", "hermes-pocket")
            .put("version", BuildConfig.VERSION_NAME)
            .put("tier", tier.id)
            .put("tiers", JSONObject()
                .put("root", tier == Tier.ROOT)
                .put("shizuku", tier == Tier.SHIZUKU)
                .put("shell", true))
            .put("caps", JSONObject(privilege.capabilities(tier) as Map<*, *>))
            .put("device", JSONObject(privilege.deviceInfo() as Map<*, *>))
            .put("bridge", JSONObject()
                .put("port", config.port)
                .put("since", STARTED_AT / 1000L))
        return json(200, payload)
    }

    private fun shell(body: ByteArray): Response {
        val json = JSONObject(String(body, Charsets.UTF_8))
        val cmd = json.optString("cmd").trim()
        if (cmd.isEmpty()) return json(400, JSONObject().put("ok", false).put("error", "cmd is required"))
        val timeout = json.optLong("timeoutMs", 15_000L).coerceIn(1_000L, 120_000L)
        val result = privilege.shell(cmd, timeout)
        return json(200, JSONObject()
            .put("ok", true)
            .put("exit", result.exit)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("truncated", result.truncated))
    }

    private fun fsRead(query: Map<String, String>): Response {
        val op = query["op"] ?: "list"
        val path = query["path"] ?: return json(400, JSONObject().put("ok", false).put("error", "path is required"))
        val file = File(path)
        return when (op) {
            "list" -> {
                val children = file.listFiles()
                    ?: return json(404, JSONObject().put("ok", false).put("error", "not a readable directory: $path"))
                val entries = JSONArray()
                children.sortedBy { it.name.lowercase() }.take(2000).forEach { child ->
                    entries.put(JSONObject()
                        .put("name", child.name)
                        .put("dir", child.isDirectory)
                        .put("size", if (child.isDirectory) 0 else child.length())
                        .put("mtime", child.lastModified() / 1000L))
                }
                json(200, JSONObject().put("ok", true).put("path", file.absolutePath).put("entries", entries))
            }
            "stat" -> json(200, JSONObject()
                .put("ok", true)
                .put("exists", file.exists())
                .put("dir", file.isDirectory)
                .put("size", if (file.isFile) file.length() else 0)
                .put("mtime", file.lastModified() / 1000L))
            "read" -> {
                if (!file.isFile) return json(404, JSONObject().put("ok", false).put("error", "not a file: $path"))
                if (file.length() > MAX_BODY_BYTES) {
                    return json(413, JSONObject().put("ok", false).put("error", "file larger than 16 MB"))
                }
                val bytes = file.readBytes()
                val payload = JSONObject().put("ok", true).put("path", file.absolutePath)
                if (query["b64"] == "1") {
                    payload.put("content_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                } else {
                    payload.put("content", String(bytes, Charsets.UTF_8))
                }
                json(200, payload)
            }
            else -> json(400, JSONObject().put("ok", false).put("error", "unknown op $op"))
        }
    }

    private fun fsWrite(body: ByteArray): Response {
        val json = JSONObject(String(body, Charsets.UTF_8))
        val path = json.optString("path")
        if (path.isEmpty()) return json(400, JSONObject().put("ok", false).put("error", "path is required"))
        val encoded = json.optString("content_b64")
        val bytes = if (encoded.isNotEmpty()) {
            Base64.decode(encoded, Base64.NO_WRAP)
        } else {
            json.optString("content").toByteArray(Charsets.UTF_8)
        }
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return json(200, JSONObject().put("ok", true).put("path", file.absolutePath).put("bytes", bytes.size))
    }

    private fun screenshot(query: Map<String, String>): Response {
        val max = query["max"]?.toIntOrNull() ?: 1280
        val tier = effectiveTier()
        // Prefer the accessibility path: it needs no process and works on an
        // unrooted phone. Fall back to `screencap` when a privileged tier exists.
        val fromA11y = PocketAccessibility.runningInstance()?.screenshot(max)
        if (fromA11y != null) return Response(200, "image/png", fromA11y)
        if (tier != Tier.APP) {
            val result = privilege.screenshot()
            if (result != null) return Response(200, "image/png", result)
        }
        return json(503, JSONObject()
            .put("ok", false)
            .put("error", "screenshot unavailable: enable the Pocket accessibility service, or grant root/Shizuku")
            .put("tier", tier.id))
    }

    private fun ui(): Response {
        val dump = PocketAccessibility.runningInstance()?.dumpUi()
            ?: return json(503, JSONObject().put("ok", false).put("error", "UI tree needs the Pocket accessibility service"))
        return json(200, JSONObject().put("ok", true).put("xml", dump))
    }

    private fun input(body: ByteArray): Response {
        val json = JSONObject(String(body, Charsets.UTF_8))
        val action = json.optString("action")
        val a11y = PocketAccessibility.runningInstance()
        when (action) {
            "tap" -> {
                val x = json.optDouble("x", -1.0).toFloat()
                val y = json.optDouble("y", -1.0).toFloat()
                if (x < 0 || y < 0) return json(400, JSONObject().put("ok", false).put("error", "x and y are required"))
                val viaA11y = a11y?.tap(x, y) == true
                return if (viaA11y) json(200, JSONObject().put("ok", true).put("via", "accessibility"))
                else fallbackInput("input tap ${x.toInt()} ${y.toInt()}")
            }
            "swipe" -> {
                val x1 = json.optInt("x1", -1); val y1 = json.optInt("y1", -1)
                val x2 = json.optInt("x2", -1); val y2 = json.optInt("y2", -1)
                if (minOf(x1, y1, x2, y2) < 0) {
                    return json(400, JSONObject().put("ok", false).put("error", "x1, y1, x2, y2 are required"))
                }
                val ms = json.optInt("ms", 300).coerceIn(60, 8_000)
                val viaA11y = a11y?.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), ms.toLong()) == true
                return if (viaA11y) json(200, JSONObject().put("ok", true).put("via", "accessibility"))
                else fallbackInput("input swipe $x1 $y1 $x2 $y2 $ms")
            }
            "text" -> {
                val text = json.optString("text")
                if (text.isEmpty()) return json(400, JSONObject().put("ok", false).put("error", "text is required"))
                if (a11y?.typeText(text) == true) {
                    return json(200, JSONObject().put("ok", true).put("via", "accessibility"))
                }
                val escaped = text.replace(" ", "%s")
                return fallbackInput("input text '$escaped'")
            }
            "key" -> {
                val key = json.optString("key").uppercase()
                if (key.isEmpty()) return json(400, JSONObject().put("ok", false).put("error", "key is required"))
                val global = when (key) {
                    "BACK" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    "HOME" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                    "RECENTS", "APP_SWITCH" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                    "NOTIFICATIONS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    else -> null
                }
                if (global != null && a11y?.globalAction(global) == true) {
                    return json(200, JSONObject().put("ok", true).put("via", "accessibility"))
                }
                return fallbackInput("input keyevent $key")
            }
            else -> return json(400, JSONObject().put("ok", false).put("error", "unknown action $action"))
        }
    }

    private fun fallbackInput(command: String): Response {
        val result = privilege.shell(command, 10_000)
        return if (result.exit == 0) {
            json(200, JSONObject().put("ok", true).put("via", "shell"))
        } else {
            json(503, JSONObject()
                .put("ok", false)
                .put("error", "input needs the Pocket accessibility service (or root/Shizuku): ${result.stderr.ifBlank { result.stdout }.trim()}")
                .put("tier", effectiveTier().id))
        }
    }

    private fun apps(body: ByteArray): Response {
        val json = JSONObject(String(body, Charsets.UTF_8))
        val pm = context.packageManager
        return when (json.optString("action")) {
            "list" -> {
                val apps = JSONArray()
                pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                    .forEach { app ->
                        apps.put(JSONObject()
                            .put("package", app.packageName)
                            .put("label", pm.getApplicationLabel(app).toString()))
                    }
                json(200, JSONObject().put("ok", true).put("apps", apps))
            }
            "launch" -> {
                val target = json.optString("package")
                val intent = pm.getLaunchIntentForPackage(target)
                    ?: return json(404, JSONObject().put("ok", false).put("error", "no launch intent for $target"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                json(200, JSONObject().put("ok", true).put("launched", target))
            }
            "stop" -> {
                val target = json.optString("package")
                val result = privilege.shell("am force-stop $target", 10_000)
                if (result.exit == 0) json(200, JSONObject().put("ok", true).put("stopped", target))
                else json(503, JSONObject().put("ok", false).put("error", "stopping apps needs root or Shizuku"))
            }
            else -> json(400, JSONObject().put("ok", false).put("error", "unknown action"))
        }
    }

    private fun intent(body: ByteArray): Response {
        val json = JSONObject(String(body, Charsets.UTF_8))
        val intent = when (json.optString("action")) {
            "view" -> {
                val url = json.optString("url")
                if (url.isEmpty()) return json(400, JSONObject().put("ok", false).put("error", "url is required"))
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            "send" -> {
                val text = json.optString("text")
                if (text.isEmpty()) return json(400, JSONObject().put("ok", false).put("error", "text is required"))
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            }
            else -> return json(400, JSONObject().put("ok", false).put("error", "unknown action"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            json(200, JSONObject().put("ok", true))
        }.getOrElse {
            json(400, JSONObject().put("ok", false).put("error", "nothing accepted the intent: ${it.message}"))
        }
    }

    // endregion

    private fun json(code: Int, body: JSONObject): Response =
        Response(code, "application/json; charset=utf-8", body.toString().toByteArray(Charsets.UTF_8))

    /** The address a client should use, or null when the phone has no LAN IPv4. */
    fun lanUrl(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
            ?.let { "http://$it:${config.port}" }
    }.getOrNull()
}
