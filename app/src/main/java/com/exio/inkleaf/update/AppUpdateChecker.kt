package com.exio.inkleaf.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/**
 * Checks for app updates by comparing the running version against a remote manifest.
 *
 * The manifest lives at a fixed raw GitHub URL and carries a single release entry.
 */
class AppUpdateChecker(
    private val context: Context,
    private val callFactory: Call.Factory,
) {
    suspend fun check(): AppUpdateCheckResult =
        withContext(Dispatchers.IO) {
            val manifest =
                try {
                    fetchManifest()
                } catch (error: AppUpdateManifestException) {
                    return@withContext AppUpdateCheckResult.Error(error.message ?: "清单解析失败")
                } catch (error: IOException) {
                    return@withContext AppUpdateCheckResult.Error("无法连接更新服务器: ${error.message}")
                }

            val entry = manifest.release ?: return@withContext AppUpdateCheckResult.UpToDate

            val currentCode = installedVersionCode(context)
            if (entry.versionCode <= currentCode) return@withContext AppUpdateCheckResult.UpToDate

            AppUpdateCheckResult.UpdateAvailable(entry)
        }

    private suspend fun fetchManifest(): AppUpdateManifest {
        val request = Request.Builder().url(MANIFEST_URL).build()
        val response = callFactory.newCall(request).execute()
        response.use { result ->
            if (!result.isSuccessful) {
                throw IOException("更新服务器返回 HTTP ${result.code}")
            }
            val body = result.body.string()
            if (body.length > MAX_MANIFEST_BYTES) {
                throw AppUpdateManifestException("更新清单超出大小限制")
            }
            return AppUpdateManifestParser.parse(body)
        }
    }

    companion object {
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/nuttycc/inkleaf/release-manifest/app-update.json"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}

internal fun installedVersionCode(context: Context): Long {
    val packageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
}
