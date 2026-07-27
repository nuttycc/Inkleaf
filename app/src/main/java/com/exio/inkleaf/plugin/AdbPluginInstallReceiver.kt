package com.exio.inkleaf.plugin

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64InputStream
import com.exio.inkleaf.InkleafApplication
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Installs plugin packages sent by the repository's ADB deployment script.
 *
 * The manifest protects this exported receiver with the system DUMP permission, so only an
 * authorized ADB shell or a system process can invoke it. Chunks are staged in app-private cache
 * because Android scoped storage prevents the app from opening a path pushed to Download directly.
 */
class AdbPluginInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as InkleafApplication
        app.applicationScope.launch {
            try {
                val message = handle(app, intent)
                pending.setResultCode(Activity.RESULT_OK)
                pending.setResultData(message)
            } catch (error: CancellationException) {
                pending.setResultCode(Activity.RESULT_CANCELED)
                pending.setResultData("ERROR|Installation was cancelled")
                throw error
            } catch (error: Throwable) {
                pending.setResultCode(Activity.RESULT_CANCELED)
                pending.setResultData("ERROR|${sanitize(error.message ?: "Unknown installation error")}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(app: InkleafApplication, intent: Intent): String {
        require(intent.action == ACTION_INSTALL_PLUGIN) { "Unsupported action" }
        val session = intent.getStringExtra(EXTRA_SESSION).orEmpty()
        require(SESSION_PATTERN.matches(session)) { "Invalid transfer session" }

        val directory = File(app.cacheDir, STAGING_DIRECTORY)
        require(directory.isDirectory || directory.mkdirs()) { "Unable to create staging directory" }
        val encodedFile = File(directory, "$session.base64")

        return when (intent.getStringExtra(EXTRA_OPERATION)) {
            OPERATION_BEGIN -> {
                directory.listFiles()?.filter { it.name != encodedFile.name }?.forEach { stale ->
                    require(stale.delete()) { "Unable to clear a stale transfer file" }
                }
                encodedFile.writeText("", StandardCharsets.US_ASCII)
                "READY"
            }
            OPERATION_APPEND -> {
                require(encodedFile.isFile) { "Transfer session has not started" }
                val chunk = intent.getStringExtra(EXTRA_PAYLOAD).orEmpty()
                require(chunk.isNotEmpty() && chunk.length <= MAX_CHUNK_CHARACTERS) {
                    "Invalid transfer chunk"
                }
                require(encodedFile.length() + chunk.length <= MAX_ENCODED_PACKAGE_BYTES) {
                    "Plugin package exceeds the transfer size limit"
                }
                encodedFile.appendText(chunk, StandardCharsets.US_ASCII)
                "APPENDED"
            }
            OPERATION_COMMIT -> install(app, encodedFile, directory, intent)
            else -> error("Unsupported transfer operation")
        }
    }

    private suspend fun install(
        app: InkleafApplication,
        encodedFile: File,
        directory: File,
        intent: Intent,
    ): String {
        require(encodedFile.isFile && encodedFile.length() > 0L) { "Plugin package is empty" }
        val packageFile = File(directory, "${encodedFile.nameWithoutExtension}.zip")
        try {
            FileInputStream(encodedFile).use { encoded ->
                Base64InputStream(encoded, android.util.Base64.DEFAULT).use { decoded ->
                    FileOutputStream(packageFile).use { output -> decoded.copyTo(output) }
                }
            }
            require(packageFile.length() <= PluginStorageLimits.MAX_PACKAGE_BYTES) {
                "Plugin package exceeds the installation size limit"
            }

            val validation = PluginPackageValidator().validate(packageFile)
            val manifest = validation.packageContent?.manifest
            require(validation.installable && manifest != null) { "Plugin package failed validation" }
            val expectedPluginId = intent.getStringExtra(EXTRA_EXPECTED_PLUGIN_ID)
            val expectedVersion = intent.getStringExtra(EXTRA_EXPECTED_VERSION)
            require(expectedPluginId.isNullOrBlank() || manifest.id == expectedPluginId) {
                "Plugin id does not match the expected manifest"
            }
            require(expectedVersion.isNullOrBlank() || manifest.version == expectedVersion) {
                "Plugin version does not match the expected manifest"
            }

            val result = app.pluginManager.installFile(packageFile, activate = true)
            require(result.status != PluginInstallStatus.REJECTED) {
                result.errorMessage ?: result.errorCode?.name ?: "Plugin installation was rejected"
            }
            require(result.activatable) { "Plugin was installed but is incompatible with this host" }
            return "${result.status}|${result.pluginId}|${result.version}"
        } finally {
            encodedFile.delete()
            packageFile.delete()
        }
    }

    private fun sanitize(message: String): String =
        message.replace(Regex("[\\r\\n|]+"), " ").take(MAX_RESULT_CHARACTERS)

    companion object {
        const val ACTION_INSTALL_PLUGIN = "com.exio.inkleaf.action.ADB_INSTALL_PLUGIN"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_SESSION = "session"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_EXPECTED_PLUGIN_ID = "expectedPluginId"
        const val EXTRA_EXPECTED_VERSION = "expectedVersion"

        const val OPERATION_BEGIN = "begin"
        const val OPERATION_APPEND = "append"
        const val OPERATION_COMMIT = "commit"

        private const val STAGING_DIRECTORY = "adb-plugin-imports"
        private const val MAX_CHUNK_CHARACTERS = 16 * 1024
        private const val MAX_ENCODED_PACKAGE_BYTES = 86L * 1024L * 1024L
        private const val MAX_RESULT_CHARACTERS = 512
        private val SESSION_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
    }
}
