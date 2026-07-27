package com.exio.inkleaf.plugin

import java.net.InetAddress
import java.net.UnknownHostException
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

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)
}
