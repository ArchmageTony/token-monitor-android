package io.github.theminionooo.tokenmonitor.data.network

import io.github.theminionooo.tokenmonitor.domain.HubConnection
import java.net.URI

/**
 * Limits where the Hub secret may be sent. Tailscale is allowed by default; RFC1918 Wi-Fi
 * hosts and mDNS names require the explicit local-network switch. Any other host is a
 * public route, which is accepted only as an `https://` domain name (port 443 unless
 * given) so the secret never crosses the internet in plain text.
 */
object HubAddressValidator {
    private const val defaultPort = 17321

    fun validate(
        rawUrl: String,
        secret: String,
        allowLocalNetwork: Boolean,
        allowPublicHttps: Boolean = true,
    ): HubAddressValidation {
        if (secret.isBlank()) return HubAddressValidation.Rejected("Enter the Hub secret before connecting.")
        val uri = try {
            URI(normalizeForRoute(rawUrl, allowPublicHttps))
        } catch (_: Exception) {
            return HubAddressValidation.Rejected("Enter the address, such as 100.101.102.103.")
        }
        if (uri.host.isNullOrBlank() && uri.scheme == null) {
            return HubAddressValidation.Rejected("Enter the address, such as 100.101.102.103.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https")) {
            return HubAddressValidation.Rejected("Only http and https Hub URLs are supported.")
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return HubAddressValidation.Rejected("Use only a Hub host and optional port; do not include credentials or a path.")
        }
        if (uri.path !in listOf("", "/")) {
            return HubAddressValidation.Rejected("Use the Hub base address, without an API path.")
        }
        if (uri.port !in -1..65535 || uri.port == 0) {
            return HubAddressValidation.Rejected("Use a valid Hub port.")
        }

        val host = uri.host.lowercase().removePrefix("[").removeSuffix("]")
        val tailscale = host.endsWith(".ts.net") || isTailscaleAddress(host)
        val local = isPrivateIpv4(host) || host.endsWith(".local")
        val public = !tailscale && !local
        if (public && allowPublicHttps) {
            if (scheme != "https") return HubAddressValidation.Rejected(HubRoutes.httpsRequiredMessage)
            HubRoutes.publicHostProblem(host)?.let { return HubAddressValidation.Rejected(it) }
        } else if (!tailscale && !(allowLocalNetwork && local)) {
            return HubAddressValidation.Rejected(
                if (allowPublicHttps) {
                    "Use a Tailscale address, enable a private Wi-Fi address as the main Hub, or use a public https:// address."
                } else {
                    "Use a Tailscale address, or enable a private Wi-Fi address as the main Hub."
                },
            )
        }
        val displayHost = if (host.contains(':')) "[$host]" else host
        // A public HTTPS Hub sits behind a reverse proxy on the standard port, so 443 is implied and not written.
        val authority = when {
            public && (uri.port == -1 || uri.port == HubRoutes.publicHttpsPort) -> displayHost
            else -> "$displayHost:${if (uri.port == -1) defaultPort else uri.port}"
        }
        return HubAddressValidation.Allowed(
            HubConnection(
                baseUrl = "$scheme://$authority",
                secret = secret.trim(),
                allowLocalNetwork = allowLocalNetwork,
            ),
        )
    }

    /**
     * People type what the desktop shows them, and often less: `100.101.102.103`,
     * `100.101.102.103:17321`, or a MagicDNS name. Add the scheme and drop stray
     * spaces and trailing slashes so those all work; the port defaults later.
     */
    internal fun normalize(rawUrl: String): String {
        val compact = rawUrl.filterNot { it.isWhitespace() }.trimEnd('/')
        if (compact.isEmpty()) return compact
        return if ("://" in compact) compact else "http://$compact"
    }

    /**
     * Like [normalize], but a bare public domain name gains `https://` instead of `http://`, so typing
     * `hub.example.com` reaches the HTTPS reverse proxy. Private and Tailscale hosts keep plain HTTP.
     */
    internal fun normalizeForRoute(rawUrl: String, allowPublicHttps: Boolean): String {
        val normalized = normalize(rawUrl)
        val compact = rawUrl.filterNot { it.isWhitespace() }
        if (!allowPublicHttps || compact.isEmpty() || "://" in compact) return normalized
        return if (HubRoutes.kind(normalized) == HubRouteKind.Public) "https://" + normalized.removePrefix("http://") else normalized
    }

    /**
     * The short route name the dashboard header and Settings show for whichever address is
     * answering: the home fallback, a Tailscale address, or another allowed private network.
     */
    fun routeLabel(url: String?, fallbackUrl: String? = null): String {
        if (url == null) return "Hub"
        if (fallbackUrl != null && url == fallbackUrl) return "Home Wi-Fi"
        val host = runCatching { java.net.URI(normalize(url)).host }.getOrNull().orEmpty().trim('[', ']').lowercase()
        return when {
            host.endsWith(".ts.net") || isTailscaleAddress(host) -> "Tailscale"
            isPrivateIpv4(host) -> "Private network"
            HubRoutes.isPublicRoute(url) -> "Internet (HTTPS)"
            else -> "Hub"
        }
    }

    /** True only for the private LAN forms accepted by [validate], never the Tailscale range. */
    fun isLocalAddress(url: String): Boolean {
        val host = runCatching { URI(normalize(url)).host }.getOrNull().orEmpty().trim('[', ']').lowercase()
        return isPrivateIpv4(host) || host.endsWith(".local")
    }

    /** True for a Tailscale address or MagicDNS name, which only routes while Tailscale is connected. */
    fun isTailscaleRoute(url: String): Boolean {
        val host = runCatching { URI(normalize(url)).host }.getOrNull().orEmpty().trim('[', ']').lowercase()
        return host.endsWith(".ts.net") || isTailscaleAddress(host)
    }

    private fun isTailscaleAddress(host: String): Boolean {
        if (host.startsWith("fd7a:115c:a1e0:", ignoreCase = true)) return true
        val octets = host.split('.')
        if (octets.size != 4) return false
        val values = octets.map { it.toIntOrNull() ?: return false }
        if (values.any { it !in 0..255 }) return false
        return values[0] == 100 && values[1] in 64..127
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        val values = octets.map { it.toIntOrNull() ?: return false }
        if (values.any { it !in 0..255 }) return false
        return when {
            values[0] == 10 -> true
            values[0] == 172 && values[1] in 16..31 -> true
            values[0] == 192 && values[1] == 168 -> true
            else -> false
        }
    }
}

sealed interface HubAddressValidation {
    data class Allowed(val connection: HubConnection) : HubAddressValidation
    data class Rejected(val reason: String) : HubAddressValidation
}
