package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.exio.inkleaf.plugin.PluginRepoIndex

/**
 * Repository configuration, reached from the source list's caption row rather than resident on the
 * resting screen: the URL is a setting for the few people who change it.
 */
@Composable
internal fun SourceRepoSheetContent(
    url: String,
    busy: Boolean,
    error: String?,
    loadedIndex: PluginRepoIndex?,
    onUrlChange: (String) -> Unit,
    onLoad: (String) -> Unit,
    onUseDefault: () -> Unit,
) {
    SheetColumn(
        modifier = Modifier.imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
        scrollable = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StandardSheetTitle("插件仓库")

            Text(
                text = "仓库索引是一份 HTTPS JSON 清单，列出可安装的漫画源插件及其版本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            InkleafPrecisionField(
                value = url,
                onValueChange = { onUrlChange(it) },
                label = "仓库索引 URL",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onLoad(url) }),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { onLoad(url) }, enabled = !busy) {
                    Text(if (loadedIndex == null) "加载" else "重新加载")
                }
                TextButton(onClick = onUseDefault, enabled = !busy) { Text("恢复默认仓库") }
            }

            loadedIndex?.let { index ->
                Text(
                    text = "${index.repoName ?: "插件仓库"} · ${index.plugins.size} 个插件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
