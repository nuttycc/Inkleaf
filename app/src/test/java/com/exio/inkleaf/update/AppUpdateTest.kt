package com.exio.inkleaf.update

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    private class FakePlatform : AppUpdatePlatform {
        var checkResult: AppUpdateCheckResult = AppUpdateCheckResult.UpToDate
        var checkCalls = 0
        var checkGate: CompletableDeferred<Unit>? = null
        val downloadedEntries = mutableListOf<AppUpdateEntry>()
        var progressSteps: List<ApkDownloadProgress> = emptyList()
        var downloadFailure: Exception? = null
        var afterProgress: (() -> Unit)? = null
        var downloadedApk = File("Inkleaf-test.apk")
        var permissionGranted = true
        var permissionRequests = 0
        val installedApks = mutableListOf<File>()

        override suspend fun check(): AppUpdateCheckResult {
            checkCalls += 1
            checkGate?.await()
            return checkResult
        }

        override suspend fun download(
            entry: AppUpdateEntry,
            onProgress: (ApkDownloadProgress) -> Unit,
        ): File {
            downloadedEntries += entry
            progressSteps.forEach(onProgress)
            afterProgress?.invoke()
            downloadFailure?.let { throw it }
            return downloadedApk
        }

        override fun hasInstallPermission(): Boolean = permissionGranted

        override fun startInstall(apk: File) {
            installedApks += apk
        }
    }

    private fun entry(version: String = "2026.1018.1200") =
        AppUpdateEntry(
            version = version,
            versionCode = 42L,
            apkUrl = "https://example.com/inkleaf.apk",
            releaseNotes = "notes",
        )

    @Test
    fun `check publishes an available update`() = runBlocking {
        val platform = FakePlatform().apply {
            checkResult = AppUpdateCheckResult.UpdateAvailable(entry())
        }
        val update = AppUpdate(this, platform)

        update.check()?.join()

        assertEquals(
            AppUpdateState.UpdateAvailable(version = "2026.1018.1200", releaseNotes = "notes"),
            update.state.value,
        )
    }

    @Test
    fun `check publishes up to date and errors`() = runBlocking {
        val platform = FakePlatform()
        val update = AppUpdate(this, platform)

        update.check()?.join()
        assertEquals(AppUpdateState.UpToDate, update.state.value)

        platform.checkResult = AppUpdateCheckResult.Error("网络不可用")
        update.check()?.join()
        assertEquals(AppUpdateState.Error("网络不可用"), update.state.value)
    }

    @Test
    fun `check is ignored while another check runs`() = runBlocking {
        val platform = FakePlatform().apply { checkGate = CompletableDeferred() }
        val update = AppUpdate(this, platform)

        val first = update.check()

        assertEquals(AppUpdateState.Checking, update.state.value)
        assertNull(update.check())

        platform.checkGate?.complete(Unit)
        first?.join()
        assertEquals(AppUpdateState.UpToDate, update.state.value)
        assertEquals(1, platform.checkCalls)
    }

    @Test
    fun `download is ignored outside the available state`() = runBlocking {
        val platform = FakePlatform()
        val update = AppUpdate(this, platform)

        assertNull(update.downloadAndInstall { platform.permissionRequests += 1 })

        assertTrue(platform.downloadedEntries.isEmpty())
    }

    @Test
    fun `download re-checks and rejects a stale prompt`() = runBlocking {
        val platform = FakePlatform().apply {
            checkResult = AppUpdateCheckResult.UpdateAvailable(entry())
        }
        val update = AppUpdate(this, platform)
        update.check()?.join()

        platform.checkResult = AppUpdateCheckResult.UpToDate
        update.downloadAndInstall { platform.permissionRequests += 1 }?.join()

        assertEquals(AppUpdateState.Error("更新信息已过期，请重新检查"), update.state.value)
        assertTrue(platform.downloadedEntries.isEmpty())
        assertEquals(2, platform.checkCalls)
    }

    @Test
    fun `download reports progress and defers install until permission`() = runBlocking {
        val platform = FakePlatform().apply {
            checkResult = AppUpdateCheckResult.UpdateAvailable(entry())
            permissionGranted = false
            progressSteps = listOf(ApkDownloadProgress(downloadedBytes = 50, totalBytes = 100))
        }
        val update = AppUpdate(this, platform)
        platform.afterProgress = {
            assertEquals(AppUpdateState.Downloading(0.5f), update.state.value)
        }
        update.check()?.join()

        update
            .downloadAndInstall {
                assertEquals(AppUpdateState.AwaitingInstallPermission, update.state.value)
                platform.permissionRequests += 1
            }
            ?.join()

        assertEquals(1, platform.permissionRequests)
        assertEquals(AppUpdateState.AwaitingInstallPermission, update.state.value)
        assertTrue(platform.installedApks.isEmpty())

        update.onInstallPermissionGranted()
        assertEquals(AppUpdateState.AwaitingInstallPermission, update.state.value)
        assertEquals(1, platform.permissionRequests)
        assertTrue(platform.installedApks.isEmpty())

        update.retryInstall()
        assertEquals(2, platform.permissionRequests)
        assertEquals(1, platform.downloadedEntries.size)

        platform.permissionGranted = true
        update.onInstallPermissionGranted()

        assertEquals(listOf(platform.downloadedApk), platform.installedApks)
        assertEquals(AppUpdateState.Idle, update.state.value)
    }

    @Test
    fun `download installs directly when permission is present`() = runBlocking {
        val platform = FakePlatform().apply {
            checkResult = AppUpdateCheckResult.UpdateAvailable(entry())
        }
        val update = AppUpdate(this, platform)
        update.check()?.join()

        update.downloadAndInstall { platform.permissionRequests += 1 }?.join()

        assertEquals(listOf(platform.downloadedApk), platform.installedApks)
        assertEquals(0, platform.permissionRequests)
        assertEquals(AppUpdateState.Idle, update.state.value)
    }

    @Test
    fun `download failure becomes an error`() = runBlocking {
        val platform = FakePlatform().apply {
            checkResult = AppUpdateCheckResult.UpdateAvailable(entry())
            downloadFailure = IOException("boom")
        }
        val update = AppUpdate(this, platform)
        update.check()?.join()

        update.downloadAndInstall { platform.permissionRequests += 1 }?.join()

        assertEquals(AppUpdateState.Error("下载失败: boom"), update.state.value)
        assertTrue(platform.installedApks.isEmpty())
    }

    @Test
    fun `an unknown download size reports zero progress`() = runBlocking {
        val platform =
            FakePlatform().apply {
                checkResult = AppUpdateCheckResult.UpdateAvailable(entry())
                permissionGranted = false
                progressSteps = listOf(ApkDownloadProgress(downloadedBytes = 50, totalBytes = null))
            }
        val update = AppUpdate(this, platform)
        platform.afterProgress = {
            assertEquals(AppUpdateState.Downloading(0f), update.state.value)
        }
        update.check()?.join()

        update.downloadAndInstall { platform.permissionRequests += 1 }?.join()

        assertEquals(AppUpdateState.AwaitingInstallPermission, update.state.value)
    }

    @Test
    fun `cancelling install permission discards the pending install`() = runBlocking {
        val platform =
            FakePlatform().apply {
                checkResult = AppUpdateCheckResult.UpdateAvailable(entry())
                permissionGranted = false
            }
        val update = AppUpdate(this, platform)
        update.check()?.join()
        update.downloadAndInstall { platform.permissionRequests += 1 }?.join()

        update.dismissState()
        platform.permissionGranted = true
        update.onInstallPermissionGranted()
        update.retryInstall()

        assertEquals(AppUpdateState.Idle, update.state.value)
        assertTrue(platform.installedApks.isEmpty())
        assertEquals(1, platform.permissionRequests)
    }

    @Test
    fun `install permission granted without a pending request does nothing`() = runBlocking {
        val platform = FakePlatform()
        val update = AppUpdate(this, platform)

        update.onInstallPermissionGranted()

        assertTrue(platform.installedApks.isEmpty())
        assertEquals(0, platform.checkCalls)
        assertEquals(AppUpdateState.Idle, update.state.value)
    }

    @Test
    fun `dismiss and consumeUpToDate return to idle`() = runBlocking {
        val platform = FakePlatform()
        val update = AppUpdate(this, platform)

        update.check()?.join()
        update.consumeUpToDate()
        assertEquals(AppUpdateState.Idle, update.state.value)

        platform.checkResult = AppUpdateCheckResult.UpdateAvailable(entry())
        update.check()?.join()
        update.consumeUpToDate()
        assertEquals(
            AppUpdateState.UpdateAvailable(version = "2026.1018.1200", releaseNotes = "notes"),
            update.state.value,
        )

        update.dismissState()
        assertEquals(AppUpdateState.Idle, update.state.value)
    }

    @Test
    fun `startup check reports the result without changing state`() = runBlocking {
        val expected = AppUpdateCheckResult.UpdateAvailable(entry())
        val platform = FakePlatform().apply { checkResult = expected }
        val update = AppUpdate(this, platform)

        assertEquals(expected, update.checkOnStartup())
        assertEquals(AppUpdateState.Idle, update.state.value)
    }
}
