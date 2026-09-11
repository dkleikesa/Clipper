package com.qcmian.clipper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.ui.Popup
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind
import com.qcmian.clipper.util.formatDateTime

/**
 * Maccy 的 `PreviewItemView.largeTextThreshold` 是 1 000 个字符；Compose 无法只布局字符串的
 * 可见部分，因此本复刻版额外限制了超长条目实际渲染的长度。
 */
private const val LARGE_TEXT_LIMIT = 20_000

/**
 * 对应 Maccy 的 `SlideoutContentView` + `PreviewItemView`：带置顶与删除操作的工具栏、
 * 内容本身，然后是元信息区块。
 */
@Composable
fun PreviewPane(
    item: ClipItem?,
    /** 来源应用图标的 base64 PNG；未知时为 `null`。 */
    appIconBase64: String?,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    /** 对应 `ToolbarView` 的 `text.viewfinder` 按钮，对带 OCR 文字的图片显示。 */
    onCopyExtractedText: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(top = Popup.verticalPadding, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item != null) {
                // `ToolbarView.selectedImageText`：仅当图片有识别出的文字、
                // 且宿主接上了该动作时才提供。
                if (onCopyExtractedText != null && item.imageBase64 != null && item.title.isNotBlank()) {
                    ToolbarIconButton(
                        kind = ClipperIconKind.TEXT_VIEWFINDER,
                        onClick = onCopyExtractedText,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                ToolbarIconButton(
                    kind = if (item.isPinned) ClipperIconKind.PIN_SLASH else ClipperIconKind.PIN,
                    onClick = onTogglePin,
                )
                Spacer(Modifier.width(4.dp))
                ToolbarIconButton(kind = ClipperIconKind.TRASH, onClick = onDelete)
            }
        }

        if (item == null) return@Column

        val bitmap = rememberImageBitmap(item.imageBase64)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(5.dp)),
                )
            } else {
                // 对应 `PreviewItemView` 的 `LargeTextPreviewView`：超过 `largeTextThreshold`
                // 个字符时，Maccy 会改用专门的 `NSTextView`。Compose 没有只布局可视区域的文本
                // 能力，因此这里直接截断尾部，而不是每帧去布局一个数兆字节的字符串。
                val text = item.previewableText
                val truncated = text.length > LARGE_TEXT_LIMIT
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = if (truncated) text.take(LARGE_TEXT_LIMIT) else text,
                        fontSize = 13.sp,
                        color = colors.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (truncated) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "内容过长，仅显示前 $LARGE_TEXT_LIMIT 个字符。",
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = colors.outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))

        item.application?.let { application ->
            MetadataRow(
                label = "应用:",
                value = application.name,
                icon = rememberImageBitmap(appIconBase64),
            )
        }
        if (bitmap != null) {
            MetadataRow(label = "尺寸:", value = "${bitmap.width}×${bitmap.height}")
        }
        MetadataRow(label = "首次复制时间:", value = formatDateTime(item.firstCopiedAt))
        MetadataRow(label = "上次复制时间:", value = formatDateTime(item.lastCopiedAt))
        MetadataRow(label = "复制次数:", value = item.numberOfCopies.toString())
    }
}

@Composable
private fun ToolbarIconButton(kind: ClipperIconKind, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .height(23.dp)
            .clip(RoundedCornerShape(Popup.cornerRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        ClipperIcon(kind, size = 14.dp, tint = colors.onSurface)
    }
}

@Composable
private fun MetadataRow(label: String, value: String, icon: ImageBitmap? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = colors.onSurfaceVariant,
            fontWeight = FontWeight.Normal,
        )
        if (icon != null) {
            Spacer(Modifier.width(3.dp))
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
            )
        }
        Spacer(Modifier.width(3.dp))
        Text(
            text = value,
            fontSize = 11.sp,
            color = colors.onSurface,
        )
    }
}
