package com.zcc09.hermespocket

/**
 * Decides whether a navigation leaves the app for the system browser.
 *
 * The app exists to be the server's own window, so only a link the person
 * actually tapped should ever leave it. Redirects must never leave: a sign-in
 * round trip is a chain of them, and each one carries the cookie the next hop
 * needs — hand a hop to another browser and the chain dies with the browser's
 * empty cookie jar (which is exactly how a login ends at "Missing PKCE state
 * cookie").
 *
 * Pure Kotlin so the rules are unit-tested instead of discovered on a phone.
 */
object NavigationPolicy {

    private val SIGN_IN_PATHS = listOf("/auth/", "/login", "/oauth", "/sso")

    /** True when this path belongs to a sign-in round trip the app must keep. */
    fun isSignInPath(path: String?): Boolean {
        val clean = path.orEmpty().lowercase()
        if (clean.isEmpty()) return false
        return SIGN_IN_PATHS.any { clean.startsWith(it) }
    }

    /**
     * True when the navigation must be handed to the default browser.
     *
     * @param url the target address
     * @param serverHost the host of the configured server, lowercase, or null
     * @param hasGesture the user tapped the link (not a redirect, not a script)
     * @param isRedirect the WebView reports this as a redirect of an earlier request
     * @param signInInFlight a sign-in round trip has started and has not returned
     */
    fun leavesApp(
        url: String,
        serverHost: String?,
        hasGesture: Boolean,
        isRedirect: Boolean,
        signInInFlight: Boolean,
    ): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
        if (host.isEmpty()) return false
        // The user's own server always stays in place.
        if (serverHost != null && host == serverHost) return false
        // A redirect is the server talking, not the person.
        if (isRedirect) return false
        // Nothing may leave while a sign-in is under way: the next hop needs the
        // cookie this WebView holds, and another browser does not have it.
        if (signInInFlight) return false
        // Only a tapped link to somewhere else goes to the browser.
        return hasGesture
    }
}
