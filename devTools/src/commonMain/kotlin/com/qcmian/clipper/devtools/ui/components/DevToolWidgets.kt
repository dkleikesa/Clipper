package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.theme.hintColor

/**
 * 工具界面共用的一小组控件。
 *
 * 各插件只有一屏「输入框 + 一排按钮 + 输出框」，样式必须一致，所以不写在各工具里：一旦分叉，
 * JSON 工具与 XML 工具会长得不像同一套软件里的东西。
 */

/** 一节等宽编辑区（输入或输出）。 */
@Composable
fun DevToolEditor(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    placeholder: String = "",
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)

    Column(modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.hintColor)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 高度由调用方在自己的 Column 里用 `weight` 分配，这里只吃掉分到的那一份。
                .weight(1f)
                .clip(shape)
                .background(colors.onSurface.copy(alpha = 0.05f))
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colors.onSurface,
                ),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
fun DevToolButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        !enabled -> colors.onSurface.copy(alpha = 0.05f)
        primary -> colors.primary
        hovered -> colors.onSurface.copy(alpha = 0.16f)
        else -> colors.onSurface.copy(alpha = 0.08f)
    }
    val content = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
        primary -> colors.onPrimary
        else -> colors.onSurface
    }

    Box(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .hoverable(interaction, enabled = enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, fontSize = 12.sp, color = content, maxLines = 1)
    }
}

/** 与 [DevToolButton] 的圆角一起构成操作栏的统一观感。 */
@Composable
fun DevToolActionSpacer() {
    Spacer(Modifier.width(8.dp))
}

/** 工具内的一条提示（成功 / 失败）。 */
@Composable
fun DevToolMessage(message: String, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = message,
        fontSize = 11.sp,
        color = if (isError) colors.error else colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
