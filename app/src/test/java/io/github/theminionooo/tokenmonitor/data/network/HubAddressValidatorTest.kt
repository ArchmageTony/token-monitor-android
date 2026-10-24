package io.github.theminionooo.tokenmonitor.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubAddressValidatorTest {
    @Test
    fun `tailscale address is accepted by default`() {
        val result = HubAddressValidator.validate(
            rawUrl = "http://100.101.102.103:17321/",
            secret = "redacted",
            allowLocalNetwork = false,
        )

        assertTrue(result is HubAddressValidation.Allowed)
        assertEquals(
            "http://100.101.102.103:17321",
            (result as HubAddressValidation.Allowed).connection.baseUrl,
        )
    }

    @Test
    fun `a bare address gains the scheme and default port`() {
        val bare = HubAddressValidator.validate(" 100.101.102.103 ", "redacted", false)
        val withPort = HubAddressValidator.validate("100.101.102.103:17321/", "redacted", false)
        val home = HubAddressValidator.validate("192.168.1.20", "redacted", true)

        assertEquals("http://100.101.102.103:17321", (bare as HubAddressValidation.Allowed).connection.baseUrl)
        assertEquals("http://100.101.102.103:17321", (withPort as HubAddressValidation.Allowed).connection.baseUrl)
        assertEquals("http://192.168.1.20:17321", (home as HubAddressValidation.Allowed).connection.baseUrl)
        assertTrue(HubAddressValidator.validate("", "redacted", false) is HubAddressValidation.Rejected)
        assertTrue(HubAddressValidator.validate("not an address", "redacted", true) is HubAddressValidation.Rejected)
    }

    @Test
    fun `magic dns tailscale name is accepted`() {
        val result = HubAddressValidator.validate(
            rawUrl = "https://studio.tailnet-name.ts.net",
            secret = "redacted",
            allowLocalNetwork = false,
        )

        assertTrue(result is HubAddressValidation.Allowed)
    }

    @Test
    fun `local wifi requires explicit opt in`() {
        val refused = HubAddressValidator.validate("http://192.168.1.44:17321", "redacted", false)
        val allowed = HubAddressValidator.validate("http://192.168.1.44:17321", "redacted", true)

        assertTrue(refused is HubAddressValidation.Rejected)
        assertTrue(allowed is HubAddressValidation.Allowed)
    }

    @Test
    fun `public targets are rejected when the public route is not allowed, and api paths always`() {
        assertTrue(
            HubAddressValidator.validate("https://example.com", "redacted", false, allowPublicHttps = false) is HubAddressValidation.Rejected,
        )
        assertTrue(
            HubAddressValidator.validate("http://100.64.1.2:17321/api/stats", "redacted", false) is HubAddressValidation.Rejected,
        )
        assertTrue(
            HubAddressValidator.validate("https://hub.example.com/api/stats", "redacted", false) is HubAddressValidation.Rejected,
        )
    }

    @Test
    fun `a public https address is accepted on the standard port`() {
        fun baseUrl(raw: String) = (HubAddressValidator.validate(raw, "redacted", false) as HubAddressValidation.Allowed).connection.baseUrl

        assertEquals("https://example.com", baseUrl("https://example.com"))
        assertEquals("https://hub.example.com", baseUrl("https://hub.example.com/"))
        assertEquals("https://hub.example.com", baseUrl("https://Hub.Example.com:443"))
        assertEquals("https://hub.example.com:8443", baseUrl("https://hub.example.com:8443"))
        // A bare public name gains https://, never http:// and never the desktop Hub port.
        assertEquals("https://hub.example.com", baseUrl(" hub.example.com "))
        assertEquals("https://hub.example.com:8443", baseUrl("hub.example.com:8443"))
    }

    @Test
    fun `a public address is never accepted over plain http`() {
        listOf("http://hub.example.com", "http://hub.example.com:17321", "http://hub.example.com:443").forEach { raw ->
            val result = HubAddressValidator.validate(raw, "redacted", true)
            assertTrue(raw, result is HubAddressValidation.Rejected)
            assertTrue((result as HubAddressValidation.Rejected).reason.contains("https://"))
        }
    }

    @Test
    fun `public routes need a real domain name`() {
        listOf(
            "https://203.0.113.10",
            "https://[2001:db8::1]",
            "https://localhost",
            "https://127.0.0.1",
            "https://hub",
            "https://hub.internal",
            "https://hub.lan",
            "https://router.home.arpa",
            "https://hub.example.com?secret=redacted",
            "https://user:pass@hub.example.com",
            "https://hub.example.com#fragment",
            "ftp://hub.example.com",
        ).forEach { raw ->
            assertTrue(raw, HubAddressValidator.validate(raw, "redacted", true) is HubAddressValidation.Rejected)
        }
        assertTrue(HubAddressValidator.validate("https://hub.example.com", " ", true) is HubAddressValidation.Rejected)
    }

    @Test
    fun `private routes keep plain http and the desktop Hub port`() {
        fun baseUrl(raw: String, allowLocal: Boolean) = (HubAddressValidator.validate(raw, "redacted", allowLocal) as HubAddressValidation.Allowed).connection.baseUrl

        assertEquals("http://100.101.102.103:17321", baseUrl("100.101.102.103", false))
        assertEquals("http://desk.tail1234.ts.net:17321", baseUrl("desk.tail1234.ts.net", false))
        assertEquals("https://desk.tail1234.ts.net:17321", baseUrl("https://desk.tail1234.ts.net", false))
        assertEquals("http://192.168.1.20:17321", baseUrl("192.168.1.20", true))
        assertEquals("http://desktop.local:17321", baseUrl("desktop.local", true))
        // A private address still needs the Wi-Fi switch; https does not turn it into a public route.
        assertTrue(HubAddressValidator.validate("192.168.1.20", "redacted", false) is HubAddressValidation.Rejected)
        assertTrue(HubAddressValidator.validate("https://192.168.1.20", "redacted", false) is HubAddressValidation.Rejected)
    }

    @Test
    fun `routes are named for the header`() {
        assertEquals("Tailscale", HubAddressValidator.routeLabel("http://100.101.102.103:17321"))
        assertEquals("Tailscale", HubAddressValidator.routeLabel("http://desk.tail1234.ts.net:17321"))
        assertEquals("Home Wi-Fi", HubAddressValidator.routeLabel("http://192.168.1.20:17321", "http://192.168.1.20:17321"))
        assertEquals("Private network", HubAddressValidator.routeLabel("http://192.168.1.20:17321"))
        assertEquals("Hub", HubAddressValidator.routeLabel(null))
        assertEquals("Internet (HTTPS)", HubAddressValidator.routeLabel("https://hub.example.com"))
        assertEquals("Home Wi-Fi", HubAddressValidator.routeLabel("http://192.168.1.20:17321", "http://192.168.1.20:17321"))
    }
}
