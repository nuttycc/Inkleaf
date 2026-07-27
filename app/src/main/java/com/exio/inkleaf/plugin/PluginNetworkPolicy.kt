package com.exio.inkleaf.plugin

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/** Network boundary shared by plugin-controlled requests. */
internal object PluginNetworkPolicy {
    val publicDns: Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                requirePublicAddresses(hostname, Dns.SYSTEM.lookup(hostname))
        }

    internal fun requirePublicAddresses(
        hostname: String,
        addresses: List<InetAddress>,
    ): List<InetAddress> {
        val publicAddresses = addresses.filter(::isPublicAddress)
        if (publicAddresses.isEmpty()) {
            throw UnknownHostException("Blocked non-public network destination: $hostname")
        }
        return publicAddresses
    }

    internal fun isPublicAddress(address: InetAddress): Boolean =
        when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return when {
            first == 0 -> false
            first == 10 -> false
            first == 100 && second in 64..127 -> false
            first == 127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 && third == 0 -> false
            first == 192 && second == 0 && third == 2 -> false
            first == 192 && second == 88 && third == 99 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff

        // Only global unicast space is eligible. Explicitly reject special-use ranges inside it.
        if ((first and 0xe0) != 0x20) return false
        if (first == 0x20 && second == 0x01) {
            if (third == 0x00 && fourth == 0x00) return false // Teredo
            if (third == 0x00 && fourth == 0x02) return false // Benchmarking
            if (third == 0x00 && ((fourth and 0xf0) == 0x10 || (fourth and 0xf0) == 0x20)) {
                return false // ORCHID
            }
            if (third == 0x0d && fourth == 0xb8) return false // Documentation
        }
        if (first == 0x20 && second == 0x02) return false // 6to4
        if (first == 0x3f && second == 0xff && (third and 0xf0) == 0x00) return false
        return true
    }
}
