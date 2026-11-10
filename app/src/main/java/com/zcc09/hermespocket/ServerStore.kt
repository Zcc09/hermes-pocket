package com.zcc09.hermespocket

import android.content.Context

/**
 * Where this install points. The address is always the user's own: nothing is
 * shipped as a default, and `null` means the setup screen has not been finished.
 */
class ServerStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }

    fun set(url: String) {
        prefs.edit().putString(KEY_SERVER, url).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SERVER).apply()
    }

    /** Last update check, used by [Updater] to throttle itself. */
    fun lastUpdateCheck(): Long = prefs.getLong(KEY_UPDATE_CHECK, 0L)

    fun setLastUpdateCheck(atMillis: Long) {
        prefs.edit().putLong(KEY_UPDATE_CHECK, atMillis).apply()
    }

    private companion object {
        const val PREFS = "hermes-pocket"
        const val KEY_SERVER = "server_url"
        const val KEY_UPDATE_CHECK = "last_update_check_ms"
    }
}
