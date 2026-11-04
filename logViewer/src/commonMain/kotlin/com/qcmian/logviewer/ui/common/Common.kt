package com.qcmian.logviewer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.logviewer.core.model.MarkerPalette
import com.qcmian.logviewer.ui.theme.LocalLvColors
import com.qcmian.logviewer.ui.theme.toComposeColor

/* ==================== 小组件 ==================== */

@Composable
fun LvTooltipChip(text: String, modifier: Modifier = Modifier) {
    val c = LocalLvColors.current
    Text(
        text = text,
        color = c.textFaint,
        fontSize = 10.sp,
        modifier = modifier
            .clip(RoundedCornerShape(99.dp))
            .background(c.surfaceAlt)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun MarkerDots(markers: Set<Int>, modifier: Modifier = Modifier) {
    if (markers.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        markers.sorted().take(4).forEach { m ->
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MarkerPalette.colorOf(m).toComposeColor()),
            )
        }
        if (markers.size > 4) {
            Text("+${markers.size - 4}", fontSize = 7.sp, color = LocalLvColors.current.textFaint)
        }
    }
}

@Composable
fun ColorSwatch(
    color: Long?,
    size: Int = 14,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalLvColors.current
    val shape = RoundedCornerShape(3.dp)
    var m = Modifier
        .size(size.dp)
        .clip(shape)
        .background(color?.toComposeColor() ?: Color.Transparent)
        .border(1.dp, if (color == null) c.borderStrong else Color.Transparent, shape)
    if (selected) {
        m = m.border(2.dp, c.accent, shape)
    }
    if (onClick != null) {
        m = m.clickable(onClick = onClick)
    }
    Box(m)
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    val c = LocalLvColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = c.textFaint, fontSize = 12.sp)
    }
}

/** 轻量工具按钮（文本型，避免图标库依赖）；onClick 置尾以支持 trailing lambda */
@Composable
fun MiniButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val c = LocalLvColors.current
    val bg = when {
        primary -> c.accent
        else -> c.surfaceAlt
    }
    val fg = when {
        primary -> Color.White
        danger -> c.levelE
        else -> c.textDim
    }
    Text(
        text = text,
        color = if (enabled) fg else c.textFaint,
        fontSize = 10.5.sp,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (enabled) bg else c.surfaceAlt)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = LocalLvColors.current
    Text(
        text = text,
        color = c.textFaint,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        modifier = modifier,
    )
}
