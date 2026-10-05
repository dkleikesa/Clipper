package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 编解码工具的两页：编码与解码。Base64 / URL / 条码三个工具共用这一个定义，页签上的字因此只有
 * 一处写着，改也只改这一处。
 *
 * 方向是**显式**的，不靠猜：`test`、`abcd` 这类普通词也是合法 Base64，猜错方向比多按一下更烦人。
 * 内容从剪贴板带进来时，工具会替用户落在多半想要的那一页（见各工具的 `LaunchedEffect(input)`），
 * 但落在哪一页始终看得见、随时能改。
 */
enum class DevToolDirection(val title: String) {
    Encode("编码"),
    Decode("解码"),
}

/**
 * 页签那一行的高度。比工具栏里的控件（30dp）高一档：它换的是**一整页**，该比旁边那些开关显眼。
 */
private val TabHeight = 34.dp

/** 选中那一道线的高度。 */
private val TabIndicatorHeight = 2.dp

/**
 * 方向页签：把「编码」与「解码」画成两页，而不是工具栏里的一枚分段控件。
 *
 * 为什么要有它：Base64 / URL 这类工具的工具栏上原本躺着一枚 [DevToolSegmentedControl]，与旁边的
 * 「规则」「URL 安全」长得一模一样。于是「换一个方向」看上去跟「改一个选项」是同一件事——可它换掉
 * 的是**整页**：输入框叫什么、里面装什么、结果画成什么，全都跟着变。页签把这件事说出来：一条横贯
 * 的底线，选中的那一页在线上压一道主色。
 *
 * 与 [DevToolSegmentedControl] 的分工是刻意的：那个是**同一页里**的若干取值（严格 / URL / 表单），
 * 这个换的是整页。因此两者不同长相——一条线上的一道粗线 vs 一条轨道里的一块实心色。
 *
 * @param options 有几页，声明顺序即从左到右的顺序。
 * @param optionLabel 每页上的短名。
 */
@Composable
fun <T> DevToolTabBar(
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // 每一页一份交互源：悬停高亮各算各的。页数恒定，按页数记忆即可。
    val interactions = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    val baseline = colors.outline.copy(alpha = 0.5f)
    val indicator = colors.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TabHeight)
            // 基线画在页签**下面的那一层**：选中的页签在自己那一段上盖一道主色，正好压在线上，
            // 读起来是「这一页是从这条线上翻开的」。若把线画成页签下面的另一个 Box，选中的那一段
            // 底下会多出一条灰线，看着像两条。
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(baseline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            val hovered by interactions[index].collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    // 上圆角：页签像一张从基线上立起来的卡片，与工具栏里那些方块控件不同族。
                    .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                    .background(
                        // 选中的那一页不再铺底色：它的记号是底下那道主色，再叠一层底反而糊。
                        if (hovered && !isSelected) colors.onSurface.copy(alpha = 0.06f) else Color.Transparent
                    )
                    // 主色那一道画在**页签自己身上**（铺满宽度，压在基线上），而不是另外摆一个
                    // `fillMaxWidth()` 的子节点：子节点会把「按内容定宽」的页签撑成整行宽，第二个
                    // 页签就只剩 0px 了——页签的宽只能由上面那行字决定。
                    .drawBehind {
                        if (!isSelected) return@drawBehind
                        val height = TabIndicatorHeight.toPx()
                        drawRect(
                            color = indicator,
                            topLeft = Offset(0f, size.height - height),
                            size = Size(size.width, height),
                        )
                    }
                    .hoverable(interactions[index])
                    .clickable(interactionSource = interactions[index], indication = null) {
                        onSelect(option)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = optionLabel(option),
                    fontSize = 13.sp,
                    color = if (isSelected) colors.primary else colors.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
