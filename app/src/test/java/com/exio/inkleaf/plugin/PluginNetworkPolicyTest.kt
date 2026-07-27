package com.exio.inkleaf.plugin

import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginNetworkPolicyTest {
    @Test
    fun `public IPv4 and IPv6 addresses are allowed`() {
        assertTrue(PluginNetworkPolicy.isPublicAddress(address("8.8.8.8")))
        assertTrue(PluginNetworkPolicy.isPublicAddress(address("1.1.1.1")))
        assertTrue(PluginNetworkPolicy.isPublicAddress(address("2606:4700:4700::1111")))
    }

    @Test
    fun `private and special-use addresses are blocked`() {
        val blocked =
            listOf(
                "0.1.2.3",
                "10.0.0.1",
                "100.64.0.1",
                "127.0.0.1",
                "169.254.1.1",
                "172.16.0.1",
                "192.168.0.1",
                "192.0.2.1",
                "192.88.99.1",
                "198.18.0.1",
                "203.0.113.1",
                "224.0.0.1",
                "::1",
                "fc00::1",
                "fe80::1",
                "2001:db8::1",
                "2002:c0a8:101::",
            )

        blocked.forEach { literal ->
            assertFalse(literal, PluginNetworkPolicy.isPublicAddress(address(literal)))
        }
    }

    @Test
    fun `mixed DNS answers retain only public destinations`() {
        val public = address("8.8.8.8")

        assertEquals(
            listOf(public),
            PluginNetworkPolicy.requirePublicAddresses(
                "example.com",
                listOf(address("127.0.0.1"), public),
            ),
        )
    }

    @Test
    fun `DNS answer with no public destination is rejected`() {
        assertThrows(UnknownHostException::class.java) {
            PluginNetworkPolicy.requirePublicAddresses(
                "localhost",
                listOf(address("127.0.0.1"), address("::1")),
            )
        }
    }

    @Test
    fun `active VPN allows only hostname DNS answers from the fake IP benchmark range`() {
        val fakeIp = address("198.18.0.60")

        assertThrows(UnknownHostException::class.java) {
            PluginNetworkPolicy.requirePublicAddresses(
                "api.example.com",
                listOf(fakeIp),
            )
        }
        assertEquals(
            listOf(fakeIp),
            PluginNetworkPolicy.requirePublicAddresses(
                "api.example.com",
                listOf(fakeIp),
                allowVpnFakeIp = true,
            ),
        )
        assertThrows(UnknownHostException::class.java) {
            PluginNetworkPolicy.requirePublicAddresses(
                "198.18.0.60",
                listOf(fakeIp),
                allowVpnFakeIp = true,
            )
        }
        assertThrows(UnknownHostException::class.java) {
            PluginNetworkPolicy.requirePublicAddresses(
                "api.example.com",
                listOf(address("192.168.1.1")),
                allowVpnFakeIp = true,
            )
        }
    }

    @Test
    fun `IP literals are validated before OkHttp can bypass custom DNS`() {
        PluginNetworkPolicy.requirePublicUrlHost("api.example.com")
        PluginNetworkPolicy.requirePublicUrlHost("8.8.8.8")
        PluginNetworkPolicy.requirePublicUrlHost("2606:4700:4700::1111")

        listOf("127.0.0.1", "192.168.1.1", "198.18.0.60", "::1").forEach { literal ->
            assertThrows(literal, UnknownHostException::class.java) {
                PluginNetworkPolicy.requirePublicUrlHost(literal)
            }
        }
    }

    @Test
    fun `policy proxy selector validates IP literals before route selection`() {
        val client =
            PluginNetworkPolicy.createCallFactory(null, OkHttpClient(), followSslRedirects = true)
                as OkHttpClient
        val selector = client.proxySelector

        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://public.example/")))
        listOf("http://127.0.0.1/", "http://198.18.0.60/", "http://[::1]/").forEach { url ->
            assertThrows(UnknownHostException::class.java) {
                selector.select(URI(url))
            }
        }
    }

    @Test
    fun `policy client rejects private IP literals before network execution`() {
        val factory =
            PluginNetworkPolicy.createCallFactory(null, OkHttpClient(), followSslRedirects = true)
        val request = Request.Builder().url("http://127.0.0.1/admin").build()

        assertThrows(UnknownHostException::class.java) {
            factory.newCall(request).execute()
        }
    }

    @Test
    fun `policy preserves caller redirect settings and caps SSL redirects`() {
        val noRedirects =
            PluginNetworkPolicy.createCallFactory(
                null,
                OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
                followSslRedirects = true,
            ) as OkHttpClient
        assertFalse(noRedirects.followRedirects)
        assertFalse(noRedirects.followSslRedirects)

        val noSslRedirects =
            PluginNetworkPolicy.createCallFactory(
                null,
                OkHttpClient(),
                followSslRedirects = false,
            ) as OkHttpClient
        assertTrue(noSslRedirects.followRedirects)
        assertFalse(noSslRedirects.followSslRedirects)
    }

    @Test
    fun `HTTP header policy is shared at the plugin boundary`() {
        assertTrue(
            PluginNetworkPolicy.areValidHttpHeaders(
                mapOf("Accept" to "application/json", "X-Trace-Id" to "abc\t123")
            )
        )
        assertFalse(PluginNetworkPolicy.areValidHttpHeaders(mapOf("Bad Header" to "value")))
        assertFalse(PluginNetworkPolicy.areValidHttpHeaders(mapOf("X-Test" to "line\nbreak")))
        assertFalse(
            PluginNetworkPolicy.areValidHttpHeaders(
                (0..64).associate { index -> "X-Header-$index" to "value" }
            )
        )
    }

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)
}
