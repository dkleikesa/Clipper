package com.qcmian.clipper.ui.components

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
import com.qcmian.clipper.ui.Popup
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind

/** Search box height, `SearchFieldView`'s fixed 23pt. */
val SearchFieldHeight = 23.dp

/** Roughly the height of the paused banner. */
val BannerHeight = 34.dp

/**
 * Port of Maccy's `HeaderView` / `ListHeaderView`: the title, the search field and the preview
 * toggle. Maccy collapses the header to zero height instead of removing it, so the search
 * field keeps focus and typing brings it back into view on its own.
 */
@Composable
fun HistoryHeader(
    title: String,
    showTitle: Boolean,
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onCompositionChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    previewOpen: Boolean,
    previewOnLeft: Boolean,
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
        if (showTitle) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
        }

        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            onCompositionChange = onCompositionChange,
            focusRequester = focusRequester,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(5.dp))
        Box(
            modifier = Modifier
                .height(23.dp)
                .clip(RoundedCornerShape(Popup.cornerRadius))
                .clickable(onClick = onTogglePreview)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            // `HeaderView` mirrors the slideout placement: `sidebar.left` when the preview
            // sits on the right, `sidebar.right` when it is docked on the left.
            ClipperIcon(
                if (previewOnLeft) ClipperIconKind.SIDEBAR_RIGHT else ClipperIconKind.SIDEBAR_LEFT,
                size = 15.dp,
                tint = if (previewOpen) colors.primary else colors.onSurface,
            )
        }
        Spacer(Modifier.width(5.dp))
    }
}

/** The banner Maccy shows while capture is paused, with its "resume" affordance. */
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

/** The placeholder shown when the history or the search result set is empty. */
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
            text = if (searching) "换个关键词，或在偏好设置中切换搜索模式。" else "在任意位置复制内容，就会出现在这里。",
            fontSize = 11.sp,
            color = colors.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
    }
}

/** The divider Maccy draws between the pinned block and the scrolling history. */
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

/** The transient toast shown at the bottom of the panel, e.g. "pasting is not supported". */
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
