package com.zcc09.hermespocket.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Which privilege tier the bridge commands run under.
 *
 * ROOT    — `su -c`, full system access. Detected by actually running `id`.
 * SHIZUKU — the Shizuku helper app grants shell (uid 2000) through its binder.
 * APP     — no special access: commands run as this app's own uid, which still
 *           covers its sandbox, media it can see, and non-privileged binaries.
 *
 * Detection always proves itself by running a command, never by trusting a
 * package name: a device with the Magisk app installed but no grant must report
 * APP, otherwise every later tool call fails in a confusing way.
 */
enum class Tier(val id: String) {
    ROOT("root"),
    SHIZUKU("shizuku"),
    APP("app");

    companion object {
        fun fromId(id: String?): Tier? = values().firstOrNull { it.id == id }
    }
}

data class ShellResult(
    val exit: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean = false,
)

/**
 * What the Shizuku helper can do right now, each fact from its own call:
 * the package check proves the app is installed, `pingBinder` proves its
 * service answers, and `checkSelfPermission` proves this app is allowed.
 *
 * `running` false means the other two facts are not answerable, so the UI
 * shows the running fact alone rather than guessing at permission.
 */
data class ShizukuState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val granted: Boolean = false,
) {
    /** Shizuku can actually lend shell access. */
    val ready: Boolean get() = running && granted
}

class Privilege(private val context: Context) {

    companion object {
        private const val TAG = "HermesPocket"
        private const val MAX_OUTPUT_BYTES = 256 * 1024

        /** The Shizuku manager app; its service is what lends shell access. */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** The permission Shizuku itself defines for client apps. */
        const val SHIZUKU_API_PERMISSION = "moe.shizuku.manager.permission.API_V23"

        /** Official download page, for phones without the Shizuku app. */
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }

    @Volatile
    private var cached: Tier? = null

    /** The tier this device can actually deliver right now. */
    fun detect(force: Boolean = false): Tier {
        cached?.let { if (!force) return it }
        val tier = when {
            canRoot() -> Tier.ROOT
            canShizuku() -> Tier.SHIZUKU
            else -> Tier.APP
        }
        cached = tier
        Log.i(TAG, "BRIDGE tier=${tier.id} root=${canRoot()} shizuku=${shizukuAvailable()}")
        return tier
    }

    fun capabilities(tier: Tier = detect()): Map<String, Boolean> = mapOf(
        "shell" to true,
        "files" to true,
        "screenshot" to true,
        "gestures" to (PocketAccessibility.isRunning() || tier != Tier.APP),
        "ui" to PocketAccessibility.isRunning(),
        "systemCommands" to (tier != Tier.APP),
    )

    // region detection

    private fun canRoot(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitFor(4, TimeUnit.SECONDS)
        val out = if (finished) process.inputStream.bufferedReader().readText() else ""
        if (!finished) process.destroyForcibly()
        finished && out.contains("uid=0")
    }.getOrDefault(false)

    private fun shizukuAvailable(): Boolean {
        val state = shizukuState()
        return state.running && state.granted
    }

    private fun canShizuku(): Boolean = shizukuAvailable()

    // endregion

    // region shizuku

    /**
     * Three facts, three calls, each one wrapped: the Shizuku API throws
     * `IllegalStateException` while its binder is not attached, and a phone
     * with Shizuku stopped is the normal case, not an error.
     */
    fun shizukuState(): ShizukuState {
        val running = try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (t: Throwable) {
            Log.w(TAG, "BRIDGE shizuku ping failed: ${t.javaClass.simpleName}")
            false
        }
        val installed = running || shizukuInstalled()
        val granted = running && try {
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Log.w(TAG, "BRIDGE shizuku permission check failed: ${t.javaClass.simpleName}")
            false
        }
        return ShizukuState(installed = installed, running = running, granted = granted)
    }

    /**
     * Is the Shizuku app on this phone? Android 11 hides packages this app did
     * not declare a `<queries>` entry for, but `checkPermission` resolves the
     * package without that filter and only throws when it is genuinely absent.
     * A live binder is proof enough on its own.
     */
    private fun shizukuInstalled(): Boolean = runCatching {
        context.packageManager.checkPermission(SHIZUKU_API_PERMISSION, SHIZUKU_PACKAGE)
    }.isSuccess

