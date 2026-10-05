package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.components.VerticalScrollbar
import com.qcmian.clipper.core.ui.components.VerticalScrollbarWidth
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor

/**
 * 结果行的标签列宽度：最长的一条（时间戳工具的「毫秒级时间戳」）也放得下。
 *
 * 两个用它的工具读同一个值，标签列因此一样宽——它们并排走进同一套界面语言，而不是各写各的。
 */
val DevToolResultLabelWidth = 92.dp

/**
 * 结果列表：逐行「标签 + 值」，点一行复制那一行的值。
 *
 * 抽出来是因为**两个工具的结果长得一模一样**：时间戳转换列它的各种写法（秒级 / 毫秒级时间戳、
 * 各种日期时间），Hash 列各算法的摘要。两者的诉求也一致——一眼从上往下核对、点一下把某一行拿走。
 * 先前 Hash 自己画了个带边框的卡片，于是同一个软件里出现了两种「结果区」，现在归这一处。
 *
 * 泛型 [T] 让两边都能直接喂自己的领域类型（`TimestampField` / `HashResult`），不必先摊平成
 * 一对字符串再传——摊平那一步会在每个工具里各写一遍取值逻辑，正是要避免的分叉。
 *
 * 空列表由调用方自己交代（提示文案因工具而异），这里只负责画非空的那一份。
 *
 * @param label 左侧标签列的文字，宽度固定为 [labelWidth]，各行值因此左缘对齐。
 * @param value 右侧值，等宽字体；长值（完整摘要）传 `wrapValues = true`，否则尾巴会被省略号吃掉。
 * @param onCopy 点整行时复制什么——通常是 [value] 本身，但由调用方说了算。
 * @param primary 给这一行「上主色」：本次真正要拿走的那些值（时间戳的秒 / 毫秒）靠它从附带
 *   信息里被挑出来。默认为假，摘要这种每一行都同等重要的列表因此不用特判。
 * @param wrapValues 值太长时折行（`true`）还是单行省略（`false`）。见 [value]。
 * @param enabled 还在算的时候置假：旧结果留着能看，但**点不动、也点不出复制图标**，免得把
 *   上一份输入的结果拷走（与 Base64 工具「正在算时留着上一份、但不能拷出去」同一条约定）。
 */
@Composable
fun <T> DevToolResultList(
    items: List<T>,
    label: (T) -> String,
    value: (T) -> String,
    onCopy: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelWidth: Dp = DevToolResultLabelWidth,
    primary: (T) -> Boolean = { false },
    wrapValues: Boolean = false,
    enabled: Boolean = true,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                // 让出滚动条的位置，免得最后一行被滑块压住。
                .padding(end = VerticalScrollbarWidth + DevToolScrollbarGap),
        ) {
            items.forEach { item ->
                DevToolResultRow(
                    labelText = label(item),
                    valueText = value(item),
                    primary = primary(item),
                    wrapValue = wrapValues,
                    enabled = enabled,
                    labelWidth = labelWidth,
                    onCopy = { onCopy(item) },
                )
            }
        }
        VerticalScrollbar(
            scrollState = scroll,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * 结果里的一行。
 *
 * 整行可点即复制：值有长有短，让人精确拖选一段很容易选歪，点整行则没有落点要求。复制图标只在
 * 悬停时出现——平时不占视觉重量，「这一行能点」靠底色变化表达。
 */
@Composable
private fun DevToolResultRow(
    labelText: String,
    valueText: String,
    primary: Boolean,
    wrapValue: Boolean,
    enabled: Boolean,
    labelWidth: Dp,
    onCopy: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(4.dp)
    // 不能动作时连悬停高亮也不给：亮起来却点不动，比一直是灰的更让人困惑。
    val highlighted = hovered && enabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlighted) colors.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction, enabled = enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onCopy,
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = labelText,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.width(labelWidth),
        )
        Text(
            text = valueText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            // 主色挑出真正要拿走的数；过期（此刻不能动作）时整体调淡，提示它不是眼前输入的结果。
            color = when {
                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                primary -> colors.primary
                else -> colors.onSurface
            },
            maxLines = if (wrapValue) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (highlighted) {
            ClipperIcon(ClipperIconKind.COPY, size = 14.dp, tint = colors.onSurfaceVariant)
        }
    }
}
