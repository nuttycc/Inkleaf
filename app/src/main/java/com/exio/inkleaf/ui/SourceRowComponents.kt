package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR

/**
 * One row of a source list: identity and state, plus the single action that state allows. Installed
 * rows navigate; the enable switch lives on the detail screen, where its consequences can be
 * explained — a row that both navigates and toggles tells the user neither.
 */
@Composable
internal fun SourceLifecycleRowItem(
    row: SourceLifecycleRow,
    busy: Boolean,
    onActivate: () -> Unit,
    onOpen: () -> Unit,
) {
    val actionLabel =
        when (row.action) {
            SourceRowAction.Install -> "安装"
            SourceRowAction.Update -> "更新"
            SourceRowAction.Manage -> null
        }
    val supporting =
        row.description
            ?.takeIf { row.action == SourceRowAction.Install }
            ?.let { "${row.statusText} · $it" }
            ?: row.statusText

    InkleafActionListItem(
        headline = row.name,
        supporting = supporting,
        onClick = if (row.action == SourceRowAction.Manage) onOpen else onActivate,
        leadingContent = { SourceIcon() },
        trailingContent = {
            if (actionLabel == null) {
                ForwardIcon()
            } else {
                TextButton(onClick = onActivate, enabled = !busy) { Text(actionLabel) }
            }
        },
    )
}

@Composable
internal fun SourceIcon() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter =
                    painterResource(
                        MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_extension_outlined
                    ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** The only filled button on these screens, and only while the repository really offers something newer. */
@Composable
internal fun UpdateBanner(count: Int, busy: Boolean, onUpdateAll: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "发现 $count 个可更新漫画源",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onUpdateAll, enabled = !busy) { Text("全部更新") }
        }
    }
}
