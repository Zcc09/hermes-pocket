package com.zcc09.hermespocket.bridge

import android.content.Context
import java.security.SecureRandom

/**
 * Everything the device-control bridge needs to persist: whether it runs, on
 * which port, the bearer token that unlocks it, and which privilege tier the
 * user asked for.
 *
 * The token is generated on the device and never leaves it except when the user
 * copies it into their agent. Regenerating it invalidates every client.
 */
class BridgeConfig(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_PORT
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** "auto", "root", "shizuku" or "app". */
    var tierPreference: String
        get() = prefs.getString(KEY_TIER, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_TIER, value).apply()

    /** Post "Hermes replied" notifications to this phone. On unless the user turns it off. */
    var replyNotifications: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY, value).apply()

    var token: String
        get() {
            val existing = prefs.getString(KEY_TOKEN, null)
            if (existing != null && existing.length >= 32) return existing
            val fresh = newToken()
            prefs.edit().putString(KEY_TOKEN, fresh).apply()
            return fresh
        }
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    fun regenerateToken(): String = newToken().also { token = it }

    /** A fresh 32-hex-character token (128 bits). */
    fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_PORT = 8737
        private const val PREFS = "hermes-pocket-bridge"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_TIER = "tier"
        private const val KEY_NOTIFY = "reply_notifications"
    }
}
