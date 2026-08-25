package com.exio.inkleaf.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 设备当前是否有可用网络。无法判定（无 ConnectivityManager 等）时按"有网络"处理， 让底层异常自己说话，避免把真实故障误报成无网络。 */
internal fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return true
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
