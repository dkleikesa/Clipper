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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.components.VerticalScrollbar
import com.qcmian.clipper.core.ui.components.VerticalScrollbarWidth
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.ui.components.DevToolScrollbarGap
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider

/** 「含义」列的宽度：最长的一条（`时区偏移（带冒号）`）也放得下，各行因此左缘对齐。 */
private val MeaningWidth = 140.dp

/** 两种风格的列宽：放得下列名（`Python`）与最长的写法（`yyyy` / `%Y`）。 */
private val JavaWidth = 62.dp
private val PythonWidth = 68.dp

/** 「写法要点」里那条写法列的宽度：最长的一条（`yyyy年MM月dd日`）也放得下。 */
private val SyntaxWidth = 132.dp

/**
 * 「占位符速查」：模板支持的全部写法，**两种风格并排**，末尾附几条不成字段的要点。
 *
 * 内容来自 [TimestampSyntax]，这里只管画。样式与数学工具的「语法帮助」是同一副（圆角卡片 +
 * 细分隔线 + 贴边的滚动条）：两处都是「摊开来看的参考材料」，长得不一样反而像两套东西。
 *
 * 表头不能省：`MM` 与 `%m` 两列都是等宽的符号，光看字形分不出哪边是 Java、哪边是 Python，
 * 而这两列恰恰是最容易写混的地方。
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
            FieldHeaderRow()
            TimestampSyntax.groups.forEach { group ->
                GroupTitle(group.title)
                group.fields.forEach { field -> FieldRow(field) }
            }

            DevToolSectionDivider(Modifier.padding(top = 10.dp, bottom = 2.dp))
            Text(
                text = "写法要点",
                fontSize = 12.sp,
                color = MaterialTheme.hintColor,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            TimestampSyntax.notes.forEach { note -> NoteRow(note) }
        }
        VerticalScrollbar(
            scrollState = scroll,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** 表头：四列各是什么（含义 / Java 写法 / Python 写法 / 示例），只在最上面写一次。 */
@Composable
private fun FieldHeaderRow() {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
        HeaderCell("含义", Modifier.width(MeaningWidth))
        HeaderCell("Java", Modifier.width(JavaWidth))
        HeaderCell("Python", Modifier.width(PythonWidth))
        HeaderCell("示例", Modifier.weight(1f))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text = text, fontSize = 10.sp, color = MaterialTheme.hintColor, modifier = modifier)
}

/** 一组字段的小标题：比正文浅一档，只是把「年月日」与「时分秒」之间划出层次。 */
@Composable
private fun GroupTitle(title: String) {
    Text(
        text = title,
        fontSize = 10.sp,
        color = MaterialTheme.hintColor,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/** 一行字段：含义 / Java 写法 / Python 写法（没有对应写法时一道「—」） / 示例。 */
@Composable
private fun FieldRow(field: TimestampSyntaxField) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = field.meaning,
            fontSize = 12.sp,
            color = colors.onSurface,
            modifier = Modifier.width(MeaningWidth),
        )
        CodeCell(field.java, JavaWidth, colors.onSurface)
        // 没有对应写法时那道「—」压暗一档：它是个空位，不该跟真写法一样抢眼。
        val pythonColor = if (field.python == null) MaterialTheme.hintColor else colors.onSurface
        CodeCell(field.python ?: "—", PythonWidth, pythonColor)
        Text(
            text = field.example,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 一行要点：左边是写法（等宽），右边是一句说明。 */
@Composable
private fun NoteRow(note: TimestampSyntaxNote) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = note.syntax,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(SyntaxWidth),
        )
        Text(
            text = note.note,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 等宽的写法格：两种风格的列都用它，字形一致才看得出是「同一类东西」。 */
@Composable
private fun CodeCell(text: String, width: Dp, color: Color) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = color,
        modifier = Modifier.width(width),
    )
}
