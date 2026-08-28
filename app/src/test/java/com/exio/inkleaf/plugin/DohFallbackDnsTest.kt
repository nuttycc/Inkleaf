package com.exio.inkleaf.plugin

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.dns.execute
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(OkHttpInternalApi::class)
class DohFallbackDnsTest {
    private class FakeDns(private val result: List<InetAddress>?) : Dns {
        var consulted = 0
            private set

        override fun lookup(hostname: String): List<InetAddress> {
            consulted += 1
            return result ?: throw UnknownHostException("fake failure for $hostname")
        }
    }

    /** 记录级 fake：按需产出 DNS 记录（含 ServiceMetadata/ECH），或抛出解析失败。 */
    private class RecordsDns(private val records: () -> List<Dns.Record>) : Dns {
        var consulted = 0
            private set

        // 只有在缺失 newCall 重写（默认 LookupDnsCall 走 lookup 同步路径）时才被调用；正常路径走 newCall。
        // 抛 UnknownHostException，避免未实现的默认路径在测试里挂起。
        override fun lookup(hostname: String): List<InetAddress> =
            throw UnknownHostException("record-level fake must be exercised via newCall: $hostname")

        override fun newCall(request: Dns.Request): Dns.Call {
            consulted += 1
            return object : Dns.Call {
                override val request: Dns.Request = request

                override fun enqueue(callback: Dns.Callback) {
                    try {
                        callback.onRecords(this, last = true, records = records())
                    } catch (error: UnknownHostException) {
                        callback.onFailure(this, error)
                    }
                }

                override fun cancel() = Unit

                override fun isCanceled(): Boolean = false
            }
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

    @Test
    fun `newCall forwards system public IP and ech config when both present`() {
        val publicIp = address("93.184.216.34")
        val ech = "fake-aedfee-ech".encodeUtf8()
        val system = RecordsDns {
            listOf(
                Dns.Record.IpAddress("example.com", publicIp),
                Dns.Record.ServiceMetadata("example.com", echConfigList = ech),
            )
        }
        val fallback = RecordsDns {
            listOf(Dns.Record.IpAddress("example.com", address("1.2.3.4")))
        }

        val records = DohFallbackDns(system, fallback).newCall(Dns.Request("example.com")).execute()

        assertTrue(records.contains(Dns.Record.IpAddress("example.com", publicIp)))
        val serviceMetadata = records.filterIsInstance<Dns.Record.ServiceMetadata>().single()
        assertEquals(ech, serviceMetadata.echConfigList)
        assertEquals(0, fallback.consulted)
    }

    @Test
    fun `newCall falls back to DoH when system returns only private addresses`() {
        val dohEch = "fake-doh-ech".encodeUtf8()
        val system = RecordsDns { listOf(Dns.Record.IpAddress("example.com", address("10.0.0.1"))) }
        val fallback = RecordsDns {
            listOf(
                Dns.Record.IpAddress("example.com", address("93.184.216.34")),
                Dns.Record.ServiceMetadata("example.com", echConfigList = dohEch),
            )
        }

        val records = DohFallbackDns(system, fallback).newCall(Dns.Request("example.com")).execute()

        assertTrue(
            records.any { it is Dns.Record.IpAddress && it.address.hostAddress == "93.184.216.34" }
        )
        val serviceMetadata = records.filterIsInstance<Dns.Record.ServiceMetadata>().single()
        assertEquals(dohEch, serviceMetadata.echConfigList)
        assertEquals(1, fallback.consulted)
    }

    @Test
    fun `newCall falls back to DoH when system returns no IpAddress records`() {
        val system = RecordsDns { emptyList() }
        val fallback = RecordsDns {
            listOf(Dns.Record.IpAddress("example.com", address("93.184.216.34")))
        }

        val records = DohFallbackDns(system, fallback).newCall(Dns.Request("example.com")).execute()

        assertEquals(1, fallback.consulted)
        assertTrue(
            records.any { it is Dns.Record.IpAddress && it.address.hostAddress == "93.184.216.34" }
        )
    }

    @Test
    fun `newCall surfaces system error when both levels fail`() {
        val system = RecordsDns { throw UnknownHostException("system down for example.com") }
        val fallback = RecordsDns { throw UnknownHostException("doh down for example.com") }

        val error =
            assertThrows(UnknownHostException::class.java) {
                DohFallbackDns(system, fallback).newCall(Dns.Request("example.com")).execute()
            }

        assertTrue(error.message!!.contains("system down"))
    }

    @Test
    fun `newCall rejects fallback answers that are not public`() {
        val system = RecordsDns { throw UnknownHostException("system down for example.com") }
        val fallback = RecordsDns {
            listOf(Dns.Record.IpAddress("example.com", address("192.168.1.1")))
        }

        val error =
            assertThrows(UnknownHostException::class.java) {
                DohFallbackDns(system, fallback).newCall(Dns.Request("example.com")).execute()
            }

        assertTrue(error.message!!.contains("Blocked non-public"))
    }

    private fun address(ip: String): InetAddress = InetAddress.getByName(ip)
}
