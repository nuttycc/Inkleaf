package com.exio.inkleaf.data.enhancement

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.SSLException

sealed interface EnhancementModelInstallState {
    data object Checking : EnhancementModelInstallState
    data object NotInstalled : EnhancementModelInstallState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : EnhancementModelInstallState

    data class Installed(val bytes: Long) : EnhancementModelInstallState
    data class Failed(
        val operation: ModelOperation,
        val message: String,
    ) : EnhancementModelInstallState
}

enum class ModelOperation { DOWNLOAD, DELETE }

class EnhancementModelRepository private constructor(context: Context) {
    private val rootDirectory = File(context.applicationContext.filesDir, MODEL_DIRECTORY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelLocks = EnhancementModelCatalog.models.associate { it.id to Mutex() }
    private val downloadJobs = mutableMapOf<String, Job>()
    private val jobsLock = Any()
    private val connectionsLock = Any()
    private val activeConnections = mutableMapOf<String, HttpURLConnection>()
    private val stateLock = Any()

    private val mutableStates = EnhancementModelCatalog.models.associate { model ->
        model.id to MutableStateFlow<EnhancementModelInstallState>(
            EnhancementModelInstallState.Checking
        )
    }
    val states: Map<String, StateFlow<EnhancementModelInstallState>> =
        mutableStates.mapValues { (_, flow) -> flow.asStateFlow() }

    private val mutableInstalledCount = MutableStateFlow(0)
    val installedCount: StateFlow<Int> = mutableInstalledCount.asStateFlow()

    private val mutableInstalledBytes = MutableStateFlow(0L)
    val installedBytes: StateFlow<Long> = mutableInstalledBytes.asStateFlow()

    init {
        refresh()
    }

    fun state(modelId: String): StateFlow<EnhancementModelInstallState> =
        requireNotNull(states[modelId]) { "Unknown enhancement model: $modelId" }

    fun install(modelId: String) {
        val model = EnhancementModelCatalog.require(modelId)
        synchronized(jobsLock) {
            if (downloadJobs[modelId]?.isActive == true) return
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    modelLocks.getValue(modelId).withLock { installLocked(model) }
                } finally {
                    synchronized(jobsLock) {
                        if (downloadJobs[modelId] === job) downloadJobs.remove(modelId)
                    }
                }
            }
            downloadJobs[modelId] = job
            job.start()
        }
    }

    fun cancel(modelId: String) {
        EnhancementModelCatalog.require(modelId)
        synchronized(jobsLock) { downloadJobs[modelId] }?.cancel(
            CancellationException("Download cancelled by user")
        )
        disconnectActiveConnection(modelId)
    }

    fun delete(modelId: String): Job {
        val model = EnhancementModelCatalog.require(modelId)
        val previousJob = synchronized(jobsLock) { downloadJobs[modelId] }
        previousJob?.cancel(CancellationException("Model deletion requested"))
        disconnectActiveConnection(modelId)
        setState(modelId, EnhancementModelInstallState.Checking)
        return scope.launch {
            previousJob?.join()
            modelLocks.getValue(modelId).withLock {
                val directory = modelDirectory(modelId)
                if (directory.exists() && !directory.deleteRecursively()) {
                    setState(
                        modelId,
                        EnhancementModelInstallState.Failed(
                            operation = ModelOperation.DELETE,
                            message = "删除失败，请确认设备存储可用后重试。",
                        ),
                    )
                    return@withLock
                }
                cleanupTemporaryDirectories(model, recoverBackup = false)
                setState(modelId, EnhancementModelInstallState.NotInstalled)
            }
        }
    }

    fun refresh() {
        scope.launch {
            rootDirectory.mkdirs()
            cleanupLegacyArchiveCache()
            EnhancementModelCatalog.models.forEach { model ->
                val downloading = synchronized(jobsLock) {
                    downloadJobs[model.id]?.isActive == true
                }
                if (downloading) return@forEach
                modelLocks.getValue(model.id).withLock {
                    setState(model.id, EnhancementModelInstallState.Checking)
                    val installed = try {
                        cleanupTemporaryDirectories(model, recoverBackup = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    setState(
                        model.id,
                        if (installed) {
                            EnhancementModelInstallState.Installed(model.installedSize)
                        } else {
                            EnhancementModelInstallState.NotInstalled
                        },
                    )
                }
            }
        }
    }

    suspend fun installedDirectory(modelId: String): File? {
        EnhancementModelCatalog.require(modelId)
        val installState = state(modelId).first { state ->
            state !is EnhancementModelInstallState.Checking
        }
        if (installState !is EnhancementModelInstallState.Installed) {
            return null
        }
        val directory = modelDirectory(modelId)
        return directory.takeIf(File::isDirectory)
    }

    private suspend fun installLocked(model: EnhancementModelDescriptor) {
        val existingState = inspectState(model)
        if (existingState is EnhancementModelInstallState.Installed) {
            setState(model.id, existingState)
            return
        }

        rootDirectory.mkdirs()
        val staging = File(rootDirectory, ".${model.id}.${UUID.randomUUID()}.download")
        var committed = false
        try {
            if (!staging.mkdirs()) {
                throw IOException("无法创建模型下载目录")
            }
            setDownloadingState(model, 0L)
            var completedBytes = 0L
            for (artifact in model.artifacts.sortedBy(EnhancementModelArtifact::bytes)) {
                downloadArtifact(model, artifact, staging, completedBytes)
                completedBytes += artifact.bytes
                setDownloadingState(model, completedBytes)
            }
            commitStagingDirectory(model, staging)
            committed = true
            setState(model.id, EnhancementModelInstallState.Installed(model.installedSize))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                setState(model.id, inspectState(model))
            }
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            setState(
                model.id,
                EnhancementModelInstallState.Failed(
                    operation = ModelOperation.DOWNLOAD,
                    message = userFacingMessage(error),
                ),
            )
        } finally {
            if (!committed) staging.deleteRecursively()
        }
    }

    private suspend fun downloadArtifact(
        model: EnhancementModelDescriptor,
        artifact: EnhancementModelArtifact,
        staging: File,
        completedBytes: Long,
    ) {
        val partFile = File(staging, "${artifact.filename}.part")
        val finalFile = File(staging, artifact.filename)
        val digest = MessageDigest.getInstance("SHA-256")
        var artifactBytes = 0L
        var lastReportedArtifactBytes = 0L
        val connection = openConnectionFollowingRedirects(model.id, artifact.url)
        try {
            val declaredLength = connection.contentLengthLong
            if (declaredLength >= 0L && declaredLength != artifact.bytes) {
                throw IOException("服务器返回的文件大小与目录记录不一致")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(partFile.outputStream()).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        artifactBytes += read
                        if (artifactBytes > artifact.bytes) {
                            throw IOException("服务器返回的模型文件超出预期大小")
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        if (artifactBytes - lastReportedArtifactBytes >= PROGRESS_UPDATE_BYTES) {
                            setDownloadingState(model, completedBytes + artifactBytes)
                            lastReportedArtifactBytes = artifactBytes
                        }
                    }
                }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            clearActiveConnection(model.id, connection)
            connection.disconnect()
        }
        if (artifactBytes != artifact.bytes) {
            throw IOException("模型文件下载不完整")
        }
        val actualHash = digest.digest().toLowerHex()
        if (!actualHash.equals(artifact.sha256, ignoreCase = true)) {
            throw IOException("模型文件 SHA-256 校验失败")
        }
        atomicMove(partFile, finalFile)
    }

    private suspend fun openConnectionFollowingRedirects(
        modelId: String,
        initialUrl: String,
    ): HttpURLConnection {
        var current = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            currentCoroutineContext().ensureActive()
            if (current.protocol != "https") {
                throw SSLException("模型下载只允许 HTTPS 连接")
            }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream,*/*")
            }
            setActiveConnection(modelId, connection)
            val responseCode = try {
                currentCoroutineContext().ensureActive()
                connection.responseCode
            } catch (error: Exception) {
                clearActiveConnection(modelId, connection)
                connection.disconnect()
                currentCoroutineContext().ensureActive()
                throw error
            }
            if (responseCode in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                clearActiveConnection(modelId, connection)
                if (location.isNullOrBlank()) throw IOException("下载重定向缺少目标地址")
                if (redirectCount >= MAX_REDIRECTS) throw IOException("模型下载重定向次数过多")
                current = URL(current, location)
            } else {
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    clearActiveConnection(modelId, connection)
                    connection.disconnect()
                    throw IOException("模型服务器返回 HTTP $responseCode")
                }
                return connection
            }
        }
        throw IOException("模型下载重定向次数过多")
    }

    private fun commitStagingDirectory(
        model: EnhancementModelDescriptor,
        staging: File,
    ) {
        val target = modelDirectory(model.id)
        val backup = File(rootDirectory, ".${model.id}.${UUID.randomUUID()}.backup")
        var targetMoved = false
        try {
            if (target.exists()) {
                atomicMove(target, backup)
                targetMoved = true
            }
            atomicMove(staging, target)
            if (targetMoved) backup.deleteRecursively()
        } catch (error: Exception) {
            if (targetMoved && !target.exists() && backup.exists()) {
                runCatching { atomicMove(backup, target) }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun atomicMove(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IOException("设备存储不支持安全的原子安装，请释放空间后重试。", unsupported)
        }
    }

    private fun cleanupTemporaryDirectories(
        model: EnhancementModelDescriptor,
        recoverBackup: Boolean,
    ): Boolean {
        val prefix = ".${model.id}."
        rootDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix) && file.name.endsWith(".download")) {
                file.deleteRecursively()
            }
            if (!recoverBackup && file.name.startsWith(prefix) && file.name.endsWith(".backup")) {
                file.deleteRecursively()
            }
        }
        return recoverBackup &&
                EnhancementModelFiles.recoverInstalledModel(rootDirectory, model, ::atomicMove)
    }

    private fun inspectState(model: EnhancementModelDescriptor): EnhancementModelInstallState {
        val directory = modelDirectory(model.id)
        return if (EnhancementModelFiles.isModelInstalled(directory, model)) {
            EnhancementModelInstallState.Installed(
                model.artifacts.sumOf { File(directory, it.filename).length() }
            )
        } else {
            EnhancementModelInstallState.NotInstalled
        }
    }

    private fun setDownloadingState(model: EnhancementModelDescriptor, downloadedBytes: Long) {
        val safeDownloaded = downloadedBytes.coerceIn(0L, model.downloadSize)
        setState(
            model.id,
            EnhancementModelInstallState.Downloading(
                progress = if (model.downloadSize == 0L) 0f else {
                    safeDownloaded.toFloat() / model.downloadSize.toFloat()
                },
                downloadedBytes = safeDownloaded,
                totalBytes = model.downloadSize,
            ),
        )
    }

    private fun setState(modelId: String, state: EnhancementModelInstallState) {
        synchronized(stateLock) {
            val stateFlow = mutableStates.getValue(modelId)
            val previousState = stateFlow.value
            stateFlow.value = state
            if (
                previousState is EnhancementModelInstallState.Installed ||
                state is EnhancementModelInstallState.Installed
            ) {
                updateInstalledSummaryLocked()
            }
        }
    }

    private fun updateInstalledSummaryLocked() {
        val installedStates = mutableStates.values.mapNotNull { flow ->
            flow.value as? EnhancementModelInstallState.Installed
        }
        mutableInstalledCount.value = installedStates.size
        mutableInstalledBytes.value =
            installedStates.sumOf(EnhancementModelInstallState.Installed::bytes)
    }

    private fun modelDirectory(modelId: String): File = File(rootDirectory, modelId)

    private fun cleanupLegacyArchiveCache() {
        File(rootDirectory, LEGACY_ARCHIVE_CACHE_DIRECTORY).deleteRecursively()
    }

    private fun setActiveConnection(modelId: String, connection: HttpURLConnection) {
        synchronized(connectionsLock) { activeConnections[modelId] = connection }
    }

    private fun clearActiveConnection(modelId: String, connection: HttpURLConnection) {
        synchronized(connectionsLock) {
            if (activeConnections[modelId] === connection) activeConnections.remove(modelId)
        }
    }

    private fun disconnectActiveConnection(modelId: String) {
        synchronized(connectionsLock) { activeConnections[modelId] }?.disconnect()
    }

    private fun userFacingMessage(error: Exception): String = when (error) {
        is UnknownHostException -> "无法连接模型服务器，请检查网络后重试。"
        is SocketTimeoutException -> "下载超时，请检查网络状况后重试。"
        is SSLException -> "无法建立安全连接，请检查系统时间或稍后重试。"
        is IOException -> "下载失败：${error.message ?: "文件读写异常"}。请重试。"
        else -> "模型安装失败，请释放存储空间后重试。"
    }

    companion object {
        private const val MODEL_DIRECTORY = "image_enhancement_models"
        private const val LEGACY_ARCHIVE_CACHE_DIRECTORY = ".archives"
        private const val USER_AGENT = "Inkleaf/1.0 (Android; image enhancement model downloader)"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_UPDATE_BYTES = 256 * 1024L
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        @Volatile
        private var instance: EnhancementModelRepository? = null

        fun getInstance(context: Context): EnhancementModelRepository =
            instance ?: synchronized(this) {
                instance ?: EnhancementModelRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
