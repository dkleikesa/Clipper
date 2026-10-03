package com.qcmian.clipper.devtools.tools.timestamp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.components.VerticalScrollbar
import com.qcmian.clipper.core.ui.components.VerticalScrollbarWidth
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.ui.components.DevToolScrollbarGap
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider

/** 符号列的宽度：最长的一条（`%Y-%m-%d %H:%M:%S`）也放得下，其余各行因此左缘对齐。 */
private val SymbolWidth = 132.dp

/**
 * 「占位符速查」：模板支持的全部写法，按「日期 / 时间 / 其它 / 写法要点」分节列出。
 *
 * 内容来自 [TimestampSyntax]，这里只管画。样式与数学工具的「语法帮助」是同一副（圆角卡片 +
 * 细分隔线 + 贴边的滚动条）：两处都是「摊开来看的参考材料」，长得不一样反而像两套东西。
 *
 * 分三列而不是两列：**示例单独一列**才扫得动——左边是「怎么写」，右边是「写出来是什么」，
 * 中间的含义只在看不出所以然时才需要读。
 */
@Composable
internal fun TimestampSyntaxPanel(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val scroll = rememberScrollState()

    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.onSurface.copy(alpha = 0.04f))
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                .verticalScroll(scroll)
                // 右端多让一条滚动条的宽度再加一点间隙，免得最长的那一行被滑块压住。
                .padding(
                    start = 12.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                    end = 12.dp + VerticalScrollbarWidth + DevToolScrollbarGap,
                ),
        ) {
            TimestampSyntax.sections.forEachIndexed { index, section ->
                if (index > 0) {
                    DevToolSectionDivider(Modifier.padding(vertical = 6.dp))
                }
                Text(
                    text = section.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.hintColor,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                section.rows.forEach { row -> SyntaxRow(row) }
            }
        }
        VerticalScrollbar(
            scrollState = scroll,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** 速查里的一行：符号（等宽） / 含义 / 写法示例（等宽、靠右，与上一行对齐成一列）。 */
@Composable
private fun SyntaxRow(row: TimestampSyntaxRow) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.symbol,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = colors.onSurface,
            modifier = Modifier.width(SymbolWidth),
        )
        Text(
            text = row.meaning,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.example,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
