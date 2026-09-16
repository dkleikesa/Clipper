package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.components.HoverTooltip
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind

/**
 * 头部：搜索框与预览开关。搜索时把头部折叠为零高度而不是移除它，
 * 这样搜索框能保持焦点，一输入就会自动把它带回视野。
 */
@Composable
fun HistoryHeader(
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onCompositionChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    previewOpen: Boolean,
    previewOnLeft: Boolean,
    /** 预览开关的悬停提示；由 `HistoryScreen` 拼上当前快捷键。 */
    previewTooltip: String,
    onTogglePreview: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 10.dp)
            .padding(top = Popup.verticalPadding)
            .then(if (visible) Modifier else Modifier.height(0.dp).clipToBounds()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            onCompositionChange = onCompositionChange,
            focusRequester = focusRequester,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(5.dp))
        HoverTooltip(previewTooltip) {
            Box(
                modifier = Modifier
                    .height(23.dp)
                    .clip(RoundedCornerShape(Popup.cornerRadius))
                    .clickable(onClick = onTogglePreview)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                // `HeaderView` 会镜像滑出面板的位置：预览在右侧时用 `sidebar.left`，
                // 停靠在左侧时用 `sidebar.right`。
                ClipperIcon(
                    if (previewOnLeft) ClipperIconKind.SIDEBAR_RIGHT else ClipperIconKind.SIDEBAR_LEFT,
                    size = 15.dp,
                    tint = if (previewOpen) colors.primary else colors.onSurface,
                )
            }
        }
        Spacer(Modifier.width(5.dp))
    }
}

/** 记录暂停时显示的横幅，带「恢复」操作。 */
@Composable
fun PausedBanner(onlyNext: Boolean, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(Popup.cornerRadius))
            .background(colors.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClipperIcon(ClipperIconKind.PAUSE, size = 12.dp, tint = colors.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (onlyNext) "已暂停 — 下一次复制将被忽略" else "已暂停 — 不再记录新的复制内容",
            fontSize = 12.sp,
            color = colors.primary,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(Popup.cornerRadius))
                .clickable(onClick = onResume)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("恢复", fontSize = 12.sp, color = colors.primary)
        }
    }
}

/** 历史为空或搜索结果为空时显示的占位内容。 */
@Composable
fun EmptyState(searching: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClipperIcon(
            if (searching) ClipperIconKind.SEARCH else ClipperIconKind.COPY,
            size = 28.dp,
            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (searching) "没有匹配的项目" else "剪贴板历史为空",
            fontSize = 13.sp,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (searching) "换个关键词，或在设置中切换搜索模式。" else "在任意位置复制内容，就会出现在这里。",
            fontSize = 11.sp,
            color = colors.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
    }
}

/** 置顶区块与可滚动历史之间的分隔线。 */
@Composable
fun PinsSeparator() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.padding(
            horizontal = Popup.horizontalSeparatorPadding,
            vertical = Popup.verticalSeparatorPadding,
        ),
    )
}

/** 显示在面板底部的临时提示，例如「此平台不支持粘贴」。 */
@Composable
fun StatusToast(message: String) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.onSurface,
        contentColor = colors.surface,
        tonalElevation = 4.dp,
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
