package io.github.theminionooo.tokenmonitor.data.network

import java.net.URI

/**
 * How a saved Hub address reaches the Hub. Tailscale and Local are the upstream private routes and
 * keep their plain-HTTP behaviour. Public is a Hub published on the internet behind an HTTPS reverse
 * proxy: it must use `https://`, is verified against the system certificate store, and is never
 * sent over cleartext.
 */
enum class HubRouteKind { Tailscale, Local, Public }

/** Classification and checks for the public HTTPS route. Pure JVM so unit tests cover it directly. */
object HubRoutes {
    const val publicHttpsPort = 443
    const val httpsRequiredMessage = "A public Hub address must use https://. The secret is never sent to a public host over plain HTTP."

    // RFC 6761/6762/8375 and common private-zone suffixes that never name an internet host.
    private val specialSuffixes = listOf(
        ".localhost", ".local", ".internal", ".intranet", ".lan", ".home", ".home.arpa", ".corp", ".private",
        ".test", ".example", ".invalid", ".arpa", ".onion",
    )

    /**
     * Route kind of a saved or typed address. Tailscale wins over Local, exactly as the upstream
     * checks order them, so no existing address changes category. Loopback is not a public route.
     */
    fun kind(url: String): HubRouteKind? {
        val host = host(url) ?: return null
        return when {
            HubAddressValidator.isTailscaleRoute(url) -> HubRouteKind.Tailscale
            HubAddressValidator.isLocalAddress(url) -> HubRouteKind.Local
            isLoopback(host) -> null
            else -> HubRouteKind.Public
        }
    }

    fun isPublicRoute(url: String): Boolean = kind(url) == HubRouteKind.Public

    /** True when [url] is a public route that does not use HTTPS, so nothing may be sent to it. */
    fun isInsecurePublicRoute(url: String): Boolean =
        isPublicRoute(url) && runCatching { URI(url).scheme }.getOrNull()?.lowercase() != "https"

    /** Throws before any request when a public route is not HTTPS (defence in depth beyond validation). */
    fun requireHttpsForPublic(url: String) {
        if (isInsecurePublicRoute(url)) throw HubApiException(0, httpsRequiredMessage)
    }

    /**
     * Null when [host] may be used as a public HTTPS Hub, otherwise the reason it may not. Public routes
     * take DNS names only: IP literals cannot normally present a publicly trusted certificate, and
     * single-label or special-use names never resolve to an internet host.
     */
    fun publicHostProblem(host: String): String? {
        val name = host.lowercase().trim('[', ']').trimEnd('.')
        if (name.isEmpty()) return "Enter the Hub's HTTPS address, such as https://hub.example.com."
        if (':' in name || isIpv4Literal(name)) return "Use the public Hub's domain name with https://, not an IP address."
        if (name == "localhost" || specialSuffixes.any { name.endsWith(it) } || '.' !in name) {
            return "Use a public domain name for an internet Hub, such as https://hub.example.com."
        }
        val labels = name.split('.')
        val validLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        if (name.length > 253 || labels.any { !validLabel.matches(it) } || labels.last().all(Char::isDigit)) {
            return "Use a valid domain name (internationalized names in their xn-- form)."
        }
        return null
    }

    internal fun host(url: String): String? =
        runCatching { URI(HubAddressValidator.normalize(url)).host }.getOrNull()?.trim('[', ']')?.lowercase()?.takeIf { it.isNotBlank() }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "0:0:0:0:0:0:0:1" ||
            (isIpv4Literal(host) && host.startsWith("127."))

    private fun isIpv4Literal(host: String): Boolean =
        host.split('.').let { parts -> parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true } }
}
