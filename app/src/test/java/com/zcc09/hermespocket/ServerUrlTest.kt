package com.zcc09.hermespocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    @Test
    fun publicNameGetsHttps() {
        assertEquals("https://hermes.example.com/", ServerUrl.normalize("hermes.example.com"))
        assertEquals("https://example.com/", ServerUrl.normalize("https://example.com"))
        assertEquals("https://example.com/", ServerUrl.normalize("  https://example.com/  "))
    }

    @Test
    fun privateAddressGetsHttp() {
        assertEquals("http://192.168.1.20:9120/", ServerUrl.normalize("192.168.1.20:9120"))
        assertEquals("http://10.0.0.5/", ServerUrl.normalize("10.0.0.5"))
        assertEquals("http://127.0.0.1:8080/", ServerUrl.normalize("127.0.0.1:8080"))
        assertEquals("http://localhost:9120/", ServerUrl.normalize("localhost:9120"))
        assertEquals("http://nas.lan/", ServerUrl.normalize("nas.lan"))
        // A single label is a machine on the local network, never a public name.
        assertEquals("http://homeserver/", ServerUrl.normalize("homeserver"))
    }

    @Test
    fun explicitSchemeWins() {
        assertEquals("http://hermes.example.com/", ServerUrl.normalize("http://hermes.example.com"))
        assertEquals("https://192.168.1.20/", ServerUrl.normalize("https://192.168.1.20"))
    }

    @Test
    fun pathPrefixIsKeptAndNormalized() {
        assertEquals("https://example.com/hermes/", ServerUrl.normalize("https://example.com/hermes"))
        assertEquals("https://example.com/hermes/", ServerUrl.normalize("https://example.com/hermes/"))
        assertEquals("https://example.com/a/b/", ServerUrl.normalize("https://example.com/a/b"))
    }

    @Test
    fun hostIsLowercasedAndTrimmedOfCredentials() {
        assertEquals("https://hermes.example.com/", ServerUrl.normalize("https://HERMES.Example.COM/"))
        assertNull(ServerUrl.normalize("https://user:pass@example.com/"))
    }

    @Test
    fun rejectsWhatItCannotOpen() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("   "))
        assertNull(ServerUrl.normalize("ftp://example.com"))
        assertNull(ServerUrl.normalize("just some words"))
        assertNull(ServerUrl.normalize("https://example.com/?q=1"))
        assertNull(ServerUrl.normalize("https://example.com/#top"))
    }

    @Test
    fun hostAndSchemeReportersAgree() {
        val url = ServerUrl.normalize("192.168.0.172:9120")!!
        assertEquals("192.168.0.172", ServerUrl.hostOf(url))
        assertTrue(ServerUrl.isPlainHttp(url))
        assertFalse(ServerUrl.isPlainHttp(ServerUrl.normalize("example.com")!!))
    }

    @Test
    fun hostPortKeepsThePortTheUserTyped() {
        assertEquals("192.168.0.172:9120", ServerUrl.hostPortOf(ServerUrl.normalize("192.168.0.172:9120")!!))
        assertEquals("hermes.mojangle.net", ServerUrl.hostPortOf(ServerUrl.normalize("hermes.mojangle.net")!!))
        assertEquals("hermes.mojangle.net:8443",
            ServerUrl.hostPortOf(ServerUrl.normalize("https://hermes.mojangle.net:8443/")!!))
        assertNull(ServerUrl.hostPortOf("not a url"))
    }
}
