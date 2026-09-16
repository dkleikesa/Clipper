package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.components.HoverTooltip
import com.qcmian.clipper.core.ui.components.VerticalScrollbar
import com.qcmian.clipper.core.ui.components.VerticalScrollbarWidth
import com.qcmian.clipper.core.ui.components.rememberImageBitmap
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.util.formatDateTime

/**
 * 是 1 000 个字符；Compose 无法只布局字符串的
 * 可见部分，因此本复刻版额外限制了超长条目实际渲染的长度。
 */
private const val LARGE_TEXT_LIMIT = 20_000

/**
 * + `PreviewItemView`：带置顶与删除操作的工具栏、
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
            // 底部信息区只占必要的高度：预览内容（`weight(1f)`）是主体，间距一大它就没了。
            .padding(top = Popup.verticalPadding, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item != null) {
                // `ToolbarView.selectedImageText`：仅当图片有识别出的文字、且宿主接上了该动作时
                // 才提供。判据必须是 `hasRecognizedText` 而不是 `title.isNotBlank()`：带正文的图文
                // 混合条目，其标题来自正文，用它判定会凭空多出一个「复制图片文字」按钮。
                if (onCopyExtractedText != null && item.hasRecognizedText) {
                    ToolbarIconButton(
                        kind = ClipperIconKind.TEXT_VIEWFINDER,
                        tooltip = "复制图片中识别出的文字",
                        onClick = onCopyExtractedText,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                ToolbarIconButton(
                    kind = if (item.isPinned) ClipperIconKind.PIN_SLASH else ClipperIconKind.PIN,
                    tooltip = if (item.isPinned) "取消置顶" else "置顶这一条",
                    onClick = onTogglePin,
                )
                Spacer(Modifier.width(4.dp))
                ToolbarIconButton(
                    kind = ClipperIconKind.TRASH,
                    tooltip = "删除这一条",
                    onClick = onDelete,
                )
            }
        }

        if (item == null) return@Column

        val bitmap = rememberImageBitmap(item.image)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    // 宽高都必须钉满：`Image` 的尺寸就是修饰符链跑完之后的最小约束，
                    // 只给 `fillMaxWidth()` 时高度仍是 0，`ContentScale.Fit` 的缩放比会退化成
                    // 「位图自身像素高」，比预览区小的图片因此永远按原尺寸居中显示，撑不满。
                    // 钉满整个区域后 `Fit` 才会按可用空间等比放大到最大，与原生 `NSImageView`
                    // 的 `scaleProportionallyUpOrDown`（圆角同样加在这个视图的 frame 上）一致。
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(5.dp)),
                )
            } else {
                // 对应 `PreviewItemView` 的 `LargeTextPreviewView`：超过 `largeTextThreshold`
                // 个字符时原生实现会改用专门的文本视图。Compose 没有只布局可视区域的文本
                // 能力，因此这里直接截断尾部，而不是每帧去布局一个数兆字节的字符串。
                val text = item.previewableText
                val truncated = text.length > LARGE_TEXT_LIMIT
                val textScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 给右侧滚动条留出位置：文字不会被滑块压住，滚动条出现 / 消失时也不重排。
                        .padding(end = VerticalScrollbarWidth + 4.dp)
                        .verticalScroll(textScrollState),
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
                // 文本超长时出现，内容放得下时整条隐藏。
                VerticalScrollbar(
                    scrollState = textScrollState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = colors.outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(6.dp))

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
private fun ToolbarIconButton(kind: ClipperIconKind, tooltip: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    HoverTooltip(tooltip) {
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
}

@Composable
private fun MetadataRow(label: String, value: String, icon: ImageBitmap? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            // 行高收到比字号略大：五行元信息一共也就几十 dp，默认行高会白占掉一截高度。
            lineHeight = 14.sp,
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
            lineHeight = 14.sp,
            color = colors.onSurface,
        )
    }
}
