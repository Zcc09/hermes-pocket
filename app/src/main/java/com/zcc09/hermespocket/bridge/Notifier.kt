package com.zcc09.hermespocket.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zcc09.hermespocket.MainActivity
import com.zcc09.hermespocket.R

/**
 * Posts "Hermes replied" notifications on behalf of the agent.
 *
 * Two rules keep this from becoming noise:
 * - it stays quiet while the app is on screen (the user is reading the reply),
 *   unless the caller asks for `force`;
 * - every reply reuses its session's tag, so ten replies replace one banner
 *   instead of stacking ten.
 */
object Notifier {

    private const val TAG = "HermesPocket"
    private const val CHANNEL_REPLIES = "hermes-replies"
    private const val MAX_TITLE = 64
    private const val MAX_BODY = 400

    /** Set by the activities: true while one of them is resumed. */
    @Volatile
    var appVisible: Boolean = false

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_REPLIES) != null) return
        val channel = NotificationChannel(
            CHANNEL_REPLIES,
            context.getString(R.string.notify_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notify_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Posts [body] under [title]. Returns whether a notification was shown, and
     * when it was not, why — the reason travels back to the agent so a silent
     * phone never looks like a broken bridge.
     */
    fun post(
        context: Context,
        title: String,
        body: String,
        tag: String?,
        force: Boolean,
    ): Result {
        val config = BridgeConfig(context)
        if (!config.replyNotifications) return Result(false, "disabled")
        if (!canPost(context)) return Result(false, "permission")
        if (appVisible && !force) return Result(false, "foreground")

        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return Result(false, "unavailable")

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cleanTitle = title.trim().ifEmpty { "Hermes" }.take(MAX_TITLE)
        val cleanBody = body.trim().take(MAX_BODY)
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_REPLIES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(cleanTitle)
            .setContentText(cleanBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        return runCatching {
            manager.notify(tag?.takeIf { it.isNotBlank() } ?: CLEAR_TAG, NOTIFICATION_ID, notification)
            Log.i(TAG, "NOTIFY posted len=${cleanBody.length}")
            Result(true, null)
        }.getOrElse {
            Log.w(TAG, "NOTIFY failed", it)
            Result(false, it.message ?: "error")
        }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }

    private const val NOTIFICATION_ID = 8100
    private const val CLEAR_TAG = "hermes-reply"

    data class Result(val posted: Boolean, val reason: String?)
}
