package com.exio.inkleaf.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.InkleafApplication
import com.exio.inkleaf.R
import com.exio.inkleaf.plugin.PluginDownloadSource
import com.exio.inkleaf.plugin.PluginInstallResult
import com.exio.inkleaf.plugin.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs one install at a time and keeps its outcome on the screen that asked for it. Results are
 * placed rather than announced: a snackbar on a screen the user is about to leave is a message they
 * never get to read.
 */
@Stable
class PluginInstaller(private val pluginManager: PluginManager, private val scope: CoroutineScope) {
    var isBusy by mutableStateOf(false)
        private set
    var result by mutableStateOf<PluginInstallResult?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    fun installUrl(source: PluginDownloadSource) =
        install { pluginManager.installUrl(source, activate = true) }

    fun installFile(uri: Uri) = install { pluginManager.installUri(uri, activate = true) }

    private fun install(call: suspend () -> PluginInstallResult) {
        if (isBusy) return
        scope.launch {
            isBusy = true
            result = null
            failure = null
            try {
                result = call()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failure = error.message ?: "安装失败"
            } finally {
                isBusy = false
            }
        }
    }
}

@Composable
private fun rememberInstaller(): PluginInstaller {
    val application = LocalContext.current.applicationContext as InkleafApplication
    val scope = rememberCoroutineScope()
    return remember(application.pluginManager, scope) {
        PluginInstaller(application.pluginManager, scope)
    }
}

/** Import a plugin package from an HTTP(S) download link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFromUrlScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val installer = rememberInstaller()
    var url by rememberSaveable { mutableStateOf("") }

    BackHandler { onBack() }
    Scaffold(
        modifier = modifier,
        topBar = { ImportTopBar(title = "从 URL 导入", onBack = onBack) },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "输入插件包的下载链接。Inkleaf 校验包内容后安装并启用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("插件包 URL") },
                leadingIcon = {
                    Icon(painter = painterResource(R.drawable.ic_file), contentDescription = null)
                },
                supportingText = {
                    Text(
                        text = installer.failure ?: "支持 HTTP 和 HTTPS 下载链接",
                        color =
                            if (installer.failure != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                isError = installer.failure != null,
                singleLine = true,
                enabled = !installer.isBusy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions =
                    KeyboardActions(
                        onGo = {
                            if (url.isNotBlank()) installer.installUrl(PluginDownloadSource(url))
                        }
                    ),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { installer.installUrl(PluginDownloadSource(url)) },
                enabled = !installer.isBusy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (installer.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(if (installer.isBusy) "正在安装…" else "下载并安装")
            }

            installer.result?.let { outcome ->
                InstallOutcomeCard(outcome = outcome, onOpen = { outcome.pluginId?.let(onOpenSource) })
            }
        }
    }
}

/** Import a plugin package already downloaded on the device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFromFileScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val installer = rememberInstaller()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) installer.installFile(uri)
        }

    BackHandler { onBack() }
    Scaffold(
        modifier = modifier,
        topBar = { ImportTopBar(title = "从本地文件导入", onBack = onBack) },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "选择 .zip 插件包。安装成功后立即启用，无需重启应用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = {
                    picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                enabled = !installer.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(if (installer.isBusy) "正在安装…" else "选择插件包", modifier = Modifier.padding(start = 8.dp))
            }

            installer.failure?.let { reason ->
                Text(
                    text = "安装失败：$reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            installer.result?.let { outcome ->
                InstallOutcomeCard(outcome = outcome, onOpen = { outcome.pluginId?.let(onOpenSource) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
    )
}

/** The result stays on screen so the user can read it, act on it, or try another package. */
@Composable
private fun InstallOutcomeCard(outcome: PluginInstallResult, onOpen: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = installCardMessage(outcome, "未知插件"),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (outcome.activatable) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
            )
            if (outcome.pluginId != null) {
                TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) {
                    Text("设置此漫画源")
                }
            }
        }
    }
}
