package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.devtools.api.DevToolHost

/**
 * 一行「文件」控件：可敲的路径输入框 + 拖入文件的落点 + 打开 / 清除两个动作。
 *
 * 这几样凑成一个控件，是因为它们说的是同一件事——**这个文件**：框里显示它是谁，「打开」换掉它，
 * 往框里拖也是，垃圾桶清掉它。拆开摆（比如把两个图标挪到这一行的另一头）就会被读成别的东西，
 * 实测被读成「清空密码」——它旁边正好是密码框。
 *
 * 宽度由调用方给：通常传 `Modifier.weight(1f)`，让它的右缘**只由这一行**决定、与文件名长短无关
 * ——右边那两个动作的位置因此是固定的（跟着名字跑的话，名字一变按钮就跳）。
 *
 * 内容不限于一个文件：它只管「这个字符串是一条路径」，密钥库、APK、导出目标都适用；路径对不对由
 * 调用方在真正用它的时候判。
 *
 * **拖入时会描一圈主色**（见 [devToolInputDrop] 的 `onHoverChange`）：一页可能有几个文件格
 * （签名页的 APK 与 KeyStore 各一个），不提示就不知道松手会落到哪一个。
 *
 * **粘贴不在这里**：`⌘V` 要回答的是「剪贴板里的文件落到**哪一个**格」，一页有几个文件格时还得
 * 连带切页签——那是页面的分流，控件做不了，由工具登记 `DevToolPasteKey`（见 `ApkSignDevTool`）。
 * 因此占位文案里若写了「粘贴」，得是工具真的接上了那一路。
 *
 * @param onValueChange 用户敲进来的字。**调用方通常要防抖再拿它去读盘**——每敲一个字读一次不行。
 * @param onDropFiles 拖进来的文件（给绝对路径）。落点挂在框上，谁都能拖；怎么分流（按内容还是按
 *   这一格的位置）由调用方定。
 * @param onOpen 「打开」被点了。弹对话框这件事留给调用方——它才知道要挑什么、挑错了怎么交代。
 * @param clearTooltip 垃圾桶的悬浮说明。**必须写清清的是什么**（「清除 KeyStore」）：「清除」两个字
 *   挨着别的控件时会被读成「清掉旁边那个」——它旁边正好是密码框。
 */
@Composable
fun DevToolFileField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    host: DevToolHost,
    onDropFiles: (List<String>) -> Unit,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    clearTooltip: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    var dragHover by remember { mutableStateOf(false) }
    val dropModifier = Modifier.devToolInputDrop(
        host = host,
        onFiles = onDropFiles,
        // 这个控件收文件，不收图片：往一条路径里丢一张图没有意义。
        onImage = null,
        onHoverChange = { dragHover = it },
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.weight(1f).then(dropModifier)) {
            DevToolSingleLineField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth(),
            )
            if (dragHover) {
                Box(Modifier.matchParentSize().clip(shape).border(1.dp, colors.primary, shape))
            }
        }
        Spacer(Modifier.width(6.dp))
        // 文案按**当前**值算：空着是「打开文件」，已经有东西是「换一个文件」。
        DevToolFieldAction(
            kind = ClipperIconKind.FOLDER,
            tooltip = if (value.isEmpty()) "打开文件" else "换一个文件",
            onClick = onOpen,
        )
        Spacer(Modifier.width(4.dp))
        DevToolFieldAction(
            kind = ClipperIconKind.TRASH,
            tooltip = clearTooltip,
            enabled = value.isNotEmpty(),
            onClick = onClear,
        )
    }
}
