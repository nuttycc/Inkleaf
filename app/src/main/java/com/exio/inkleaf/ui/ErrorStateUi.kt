package com.exio.inkleaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.R as MaterialSymbolsOutlinedR

/** 按失败类别选择 Material Symbols 图标，阅读器与详情页共用。 */
internal fun ContentLoadErrorKind.errorIconRes(): Int =
    when (this) {
        ContentLoadErrorKind.NO_NETWORK ->
            MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_wifi_off_outlined
        ContentLoadErrorKind.DNS_UNRESOLVED,
        ContentLoadErrorKind.TIMEOUT,
        ContentLoadErrorKind.CONNECTION_FAILED ->
            MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_cloud_off_outlined
        ContentLoadErrorKind.RATE_LIMITED,
        ContentLoadErrorKind.HTTP_REJECTED,
        ContentLoadErrorKind.UNKNOWN ->
            MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_error_outlined
        ContentLoadErrorKind.CONTENT_MISSING ->
            MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_search_off_outlined
        ContentLoadErrorKind.PLUGIN ->
            MaterialSymbolsOutlinedR.drawable.materialsymbols_ic_extension_outlined
    }

/**
 * 阅读器全屏错误态：图标 + 主文案 + 建议 + 主重试 + 次动作 + 返回 + 复制详情。
 *
 * 重试是最可能的下一步，因此是唯一的实心按钮；返回与复制为弱化的文字按钮。 原始异常文本不出现在主文案里，只通过"复制错误信息"带出，便于排查插件问题。
 */
@Composable
internal fun ReaderErrorContent(
    error: ContentLoadError,
    backgroundColor: Color,
    contentColor: Color,
    onBack: () -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    retryLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
) {
    var detailCopied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(error.kind.errorIconRes()),
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.85f),
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = error.message,
            color = contentColor,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        error.hint?.let { hint ->
            Text(
                text = hint,
                color = contentColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (onRetry != null && retryLabel != null && error.retryable) {
            Button(
                onClick = onRetry,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = backgroundColor,
                    ),
            ) {
                Text(retryLabel)
            }
        }
        if (onAction != null && actionLabel != null) {
            OutlinedButton(
                onClick = onAction,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
            ) {
                Text(actionLabel)
            }
        }
        TextButton(
            onClick = onBack,
            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        ) {
            Text(backLabel)
        }
        if (error.technicalDetail.isNotBlank()) {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(error.technicalDetail))
                    detailCopied = true
                },
                colors =
                    ButtonDefaults.textButtonColors(contentColor = contentColor.copy(alpha = 0.6f)),
            ) {
                Text(if (detailCopied) "已复制错误信息" else "复制错误信息")
            }
        }
    }
}
