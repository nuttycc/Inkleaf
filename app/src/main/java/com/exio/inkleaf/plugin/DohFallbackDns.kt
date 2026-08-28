// DohFallbackDns.kt
//
// 系统 DNS 优先、DoH 兜底的解析器。lookup() 提供同步的纯 IP 解析；newCall() 提供记录式解析，
// 以便把 DoH 解析出的 ServiceMetadata（含 ECH 配置）原样转发给 OkHttp 的 RouteSelector。
//
// 为什么需要 newCall：OkHttp 5.5 里 RouteSelector 只有在 newCall 返回"能产出记录"的 Call 时
// 才会消费 ServiceMetadata（*execute*），从而为直连路由套用 echConfigList 启用 ECH；若 newCall
// 返回默认的 LookupDnsCall，OkHttp 会退回走 lookup()，那只会拿到 IP，ECH 配置被丢弃。
//
// 两级记录都经过 PluginNetworkPolicy.requirePublicAddresses 公网校验；两级全失败时抛出系统 DNS
// 的原始异常，保持 "Unable to resolve host ..." 的错误语义供分类层使用。
@file:OptIn(okhttp3.internal.OkHttpInternalApi::class)

package com.exio.inkleaf.plugin

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Dns
import okhttp3.internal.dns.execute

internal class DohFallbackDns(
    private val system: Dns,
    private val fallback: Dns,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        var systemError: UnknownHostException? = null
        val systemResult =
            try {
                system.lookup(hostname)
            } catch (error: UnknownHostException) {
                systemError = error
                emptyList()
            }
        val systemPublic =
            runCatching { PluginNetworkPolicy.requirePublicAddresses(hostname, systemResult) }
                .getOrNull()
        if (!systemPublic.isNullOrEmpty()) return systemPublic
        val fallbackResult =
            try {
                fallback.lookup(hostname)
            } catch (error: UnknownHostException) {
                throw systemError ?: error
            }
        return PluginNetworkPolicy.requirePublicAddresses(hostname, fallbackResult)
    }

    override fun newCall(request: Dns.Request): Dns.Call =
        DohFallbackDnsCall(
            request = request,
            system = system,
            fallback = fallback,
        )

    /** 与 [lookup] 相同的两级策略，但保留 ServiceMetadata(ECH)，供 OkHttp 建立 ECH 连接。 */
    private class DohFallbackDnsCall(
        override val request: Dns.Request,
        private val system: Dns,
        private val fallback: Dns,
    ) : Dns.Call {
        // DNS 阶段无法真正抢占（execute() 会同步阻塞），cancel 只负责让尚未开始的调用尽快失败。
        private val canceled = AtomicBoolean(false)

        override fun enqueue(callback: Dns.Callback) {
            if (canceled.get()) {
                callback.onFailure(this, IOException("canceled"))
                return
            }
            try {
                callback.onRecords(this, last = true, records = resolve())
            } catch (error: UnknownHostException) {
                callback.onFailure(this, error)
            }
        }

        override fun cancel() {
            canceled.set(true)
        }

        override fun isCanceled(): Boolean = canceled.get()

        private fun resolve(): List<Dns.Record> {
            var systemError: UnknownHostException? = null
            val systemRecords =
                try {
                    system.newCall(request).execute()
                } catch (error: UnknownHostException) {
                    systemError = error
                    emptyList()
                }
            // 系统回答里有公网 IP 就信任它；否则（失败/空/私网）改问 DoH。
            publicRecords(request.hostname, systemRecords)?.let {
                return it
            }

            val fallbackRecords =
                try {
                    fallback.newCall(request).execute()
                } catch (error: UnknownHostException) {
                    throw systemError ?: error
                }
            // execute() 保证 fallbackRecords 至少含一条 IpAddress，否则早已抛错；
            // 因此到这里若无一公网，唯一的合理结果就是把 requirePublicAddresses 的拒绝异常抛出去。
            val fallbackPublicIps =
                PluginNetworkPolicy.requirePublicAddresses(
                    request.hostname,
                    fallbackRecords.filterIsInstance<Dns.Record.IpAddress>().map { it.address },
                )
            return keepPublic(fallbackRecords, fallbackPublicIps.toSet())
        }

        /**
         * 系统级的记录过滤：保留公网 IP 记录与全部 ServiceMetadata(ECH)。
         *
         * 返回 null 表示系统回答不可信（无公网 IP），上层据此继续 DoH 兜底，而不是立刻抛错。
         */
        private fun publicRecords(
            hostname: String,
            records: List<Dns.Record>,
        ): List<Dns.Record>? {
            val publicIps =
                runCatching {
                        PluginNetworkPolicy.requirePublicAddresses(
                            hostname,
                            records.filterIsInstance<Dns.Record.IpAddress>().map { it.address },
                        )
                    }
                    .getOrNull() ?: return null
            if (publicIps.isEmpty()) return null
            return keepPublic(records, publicIps.toSet())
        }

        /** 用胜出级的公网 IP 子集筛记录；ServiceMetadata 与该级整体同存，避免把 ECH 弄丢。 */
        private fun keepPublic(
            records: List<Dns.Record>,
            publicIps: Set<InetAddress>,
        ): List<Dns.Record> = records.filter {
            when (it) {
                is Dns.Record.IpAddress -> it.address in publicIps
                is Dns.Record.ServiceMetadata -> true
            }
        }
    }
}
