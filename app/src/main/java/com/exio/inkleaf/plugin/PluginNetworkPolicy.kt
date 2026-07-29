package com.exio.inkleaf.plugin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.exio.inkleaf.diagnostics.NetworkDiagnosticReporter
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import okhttp3.Call
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/** Network boundary shared by plugin-controlled requests. */
internal object PluginNetworkPolicy {
    private val strictDns: Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                requirePublicAddresses(hostname, Dns.SYSTEM.lookup(hostname))
        }

    fun createCallFactory(
        context: Context?,
        client: OkHttpClient,
        followSslRedirects: Boolean,
        diagnosticSource: String? = null,
        pluginId: String? = null,
    ): Call.Factory {
        val builder =
            client
                .newBuilder()
                .dns(strictDns)
        if (context != null && diagnosticSource != null) {
            // Observe before validation so blocked requests are visible too; this interceptor
            // neither reads sensitive request data nor changes policy outcomes.
            builder.addInterceptor(
                NetworkDiagnosticReporter.interceptor(context, diagnosticSource, pluginId)
            )
        }
        val policyClient =
            builder
                .addInterceptor(hostValidationInterceptor())
                .proxy(null)
                .proxySelector(validatingProxySelector())
                .followRedirects(client.followRedirects)
                .followSslRedirects(client.followSslRedirects && followSslRedirects)
                .build()
        if (context == null) return policyClient

        val appContext = context.applicationContext ?: context
        val connectivityManager =
            appContext.getSystemService(ConnectivityManager::class.java) ?: return policyClient
        return NetworkBoundCallFactory(connectivityManager, policyClient)
    }

    internal fun requirePublicUrlHost(hostname: String) {
        if (!IP_LITERAL_PATTERN.matches(hostname)) return
        if (!isPublicAddress(InetAddress.getByName(hostname))) {
            throw UnknownHostException("Blocked non-public network destination: $hostname")
        }
    }

    internal fun requirePublicAddresses(
        hostname: String,
        addresses: List<InetAddress>,
        allowVpnFakeIp: Boolean = false,
    ): List<InetAddress> {
        // Fake-IP VPNs map public hostnames into the RFC 2544 benchmark range and route those
        // addresses through their tunnel. Keep the exception limited to active VPN lookups and
        // hostname-based requests so the DNS filter stays strict for every other private answer.
        val acceptVpnFakeIp = allowVpnFakeIp && !IP_LITERAL_PATTERN.matches(hostname)
        val allowedAddresses = addresses.filter { address ->
            isPublicAddress(address) || (acceptVpnFakeIp && isBenchmarkIpv4Address(address))
        }
        if (allowedAddresses.isEmpty()) {
            throw UnknownHostException("Blocked non-public network destination: $hostname")
        }
        return allowedAddresses
    }

    internal fun isPublicAddress(address: InetAddress): Boolean =
        when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }

    internal fun areValidHttpHeaders(headers: Map<String, String>): Boolean =
        headers.size <= MAX_HEADER_COUNT &&
            headers.keys.all {
                it.isNotBlank() &&
                    it.length <= MAX_HEADER_NAME_LENGTH &&
                    HEADER_NAME_PATTERN.matches(it)
            } &&
            headers.values.all { value ->
                value.length <= MAX_HEADER_VALUE_LENGTH &&
                    value.all { it == '\t' || it in '\u0020'..'\u007e' }
            }

    private class NetworkBoundCallFactory(
        private val connectivityManager: ConnectivityManager,
        private val baseClient: OkHttpClient,
    ) : Call.Factory {
        @Volatile private var binding: NetworkBinding? = null

        override fun newCall(request: Request): Call {
            val vpnNetwork =
                activeVpnNetwork(connectivityManager) ?: return baseClient.newCall(request)
            return clientFor(vpnNetwork).newCall(request)
        }

        private fun clientFor(network: Network): OkHttpClient {
            binding
                ?.takeIf { it.network == network }
                ?.let {
                    return it.client
                }
            return synchronized(this) {
                binding?.takeIf { it.network == network }?.client
                    ?: baseClient
                        .newBuilder()
                        // DNS and sockets stay on one captured VPN. If that network disappears,
                        // the request fails instead of sending its Fake-IP route on another
                        // network.
                        .dns(vpnDns(connectivityManager, network))
                        .proxySelector(validatingProxySelector(connectivityManager, network))
                        .addInterceptor(networkBindingInterceptor(connectivityManager, network))
                        .socketFactory(network.socketFactory)
                        .build()
                        .also { client -> binding = NetworkBinding(network, client) }
            }
        }
    }

    private data class NetworkBinding(val network: Network, val client: OkHttpClient)

    private fun vpnDns(
        connectivityManager: ConnectivityManager,
        network: Network,
    ): Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (activeVpnNetwork(connectivityManager) != network) {
                    throw UnknownHostException("VPN changed during DNS lookup: $hostname")
                }
                val addresses = network.getAllByName(hostname).toList()
                if (activeVpnNetwork(connectivityManager) != network) {
                    throw UnknownHostException("VPN changed during DNS lookup: $hostname")
                }
                return requirePublicAddresses(hostname, addresses, allowVpnFakeIp = true)
            }
        }

    private fun activeVpnNetwork(connectivityManager: ConnectivityManager): Network? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val isVpn =
            connectivityManager
                .getNetworkCapabilities(activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return activeNetwork.takeIf { isVpn }
    }

    private fun validatingProxySelector(
        connectivityManager: ConnectivityManager? = null,
        network: Network? = null,
    ): ProxySelector =
        object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                if (
                    connectivityManager != null &&
                        network != null &&
                        activeVpnNetwork(connectivityManager) != network
                ) {
                    throw UnknownHostException("VPN changed before connection: ${uri.host}")
                }
                val hostname =
                    uri.host?.removeSurrounding("[", "]")
                        ?: throw UnknownHostException("Missing network destination")
                requirePublicUrlHost(hostname)
                return listOf(Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }

    private fun networkBindingInterceptor(
        connectivityManager: ConnectivityManager,
        network: Network,
    ): Interceptor = Interceptor { chain ->
        if (activeVpnNetwork(connectivityManager) != network) {
            throw UnknownHostException("VPN changed before request: ${chain.request().url.host}")
        }
        chain.proceed(chain.request())
    }

    private fun hostValidationInterceptor(): Interceptor = Interceptor { chain ->
        requirePublicUrlHost(chain.request().url.host)
        chain.proceed(chain.request())
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

    private fun isBenchmarkIpv4Address(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 198 && second in 18..19
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

    private const val MAX_HEADER_COUNT = 64
    private const val MAX_HEADER_NAME_LENGTH = 256
    private const val MAX_HEADER_VALUE_LENGTH = 16 * 1024
    private val IP_LITERAL_PATTERN = Regex("^(?:[0-9a-fA-F]*:[0-9a-fA-F:.]*|[0-9.]+)$")
    private val HEADER_NAME_PATTERN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
}
