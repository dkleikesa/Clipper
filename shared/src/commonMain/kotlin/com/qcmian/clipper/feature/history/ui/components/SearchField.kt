package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind

/**
 * 一个 23pt 高的圆角框，用 10% 不透明度的次级色填充，
 * 内含放大镜与内联的清空按钮。
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    /** 输入法已标记文本（即候选窗打开）时为 `true`。 */
    onCompositionChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface

    var value by remember { mutableStateOf(TextFieldValue(query)) }

    // 当查询词被外部修改时（⌃H、⌃W、清空）保持输入框同步。
    LaunchedEffect(query) {
        if (value.text != query) {
            value = TextFieldValue(query, TextRange(query.length))
        }
    }

    // 对应 `KeyHandlingView` 的 `hasMarkedText()` 检查：输入法已标记文本时，
    // 每次按键都属于候选窗，面板不得把它当成快捷键。
    LaunchedEffect(value.composition) {
        onCompositionChange(value.composition != null)
    }

    Box(
        modifier = modifier
            .height(23.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.onSurface.copy(alpha = 0.1f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClipperIcon(
                ClipperIconKind.SEARCH,
                size = 11.dp,
                tint = foreground.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 5.dp),
            )
            Spacer(Modifier.width(5.dp))

            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索…",
                        fontSize = 13.sp,
                        color = foreground.copy(alpha = 0.5f),
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onQueryChange(it.text)
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = foreground),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }

            if (query.isNotEmpty()) {
                Spacer(Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    ClipperIcon(
                        ClipperIconKind.CLEAR,
                        size = 11.dp,
                        tint = foreground.copy(alpha = 0.9f),
                    )
                }
            }
            Spacer(Modifier.width(5.dp))
        }
    }
}
