package com.exio.inkleaf.plugin

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * 系统 DNS 优先、DoH 兜底的解析器。
 *
 * 系统 DNS 失败、返回空答案或仅私网地址（被过滤/污染的典型特征）时，改用 DoH 重新解析， 绕过本地网络的域名过滤。两级答案都经过
 * [PluginNetworkPolicy.requirePublicAddresses] 公网校验； 两级全部失败时抛出系统 DNS 的原始异常，保持 "Unable to resolve
 * host ..." 的错误语义供分类层使用。
 */
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
}
