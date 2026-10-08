package com.qcmian.clipper.core.ui.code

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
 * 底色、行号、当前行底纹、括号配对底纹、折叠占位、装订线分隔线都取 [CodeColors] 里那一份色值，
 * 本项目的代码框因此只有一处配色出处。没有对应关系的项（搜索面板底色、提示浮层——本项目的代码框
 * 并不显示它们）从应用色板派生，免得万一露出来是一块与窗口无关的灰。
 *
 * 两处**刻意的取舍**：
 *  - 行号不分「当前行」：所有行号同色，因此把 `gutterActiveForeground` 也填成行号色，并且
 *    **不安装** `highlightActiveLineGutter`（它会给当前行的行号铺一条底色）。
 *  - 不配「不配对的括号」底色：`bracketMatching` 会给落单的括号上底纹，而这里对孤立括号是
 *    **不上色**的（见 `matchBracketPair`——它只认已配对的那部分），同样留成透明。
 */
@Composable
fun rememberKodemirrorTheme(colors: CodeColors): EditorTheme {
    val scheme = MaterialTheme.colorScheme
    val hint = MaterialTheme.hintColor
    val dark = colors.editorBackground.luminance() < 0.5f
    return remember(colors, scheme, hint, dark) {
        EditorTheme(
            background = colors.editorBackground,
            foreground = scheme.onSurface,
            cursor = scheme.primary,
            selection = scheme.primary.copy(alpha = if (dark) 0.30f else 0.25f),
            // 光标所在那一行整行铺一条底纹（只在可编辑时画，见 `highlightActiveLine` 的用法）。
            activeLineBackground = colors.currentLineBackground,
            // 装订线与正文**同一块底色**，只靠一条竖线分界。
            gutterBackground = colors.editorBackground,
            gutterForeground = hint,
            gutterActiveForeground = hint,
            gutterBorderColor = colors.gutterDivider,
            searchMatchBackground = scheme.primary.copy(alpha = 0.35f),
            searchMatchSelectedBackground = scheme.primary.copy(alpha = 0.20f),
            selectionMatchBackground = scheme.primary.copy(alpha = 0.10f),
            matchingBracketBackground = colors.bracketBackground,
            // 只认配对成功的括号，孤立括号不上色。
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
                // 正文上边距 4dp；装订线内容右端 6dp 之后就是那条分隔线。
                gutterStartPadding = 5.dp,
                gutterEndPadding = 6.dp,
                // 折叠箭头那一列的宽度。
                customGutterWidth = 18.dp,
                contentTopPadding = 4.dp,
                contentBottomPadding = 4.dp,
            ),
        )
    }
}

/**
 * 正文的文字度量与颜色：12sp / 16sp 等宽；[isError] 为真时整篇改用 `error` 色（一条失败说明照样
 * 交给同一个框显示）。
 *
 * KodeMirror 把这一份同时用在正文、行号与折叠箭头上，因此三者的行高是对齐的。
 */
fun kodemirrorContentStyle(scheme: ColorScheme, isError: Boolean): TextStyle =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = if (isError) scheme.error else scheme.onSurface,
    )
