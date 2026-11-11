package com.zcc09.hermespocket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decided a login was a "link to another site" and handed the
 * OAuth hop to the system browser, where the PKCE cookie does not exist.
 */
class NavigationPolicyTest {

    private val server = "hermes.mojangle.net"

    @Test
    fun aTappedLinkToAnotherSiteLeavesTheApp() {
        assertTrue(
            NavigationPolicy.leavesApp(
                "https://example.com/article",
                serverHost = server,
                hasGesture = true,
                isRedirect = false,
                signInInFlight = false,
            ),
        )
    }

    @Test
    fun theOwnServerNeverLeaves() {
        assertFalse(
            NavigationPolicy.leavesApp(
                "https://hermes.mojangle.net/sessions",
                serverHost = server,
                hasGesture = true,
                isRedirect = false,
                signInInFlight = false,
            ),
        )
    }

    @Test
    fun aRedirectNeverLeavesEvenWithAGesture() {
        // A form submit on the provider page is both a gesture and a redirect;
        // letting it out is what emptied the cookie jar.
        assertFalse(
            NavigationPolicy.leavesApp(
                "https://portal.nousresearch.com/oauth/authorize?x=1",
                serverHost = server,
                hasGesture = true,
                isRedirect = true,
                signInInFlight = true,
            ),
        )
    }

    @Test
    fun nothingLeavesWhileASignInIsUnderWay() {
        assertFalse(
            NavigationPolicy.leavesApp(
                "https://portal.nousresearch.com/sign-in",
                serverHost = server,
                hasGesture = true,
                isRedirect = false,
                signInInFlight = true,
            ),
        )
    }

    @Test
    fun aTypedOrScriptedNavigationStays() {
        assertFalse(
            NavigationPolicy.leavesApp(
                "https://example.com/",
                serverHost = server,
                hasGesture = false,
                isRedirect = false,
                signInInFlight = false,
            ),
        )
    }

    @Test
    fun anUnknownTargetStaysInPlace() {
        assertFalse(
            NavigationPolicy.leavesApp(
                "about:blank", serverHost = server, hasGesture = true,
                isRedirect = false, signInInFlight = false,
            ),
        )
        assertFalse(
            NavigationPolicy.leavesApp(
                "https://example.com/", serverHost = null, hasGesture = false,
                isRedirect = false, signInInFlight = false,
            ),
        )
    }

    @Test
    fun signInPathsAreRecognised() {
        assertTrue(NavigationPolicy.isSignInPath("/auth/login"))
        assertTrue(NavigationPolicy.isSignInPath("/auth/callback"))
        assertTrue(NavigationPolicy.isSignInPath("/login"))
        assertTrue(NavigationPolicy.isSignInPath("/oauth/authorize"))
        assertTrue(NavigationPolicy.isSignInPath("/AUTH/LOGIN"))
        assertFalse(NavigationPolicy.isSignInPath("/"))
        assertFalse(NavigationPolicy.isSignInPath("/sessions"))
        assertFalse(NavigationPolicy.isSignInPath(null))
    }
}