    /**
     * Asks Shizuku for its permission. Shizuku shows its own confirmation
     * dialog; the answer arrives on the listener from [watchShizukuPermission].
     */
    fun requestShizukuPermission(requestCode: Int): Boolean = try {
        rikka.shizuku.Shizuku.requestPermission(requestCode)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "BRIDGE shizuku requestPermission failed: ${t.javaClass.simpleName}")
        false
    }

    /**
     * Watches for the answer to [requestShizukuPermission]. Returns a function
     * that unregisters the listener, or null when Shizuku refused the listener
     * because its service is not there.
     */
    fun watchShizukuPermission(onResult: (granted: Boolean) -> Unit): (() -> Unit)? {
        val listener = try {
            rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, result ->
                onResult(result == PackageManager.PERMISSION_GRANTED)
            }.also { rikka.shizuku.Shizuku.addRequestPermissionResultListener(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "BRIDGE shizuku permission listener not registered: ${t.javaClass.simpleName}")
            return null
        }
        return {
            runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener) }
            Unit
        }
    }

    // endregion

    /**
     * Runs [cmd] under the best available tier. The command always goes through
     * `sh -c`, so pipes and redirection behave the way a user expects.
     */
    fun shell(cmd: String, timeoutMs: Long = 15_000): ShellResult {
        val tier = detect()
        return when (tier) {
            Tier.ROOT -> execProcess(ProcessBuilder("su", "-c", cmd), timeoutMs)
            Tier.SHIZUKU -> shizukuShell(cmd, timeoutMs)
            Tier.APP -> execProcess(ProcessBuilder("sh", "-c", cmd), timeoutMs)
        }
    }

    private fun shizukuShell(cmd: String, timeoutMs: Long): ShellResult = try {
        val shizuku = Class.forName("rikka.shizuku.Shizuku")
        val newProcess = shizuku.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        val process = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null)
        val remote = process as rikka.shizuku.ShizukuRemoteProcess
        if (!remote.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            remote.destroy()
            ShellResult(124, "", "timed out after ${timeoutMs}ms")
        } else {
            ShellResult(remote.exitValue(), cap(remote.inputStream.readBytes()), cap(remote.errorStream.readBytes()))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "BRIDGE shizuku exec failed, falling back to app tier", t)
        execProcess(ProcessBuilder("sh", "-c", cmd), timeoutMs)
    }

    private fun execProcess(builder: ProcessBuilder, timeoutMs: Long): ShellResult = try {
        val process = builder.start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = drain(process.inputStream, stdout)
        val errThread = drain(process.errorStream, stderr)
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(500)
            errThread.join(500)
            ShellResult(124, cap(stdout.toByteArray()), "timed out after ${timeoutMs}ms")
        } else {
            outThread.join(2000)
            errThread.join(2000)
            ShellResult(process.exitValue(), cap(stdout.toByteArray()), cap(stderr.toByteArray()))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "BRIDGE exec failed", t)
        ShellResult(127, "", t.message ?: "exec failed")
    }

    private fun drain(input: java.io.InputStream, sink: ByteArrayOutputStream): Thread =
        Thread {
            runCatching {
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    synchronized(sink) {
                        if (total < MAX_OUTPUT_BYTES) {
                            val room = MAX_OUTPUT_BYTES - total
                            sink.write(buffer, 0, minOf(read, room))
                            total += minOf(read, room)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

    private fun cap(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    private fun cap(reader: java.io.InputStream): String = cap(reader.readBytes())

    /** True when the command actually ran with the privileges the tier implies. */
    fun deviceInfo(): Map<String, Any> = mapOf(
        "model" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        "android" to Build.VERSION.RELEASE.orEmpty(),
        "sdk" to Build.VERSION.SDK_INT,
        "package" to context.packageName,
    )

    /**
     * Raw PNG of the current screen through `screencap`. Only the privileged
     * tiers can do this; the accessibility path lives in [PocketAccessibility]
     * and is preferred by the bridge when it is available.
     */
    fun screenshot(): ByteArray? {
        val tier = detect()
        if (tier == Tier.APP) return null
        return runCatching {
            val bytes = if (tier == Tier.ROOT) {
                val process = ProcessBuilder("su", "-c", "screencap -p").start()
                val data = process.inputStream.readBytes()
                process.waitFor(15, TimeUnit.SECONDS)
                data
            } else {
                val shizuku = Class.forName("rikka.shizuku.Shizuku")
                val newProcess = shizuku.getMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                val remote = newProcess.invoke(null, arrayOf("sh", "-c", "screencap -p"), null, null)
                    as rikka.shizuku.ShizukuRemoteProcess
                val data = remote.inputStream.readBytes()
                remote.waitFor(15, TimeUnit.SECONDS)
                data
            }
            bytes.takeIf { it.size in 8..MAX_OUTPUT_BYTES * 64 }
        }.getOrNull()
    }

    /** A cheap, honest self-test the app's own UI shows. */
    fun test(): Map<String, Any> {
        val tier = detect(force = true)
        val probe = shell("echo mount-check; id")
        return mapOf(
            "tier" to tier.id,
            "exit" to probe.exit,
            "output" to (probe.stdout + probe.stderr).trim().take(400),
            "caps" to capabilities(tier),
        )
    }

    fun externalFilesRoot(): File? = context.getExternalFilesDir(null)
}
