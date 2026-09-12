package com.qcmian.clipper.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 清除历史前显示的确认框，附带「不再提示」开关。
 *
 * 视觉：与应用的紧凑面板风格一致——12dp 圆角、约 280dp 宽、`surface` 底色 + 6dp tonal
 * elevation；确认按钮是破坏性操作，用 `error` 色与取消按钮区分。
 */
@Composable
fun ConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    comment: String? = null,
    confirmLabel: String = "清除",
    dismissLabel: String = "取消",
    suppress: Boolean = false,
    onSuppressChange: (Boolean) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 260.dp, max = 300.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = colors.surface,
        tonalElevation = 6.dp,
        title = {
            Text(
                text = message,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        },
        text = {
            Column {
                if (comment != null) {
                    Text(
                        text = comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                // 整行可点，不必精确命中复选框。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSuppressChange(!suppress) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = suppress, onCheckedChange = { onSuppressChange(it) })
                    Text(
                        text = "不再提示",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
    )
}
