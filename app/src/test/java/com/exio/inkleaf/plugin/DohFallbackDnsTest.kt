package com.exio.inkleaf.plugin

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DohFallbackDnsTest {
    private class FakeDns(private val result: List<InetAddress>?) : Dns {
        var consulted = 0
            private set

        override fun lookup(hostname: String): List<InetAddress> {
            consulted += 1
            return result ?: throw UnknownHostException("fake failure for $hostname")
        }
    }

    @Test
    fun `system answer is used when public`() {
        val system = FakeDns(listOf(address("93.184.216.34")))
        val fallback = FakeDns(listOf(address("1.2.3.4")))

        val result = DohFallbackDns(system, fallback).lookup("example.com")

        assertEquals(listOf("93.184.216.34"), result.map { it.hostAddress })
        assertEquals(0, fallback.consulted)
    }

    @Test
    fun `fallback is consulted when system fails`() {
        val system = FakeDns(null)
        val fallback = FakeDns(listOf(address("93.184.216.34")))

        val result = DohFallbackDns(system, fallback).lookup("example.com")

        assertEquals(listOf("93.184.216.34"), result.map { it.hostAddress })
        assertEquals(1, fallback.consulted)
    }

    @Test
    fun `fallback is consulted when system returns no answers`() {
        val system = FakeDns(emptyList())
        val fallback = FakeDns(listOf(address("93.184.216.34")))

        val result = DohFallbackDns(system, fallback).lookup("example.com")

        assertEquals(listOf("93.184.216.34"), result.map { it.hostAddress })
    }

    @Test
    fun `fallback is consulted when system returns only private addresses`() {
        // 运营商 DNS 返回私网/保留地址是被过滤或污染的典型特征，需要 DoH 二次意见
        val system = FakeDns(listOf(address("10.0.0.1")))
        val fallback = FakeDns(listOf(address("93.184.216.34")))

        val result = DohFallbackDns(system, fallback).lookup("example.com")

        assertEquals(listOf("93.184.216.34"), result.map { it.hostAddress })
    }

    @Test
    fun `system error wins when both levels fail`() {
        val system = FakeDns(null)
        val fallback = FakeDns(null)

        val error =
            assertThrows(UnknownHostException::class.java) {
                DohFallbackDns(system, fallback).lookup("ss.mangafunb.fun")
            }

        assertTrue(error.message!!.contains("ss.mangafunb.fun"))
    }

    @Test
    fun `fallback answers must be public`() {
        val system = FakeDns(null)
        val fallback = FakeDns(listOf(address("192.168.1.1")))

        val error =
            assertThrows(UnknownHostException::class.java) {
                DohFallbackDns(system, fallback).lookup("example.com")
            }

        assertTrue(error.message!!.contains("Blocked non-public"))
    }

    @Test
    fun `fallback failure surfaces when system returned empty without error`() {
        val system = FakeDns(emptyList())
        val fallback = FakeDns(null)

        val error =
            assertThrows(UnknownHostException::class.java) {
                DohFallbackDns(system, fallback).lookup("example.com")
            }

        assertTrue(error.message!!.contains("fake failure"))
    }

    private fun address(ip: String): InetAddress = InetAddress.getByName(ip)
}
