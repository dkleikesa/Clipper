package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.feature.history.state.FooterAction

/** 一个页脚按钮。 */
data class FooterEntry(
    val action: FooterAction,
    val title: String,
)

/**
 *，压缩成一行三个等宽按钮。
 * 「清除」只删除未置顶的条目（清空全部数据请到设置里操作）。
 */
@Composable
fun FooterRows(
    selectedIndex: Int,
    showQuit: Boolean,
    onAction: (FooterAction) -> Unit,
    onHover: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = footerEntries(showQuit)

    // 整行跟踪悬停：鼠标离开页脚区域后解除高亮，
    // 否则 `footerSelection` 会一直停留在最后悬停的按钮上，背景色擦不掉。
    val rowInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowInteraction.collectIsHoveredAsState()
    var footerHovered by remember { mutableStateOf(false) }
    LaunchedEffect(rowHovered) {
        if (rowHovered) {
            footerHovered = true
        } else if (footerHovered) {
            footerHovered = false
            onHover(-1)
        }
    }

    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            modifier = Modifier
                .padding(horizontal = Popup.horizontalSeparatorPadding)
                .padding(bottom = Popup.verticalSeparatorPadding),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Popup.horizontalSeparatorPadding)
                .hoverable(rowInteraction),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entries.forEachIndexed { index, entry ->
                FooterButton(
                    title = entry.title,
                    isSelected = index == selectedIndex,
                    onClick = { onAction(entry.action) },
                    onHover = { onHover(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(Popup.verticalPadding))
    }
}

@Composable
private fun FooterButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onHover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    LaunchedEffect(hovered) {
        if (hovered) onHover()
    }

    Row(
        modifier = modifier
            .heightIn(min = Popup.itemHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    isSelected -> colors.primary.copy(alpha = 0.8f)
                    hovered -> colors.onSurface.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            maxLines = 1,
            color = if (isSelected) colors.onPrimary else colors.onSurface,
        )
    }
}

fun footerEntries(showQuit: Boolean): List<FooterEntry> {
    return buildList {
        add(FooterEntry(FooterAction.CLEAR, "清除"))
        add(FooterEntry(FooterAction.PREFERENCES, "设置"))
        if (showQuit) {
            add(FooterEntry(FooterAction.QUIT, "退出"))
        }
    }
}
