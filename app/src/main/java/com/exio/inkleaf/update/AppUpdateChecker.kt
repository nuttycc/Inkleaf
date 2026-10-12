package com.exio.inkleaf.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/**
 * Checks for app updates by comparing the running version against a remote manifest.
 *
 * The manifest lives at a fixed raw GitHub URL and carries both stable and preview channels.
 * A preview build (versionName contains a hyphen) only compares against the preview channel;
 * a stable build compares against stable. The caller may override the channel explicitly.
 */
class AppUpdateChecker(
    private val context: Context,
    private val callFactory: Call.Factory,
    private val settings: AppUpdateSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun check(channelOverride: AppUpdateChannel? = null): AppUpdateCheckResult =
        withContext(Dispatchers.IO) {
            val manifest =
                try {
                    fetchManifest()
                } catch (error: AppUpdateManifestException) {
                    return@withContext AppUpdateCheckResult.Error(error.message ?: "清单解析失败")
                } catch (error: IOException) {
                    return@withContext AppUpdateCheckResult.Error("无法连接更新服务器: ${error.message}")
                }

            val channel = channelOverride ?: detectChannel()
            val entry =
                when (channel) {
                    AppUpdateChannel.STABLE -> manifest.stable
                    AppUpdateChannel.PREVIEW -> manifest.preview ?: manifest.stable
                } ?: return@withContext AppUpdateCheckResult.UpToDate

            val currentCode = currentVersionCode()
            if (entry.versionCode <= currentCode) return@withContext AppUpdateCheckResult.UpToDate

            val skipped = settings.skippedVersion.first()
            if (skipped != null && skipped == entry.version) {
                return@withContext AppUpdateCheckResult.UpToDate
            }

            settings.setLastCheckMs(clock())
            AppUpdateCheckResult.UpdateAvailable(entry, channel)
        }

    private suspend fun fetchManifest(): AppUpdateManifest {
        val request = Request.Builder().url(AppUpdateSettings.MANIFEST_URL).build()
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

    private fun detectChannel(): AppUpdateChannel {
        val versionName = currentVersionName() ?: return AppUpdateChannel.STABLE
        return if (versionName.contains('-')) AppUpdateChannel.PREVIEW else AppUpdateChannel.STABLE
    }

    private fun currentVersionCode(): Long {
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

    private fun currentVersionName(): String? {
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
        return packageInfo.versionName
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
