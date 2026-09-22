package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.feature.history.state.DeepSearchState

/**
 * 这一行的固定高度。
 *
 * 与历史行一样是**精确值**：窗口高度是列表总高的纯函数（见 `historyHeightMetrics`），
 * 这一行也是列表里的一行，高度算错会让窗口与滚动条同时偏一点。
 */
internal val DeepSearchFooterHeight: Dp = Popup.itemHeight + Popup.verticalSeparatorPadding * 2

/**
 * 滚动列表末尾的「全文搜索」入口。
 *
 * 它就在内容的最后一行，而不是悬在界面上方：默认搜索只覆盖标题，而用户滚到底这个动作本身
 * 就是「这些还不够」的表达，入口出现在那一刻的位置最不打扰。
 *
 * 三种状态共用一个位置——可触发、进行中、已结束——因此它同时充当进度与结果说明，不需要
 * 另外的提示条。也正因如此，只有 [DeepSearchState.AVAILABLE] 那一种才是按钮。
 */
@Composable
internal fun DeepSearchFooter(
    state: DeepSearchState,
    hits: Int,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val runnable = state == DeepSearchState.AVAILABLE
    val label = when (state) {
        DeepSearchState.AVAILABLE -> "在正文中继续搜索"
        DeepSearchState.RUNNING -> "正在搜索正文…"
        DeepSearchState.DONE -> if (hits > 0) "正文中又找到 $hits 条" else "正文中没有更多结果"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DeepSearchFooterHeight)
            // 只有可触发的那一种接受点击：另外两种是一条说明，不该看起来像按钮。
            .then(if (runnable) Modifier.clickable(onClick = onRun) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            // 可触发时用主色（它是个入口），其余时候退成次要色（它只是说明）。
            color = if (runnable) colors.primary else colors.onSurfaceVariant,
        )
    }
}
