package com.qcmian.clipper.devtools.ui.components.code

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monkopedia.kodemirror.view.EditorLayout
import com.qcmian.clipper.core.ui.theme.hintColor
import com.monkopedia.kodemirror.view.EditorTheme

/**
 * 把本项目的代码配色（[CodeColors]）与应用色板翻译成 KodeMirror 的 [EditorTheme]。
 *
 * **一栏一栏对着现有编辑框填**，为的是两套实现并排看过去是同一个框：底色、行号、当前行底纹、
 * 括号配对底纹、折叠占位、装订线分隔线，全都取原生那一侧用的同一个色值。没有对应关系的东西
 * （搜索面板底色、提示浮层——本项目的代码框并不显示它们）从应用色板派生，免得万一露出来是一块
 * 与窗口无关的灰。
 *
 * 两处**刻意的取舍**：
 *  - 行号不分「当前行」：原生框里所有行号同色，所以这里把 `gutterActiveForeground` 也填成行号色，
 *    并且**不安装** `highlightActiveLineGutter`（它会给当前行的行号铺一条底色，原生框没有）。
 *  - 不配「不配对的括号」底色：`bracketMatching` 会给落单的括号上底纹，而原生框对孤立括号是
 *    **不上色**的（见 `matchBracketPair`——它只认已配对的那部分），这里同样留成透明。
 */
@Composable
internal fun rememberKodemirrorTheme(colors: CodeColors): EditorTheme {
    val scheme = MaterialTheme.colorScheme
    val hint = MaterialTheme.hintColor
    val dark = colors.editorBackground.luminance() < 0.5f
    return remember(colors, scheme, hint, dark) {
        EditorTheme(
            background = colors.editorBackground,
            foreground = scheme.onSurface,
            cursor = scheme.primary,
            selection = scheme.primary.copy(alpha = if (dark) 0.30f else 0.25f),
            // 原生框：光标所在那一行整行铺一条底纹（只在聚焦时画，见 `highlightActiveLine` 的用法）。
            activeLineBackground = colors.currentLineBackground,
            // 原生框的装订线与正文**同一块底色**，只靠一条竖线分界。
            gutterBackground = colors.editorBackground,
            gutterForeground = hint,
            gutterActiveForeground = hint,
            gutterBorderColor = colors.gutterDivider,
            searchMatchBackground = scheme.primary.copy(alpha = 0.35f),
            searchMatchSelectedBackground = scheme.primary.copy(alpha = 0.20f),
            selectionMatchBackground = scheme.primary.copy(alpha = 0.10f),
            matchingBracketBackground = colors.bracketBackground,
            // 只认配对成功的括号，孤立括号不上色——与原生框一致。
            nonMatchingBracketBackground = Color.Transparent,
            panelBackground = scheme.surface,
            panelBorderColor = scheme.outline,
            buttonBackground = scheme.surface,
            buttonBorderColor = scheme.outline,
            inputBackground = colors.editorBackground,
            inputBorderColor = scheme.outline,
            tooltipBackground = scheme.surface,
            foldPlaceholderColor = colors.foldPlaceholder,
            foldPlaceholderBackground = colors.foldPlaceholderBackground,
            // 装订线不因当前行变色（同上面的行号说明）。
            activeLineGutterBackground = Color.Transparent,
            dark = dark,
            layout = EditorLayout(
                // 原生框：正文上边距 4dp；装订线内容右端 6dp 之后就是那条分隔线（GutterEndPadding）。
                gutterStartPadding = 5.dp,
                gutterEndPadding = 6.dp,
                // 折叠箭头那一列：与原生框的 ChevronColumnWidth 取同一个值。
                customGutterWidth = 18.dp,
                contentTopPadding = 4.dp,
                contentBottomPadding = 4.dp,
            ),
        )
    }
}

/**
 * 正文的文字度量与颜色，对应原生框里的 `textStyle`：同样的 12sp / 16sp 等宽；[isError] 为真时
 * 整篇改用 `error` 色（原生框就是这么做的——一条失败说明照样交给同一个框显示）。
 *
 * KodeMirror 把这一份同时用在正文、行号与折叠箭头上，因此两套实现的行高是对齐的。
 */
internal fun kodemirrorContentStyle(scheme: ColorScheme, isError: Boolean): TextStyle =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = if (isError) scheme.error else scheme.onSurface,
    )
