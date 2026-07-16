package com.exio.inkleaf.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exio.inkleaf.R
import com.exio.inkleaf.data.enhancement.EnhancementModelCatalog
import com.exio.inkleaf.data.enhancement.EnhancementModelDescriptor
import com.exio.inkleaf.data.enhancement.EnhancementModelInstallState
import com.exio.inkleaf.data.enhancement.NcnnEnhancementEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancementModelManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnhancementModelsViewModel = viewModel(),
) {
    val states by viewModel.modelStates.collectAsStateWithLifecycle()
    val installedCount by viewModel.installedCount.collectAsStateWithLifecycle()
    val installedBytes by viewModel.installedBytes.collectAsStateWithLifecycle()
    val isChecking by viewModel.isChecking.collectAsStateWithLifecycle()
    var detailModelId by rememberSaveable { mutableStateOf<String?>(null) }

    val installed = remember(states) {
        EnhancementModelCatalog.models.filter { states[it.id] is EnhancementModelInstallState.Installed }
    }
    val checking = remember(states) {
        EnhancementModelCatalog.models.filter { states[it.id] is EnhancementModelInstallState.Checking }
    }
    val available = remember(states) {
        EnhancementModelCatalog.models.filter { model ->
            states[model.id] !is EnhancementModelInstallState.Installed &&
                    states[model.id] !is EnhancementModelInstallState.Checking
        }
    }
    val actions = remember(viewModel) {
        ModelManagerActions(
            install = viewModel::install,
            cancel = viewModel::cancel,
            delete = viewModel::delete,
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("图像增强模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item {
                ModelRuntimeNotice(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            item {
                Text(
                    text = when {
                        isChecking -> "正在检查模型文件…"
                        installedCount == 0 -> "尚未下载模型包。文件保存在应用私有目录。"
                        else -> "已下载 $installedCount 个模型包 · 占用 " +
                                formatFileSize(installedBytes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            modelManagerSection("正在检查", checking, states, actions) {
                detailModelId = it
            }
            modelManagerSection("已下载", installed, states, actions) {
                detailModelId = it
            }
            modelManagerSection("可下载", available, states, actions) {
                detailModelId = it
            }
        }
    }

    detailModelId?.let { modelId ->
        val model = EnhancementModelCatalog.find(modelId)
        if (model != null) {
            ModalBottomSheet(
                onDismissRequest = { detailModelId = null },
                sheetState = rememberExpandOnlySheetState(),
            ) {
                ModelDetailsSheet(model)
            }
        }
    }
}

private data class ModelManagerActions(
    val install: (String) -> Unit,
    val cancel: (String) -> Unit,
    val delete: (String) -> Unit,
)

private fun LazyListScope.modelManagerSection(
    title: String,
    models: List<EnhancementModelDescriptor>,
    states: Map<String, EnhancementModelInstallState>,
    actions: ModelManagerActions,
    onOpenDetails: (String) -> Unit,
) {
    if (models.isEmpty()) return
    item { ModelSectionLabel(title) }
    items(models, key = EnhancementModelDescriptor::id) { model ->
        ModelManagerRow(
            model = model,
            state = states.getValue(model.id),
            actions = actions,
            onOpenDetails = { onOpenDetails(model.id) },
        )
    }
}

@Composable
private fun ModelManagerRow(
    model: EnhancementModelDescriptor,
    state: EnhancementModelInstallState,
    actions: ModelManagerActions,
    onOpenDetails: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(model.displayName, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            ModelSummaryContent(model, state)
        },
        trailingContent = {
            ModelPackageAction(
                model = model,
                state = state,
                onInstall = { actions.install(model.id) },
                onCancel = { actions.cancel(model.id) },
                onRetryDownload = { actions.install(model.id) },
                onRetryDelete = { actions.delete(model.id) },
                installedAction = {
                    IconButton(onClick = { actions.delete(model.id) }) {
                        Icon(
                            painterResource(R.drawable.ic_delete),
                            contentDescription = "删除 ${model.displayName}",
                        )
                    }
                },
            )
        },
        modifier = Modifier.clickable(
            onClickLabel = "查看 ${model.displayName} 详情",
            onClick = onOpenDetails,
        ),
    )
}

@Composable
private fun ModelSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelDetailsSheet(model: EnhancementModelDescriptor) {
    val context = LocalContext.current
    SheetColumn(scrollable = true) {
        StandardSheetTitle(model.displayName)
        ModelRuntimeNotice(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        DetailRow("模型 ID", model.id, monospace = true)
        DetailRow("系列", model.family)
        DetailRow("版本", model.version)
        DetailRow("变体", model.variant)
        DetailRow("模型目标后端", model.targetBackend)
        DetailRow("应用推理后端", NcnnEnhancementEngine.runtimeDescription())
        DetailRow("倍率", "${model.scale}×")
        DetailRow("下载大小", formatFileSize(model.downloadSize))
        DetailRow("能力", model.capabilities.joinToString(" · "))
        DetailRow("推荐场景", model.recommendedFor.joinToString(" · "))
        DetailRow("许可证", model.license)

        Text(
            text = "文件校验",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
        )
        model.artifacts.forEach { artifact ->
            ListItem(
                headlineContent = { Text(artifact.filename) },
                supportingContent = {
                    Text(
                        text = "${formatFileSize(artifact.bytes)}\nSHA-256\n${artifact.sha256}\n${artifact.url}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.sourceUrl)))
                    }
                },
            ) {
                Text("查看模型来源")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                text = value,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            )
        },
    )
}
