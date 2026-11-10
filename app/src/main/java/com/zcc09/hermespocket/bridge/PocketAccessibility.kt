package com.zcc09.hermespocket.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional accessibility service. It is what makes taps, swipes, typing, the UI
 * tree and screenshots work on a phone with no root and no Shizuku, and it makes
 * the same features work without spawning processes on a rooted one.
 *
 * The user opts in from Android settings; nothing here runs unless they did.
 */
class PocketAccessibility : AccessibilityService() {

    companion object {
        private const val TAG = "HermesPocket"

        @Volatile
        private var instance: PocketAccessibility? = null

        fun isRunning(): Boolean = instance != null

        fun runningInstance(): PocketAccessibility? = instance
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pocket-a11y").apply { isDaemon = true }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "A11Y connected")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "A11Y disconnected")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // region screenshots

    /** PNG bytes of the current screen, or null when unavailable. */
    fun screenshot(maxWidth: Int): ByteArray? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null else screenshotApi30(maxWidth)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun screenshotApi30(maxWidth: Int): ByteArray? {
        val latch = CountDownLatch(1)
        var png: ByteArray? = null
        runCatching {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        runCatching {
                            val buffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?: return@runCatching
                            val scaled = scale(bitmap, maxWidth)
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                            png = out.toByteArray()
                            if (scaled !== bitmap) scaled.recycle()
                            bitmap.recycle()
                            buffer.close()
                        }.onFailure { Log.w(TAG, "A11Y screenshot decode failed", it) }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "A11Y screenshot failed code=$errorCode")
                        latch.countDown()
                    }
                }
            )
        }.onFailure {
            Log.w(TAG, "A11Y takeScreenshot threw", it)
            latch.countDown()
        }
        latch.await(4, TimeUnit.SECONDS)
        return png
    }

    private fun scale(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (maxWidth <= 0 || bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(
            bitmap,
            maxWidth,
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    // endregion

    // region ui tree

    /** A depth- and size-capped XML dump of the window, similar to `uiautomator dump`. */
    fun dumpUi(maxNodes: Int = 2000): String {
        val root = rootInActiveWindow ?: return "<hierarchy/>"
        val builder = StringBuilder(8192)
        builder.append("<hierarchy rotation=\"").append(0).append("\">\n")
        var budget = maxNodes
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || budget <= 0 || depth > 40) return
            budget--
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            builder.append("  ".repeat(depth + 1)).append("<node")
            builder.append(" class=\"").append(escape(node.className)).append('"')
            builder.append(" text=\"").append(escape(node.text)).append('"')
            builder.append(" desc=\"").append(escape(node.contentDescription)).append('"')
            builder.append(" id=\"").append(escape(node.viewIdResourceName)).append('"')
            builder.append(" clickable=\"").append(node.isClickable).append('"')
            builder.append(" enabled=\"").append(node.isEnabled).append('"')
            builder.append(" bounds=\"[").append(bounds.left).append(',').append(bounds.top)
                .append("][").append(bounds.right).append(',').append(bounds.bottom).append("]\"")
            builder.append(" />\n")
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        builder.append("</hierarchy>")
        return builder.toString()
    }

    private fun escape(value: CharSequence?): String =
        value?.toString()?.replace("&", "&amp;")?.replace("\"", "&quot;")
            ?.replace("<", "&lt;")?.replace(">", "&gt;") ?: ""

    // endregion

    // region gestures

    fun tap(x: Float, y: Float): Boolean = gesture(
        Path().apply { moveTo(x, y) }, 0L, 60L
    )

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean = gesture(
        Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0L, durationMs.coerceIn(60L, 8_000L)
    )

    fun longPress(x: Float, y: Float, holdMs: Long = 600L): Boolean = gesture(
        Path().apply { moveTo(x, y) }, 0L, holdMs.coerceIn(400L, 8_000L)
    )

    private fun gesture(path: Path, startMs: Long, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        runCatching {
            dispatchGesture(description, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
        }.onFailure {
            Log.w(TAG, "A11Y gesture threw", it)
            latch.countDown()
        }
        latch.await(durationMs + 3_000L, TimeUnit.MILLISECONDS)
        return completed
    }

    /** Types into the focused field by pasting through the node's action, if it offers one. */
    fun typeText(text: String): Boolean {
        val focused = findFocusedEditable(rootInActiveWindow) ?: return false
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFocusedEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    // endregion
}
