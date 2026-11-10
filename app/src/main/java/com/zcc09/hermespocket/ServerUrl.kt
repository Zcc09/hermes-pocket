package com.zcc09.hermespocket

/**
 * Turns whatever the user typed into an absolute base URL.
 *
 * Pure Kotlin (no Android, no network) so it is unit-tested on the JVM. The app
 * has no built-in server: the address always comes from the person using it.
 *
 * Accepted: `hermes.example.com`, `https://hermes.example.com`,
 * `192.168.1.20:9120`, `http://nas.lan:8080/app`, `[fd00::5]:9120`.
 * A missing scheme becomes `http` for private/loopback addresses and
 * single-label hosts (a box on your own network usually has no certificate) and
 * `https` for public ones. Everything else is rejected instead of guessed.
 */
object ServerUrl {

    /** A normalized base URL ending in `/`, or null when the input is not usable. */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null

        val withScheme = when {
            text.contains("://") -> text
            looksPrivate(text.substringBefore('/')) -> "http://$text"
            else -> "https://$text"
        }

        val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.userInfo != null) return null

        val host = uri.host?.lowercase() ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.path.orEmpty().trimEnd('/')
        return "$scheme://$host$port$path/"
    }

    /** The host a normalized URL points at, or null. */
    fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

    /** True when [url] is served over plain HTTP (worth a warning in the UI). */
    fun isPlainHttp(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true)

    private fun looksPrivate(hostPort: String): Boolean {
        val host = (
            if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']')
            else hostPort.substringBefore(':')
            ).lowercase()

        if (host.isEmpty()) return false
        if (host == "localhost") return true
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal") ||
            host.endsWith(".home") || host.endsWith(".localdomain")
        ) return true

        val v4 = host.split('.')
        if (v4.size == 4 && v4.all { it.toIntOrNull() != null }) {
            val a = v4[0].toInt()
            val b = v4[1].toInt()
            return a == 10 ||
                a == 127 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254)
        }
        // IPv6 literals (the URI keeps them in brackets) are local or public in
        // equal measure; treat loopback/link-local as private, the rest public.
        if (host.contains(':')) {
            return host == "::1" || host.startsWith("fe80:") || host.startsWith("fd") || host.startsWith("fc")
        }
        // A single label with no dot is a machine on the local network ("nas",
        // "homeserver"), never a public name.
        return !host.contains('.')
    }
}
