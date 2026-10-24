package io.github.theminionooo.tokenmonitor.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HubRouteKindTest {
    @Test
    fun `existing private routes keep their category`() {
        assertEquals(HubRouteKind.Tailscale, HubRoutes.kind("http://100.64.0.1:17321"))
        assertEquals(HubRouteKind.Tailscale, HubRoutes.kind("http://100.127.255.254:17321"))
        assertEquals(HubRouteKind.Tailscale, HubRoutes.kind("https://desk.tail1234.ts.net:17321"))
        assertEquals(HubRouteKind.Tailscale, HubRoutes.kind("http://[fd7a:115c:a1e0::1]:17321"))
        assertEquals(HubRouteKind.Local, HubRoutes.kind("http://192.168.1.20:17321"))
        assertEquals(HubRouteKind.Local, HubRoutes.kind("http://10.0.2.2:17321"))
        assertEquals(HubRouteKind.Local, HubRoutes.kind("http://desktop.local:17321"))
    }

    @Test
    fun `other hosts are public, loopback is not`() {
        assertEquals(HubRouteKind.Public, HubRoutes.kind("https://hub.example.com"))
        assertEquals(HubRouteKind.Public, HubRoutes.kind("hub.example.com"))
        assertEquals(HubRouteKind.Public, HubRoutes.kind("http://203.0.113.9:17321"))
        // 100.128.0.0 is outside the Tailscale range.
        assertEquals(HubRouteKind.Public, HubRoutes.kind("http://100.128.0.1:17321"))
        assertNull(HubRoutes.kind("http://127.0.0.1:17321"))
        assertNull(HubRoutes.kind("http://localhost:17321"))
        assertNull(HubRoutes.kind(""))
    }

    @Test
    fun `only public routes without https are insecure`() {
        assertTrue(HubRoutes.isInsecurePublicRoute("http://hub.example.com"))
        assertTrue(HubRoutes.isInsecurePublicRoute("http://hub.example.com:443"))
        assertFalse(HubRoutes.isInsecurePublicRoute("https://hub.example.com"))
        assertFalse(HubRoutes.isInsecurePublicRoute("http://100.101.102.103:17321"))
        assertFalse(HubRoutes.isInsecurePublicRoute("http://192.168.1.20:17321"))
        assertFalse(HubRoutes.isInsecurePublicRoute("http://127.0.0.1:17321"))
        assertThrows(HubApiException::class.java) { HubRoutes.requireHttpsForPublic("http://hub.example.com") }
        HubRoutes.requireHttpsForPublic("https://hub.example.com")
    }

    @Test
    fun `public host names must be real internet names`() {
        listOf("hub.example.com", "example.com", "a-b.example.co.uk", "xn--bcher-kva.com").forEach {
            assertNull(it, HubRoutes.publicHostProblem(it))
        }
        listOf(
            "", "hub", "localhost", "api.localhost", "hub.local", "hub.internal", "hub.lan", "nas.home.arpa",
            "203.0.113.9", "2001:db8::1", "[2001:db8::1]", "1.2.3", "-bad.example.com", "hub..example.com",
        ).forEach { assertNotNull(it, HubRoutes.publicHostProblem(it)) }
    }
}
