package com.qcmian.clipper.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qcmian.clipper.ClipperInfo
import com.qcmian.clipper.getPlatform
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind

/**
 * Port of Maccy's `About` panel: the app name, its version and the credits block. Maccy's
 * credits are a `Website│GitHub│Support` link row, reproduced here with clickable text.
 */
@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(360.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.primary,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            ClipperIcon(ClipperIconKind.COPY, size = 20.dp, tint = colors.onPrimary)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(ClipperInfo.NAME, style = MaterialTheme.typography.titleMedium)
                        Text(
                            ClipperInfo.TAGLINE,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "轻量、键盘优先的剪贴板历史记录，运行在桌面端、Android、iOS 与网页端。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "参考并复刻了 macOS 开源项目 Maccy。",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "版本 ${ClipperInfo.VERSION}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    "运行于 ${getPlatform().name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                // Maccy's credits row is `Website│GitHub│Support`; this replica links to the
                // project it follows instead of claiming a site of its own.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CreditLink("${ClipperInfo.UPSTREAM_NAME} 官网") {
                        onOpenUrl(ClipperInfo.UPSTREAM_WEBSITE)
                    }
                    Text("│", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    CreditLink("${ClipperInfo.UPSTREAM_NAME} GitHub") {
                        onOpenUrl(ClipperInfo.UPSTREAM_URL)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/** One clickable entry of the credits row, Maccy's `Website│GitHub│Support` equivalent. */
@Composable
private fun CreditLink(label: String, onClick: () -> Unit) {
    Text(
        text = "$label ↗",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
