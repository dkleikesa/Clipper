package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.components.HoverTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.writeTextFile

/**
 * 编辑区标题行上的那几个动作（结果框的保存 / 复制；输入框的打开 / 清空由 `DevToolInputField`
 * 自带）。
 *
 * 做成**常驻的小图标按钮**，而不是原先的一串 11sp 灰字。两个理由：
 *
 *  - 那串字挨着框名排在同一行，读起来是「输入 打开文件 清空 结果 排版中… 保存文件 复制」一句
 *    话，框名与动作分不开；换成图标之后，文字只剩下框名，「这行在说哪个框」一目了然。
 *  - 文字链接的可点区域只有字那么高，而这几个动作是编辑时的高频操作。
 *
 * 刻意**不做成悬停才出现**：鼠标要先去「猜」哪儿有按钮，而这一行本来就空着，藏起来省的是一块
 * 用不上的地方，代价却是每次都得先晃一下鼠标。
 *
 * 仍然贴着各自的框：图标跟着框走，「打开文件」只对输入框成立、「保存文件」只对结果框成立。
 */
@Composable
fun DevToolFieldAction(
    kind: ClipperIconKind,
    tooltip: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    HoverTooltip(text = tooltip, positioning = TooltipAnchorPosition.Above) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    if (hovered && enabled) colors.onSurface.copy(alpha = 0.08f) else Color.Transparent
                )
                .hoverable(interaction, enabled = enabled)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            ClipperIcon(
                kind = kind,
                size = 15.dp,
                tint = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }
    }
}

/**
 * 结果框标题行上的动作：保存文件、复制。
 *
 * 「复制」放在这里而不是工具栏上：它产出的是**这个框里**的内容，跟框放在一起才不会让人去找。
 *
 * @param suggestedFileName 保存对话框里预填的文件名，由工具给（它才知道自己吐的是什么格式）。
 */
@Composable
fun DevToolResultActions(
    value: String,
    host: DevToolHost,
    suggestedFileName: String,
) {
    val canAct = value.isNotEmpty()
    DevToolFieldAction(
        kind = ClipperIconKind.SAVE,
        tooltip = "保存为文件",
        enabled = canAct,
        onClick = {
            val path = host.pickFileToSave(suggestedFileName) ?: return@DevToolFieldAction
            host.showStatus(
                if (writeTextFile(path, value)) "已保存到 $path" else "写不进这个位置：$path"
            )
        },
    )
    Spacer(Modifier.width(4.dp))
    DevToolFieldAction(
        kind = ClipperIconKind.COPY,
        tooltip = "复制结果",
        enabled = canAct,
        onClick = { host.copyToClipboard(value) },
    )
}
