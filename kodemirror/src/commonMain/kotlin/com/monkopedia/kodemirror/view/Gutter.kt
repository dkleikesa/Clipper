/*
 * Copyright 2026 Jason Monk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Originally based on CodeMirror 6 by Marijn Haverbeke, licensed under MIT.
 * See NOTICE file for details.
 */
package com.monkopedia.kodemirror.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.Facet
import com.monkopedia.kodemirror.state.LineNumber
import com.monkopedia.kodemirror.state.RangeValue

/** A gutter marker contributes to a specific line's gutter column. */
abstract class GutterMarker : RangeValue() {

    /** Render the marker content. */
    @Composable
    abstract fun Content(theme: EditorTheme)
}

/**
 * Identifies the type of a gutter column.
 *
 * Standard types like [LineNumbers] and [ActiveLineGutter] are used for
 * built-in gutter functionality. Use [Custom] for user-defined gutters.
 */
sealed class GutterType {
    /** The line numbers gutter column. */
    data object LineNumbers : GutterType()

    /** Marker-only column that highlights the active line's gutter. */
    data object ActiveLineGutter : GutterType()

    /** A custom gutter identified by a name. */
    data class Custom(val name: String) : GutterType()
}

/** Configuration for a gutter column. */
data class GutterConfig(
    /** Identifies this gutter column's type. */
    val type: GutterType? = null,
    /** Called for each visible line to get the marker, or null. */
    val lineMarker: ((EditorSession, Int) -> GutterMarker?)? = null,
    /** Returns true when the gutter markers need to be recalculated. */
    val lineMarkerChange: ((ViewUpdate) -> Boolean)? = null,
    /**
     * Called when the gutter is clicked for a given line.
     * Receives the session and the line-start position.
     * Returns true if the click was handled.
     */
    val lineMarkerClick: ((EditorSession, Int) -> Boolean)? = null,
    /** Padding on the right of gutter content. */
    val renderEmptyElements: Boolean = false,
    /** Initial marker for the gutter (shown before any lines). */
    val initialSpacer: ((EditorSession) -> GutterMarker)? = null,
    /** Marker shown when the gutter is active (cursor on line). */
    val updateSpacer: ((GutterMarker, ViewUpdate) -> GutterMarker)? = null
)

/** Facet that collects all registered gutter configurations. */
val gutters: Facet<GutterConfig, List<GutterConfig>> = Facet.define()

/** Create an extension that adds a gutter column. */
fun gutter(config: GutterConfig): Extension = gutters.of(config)

/**
 * A composable that renders all registered gutter columns alongside the
 * editor content.
 *
 * Place this to the left of the [EditorSession] composable in a [Row].
 */
@Composable
fun GutterView(session: EditorSession, lineNumber: Int, modifier: Modifier = Modifier) {
    val theme = LocalEditorTheme.current
    val lineNum = LineNumber(lineNumber)
    val isActive = session.state.doc.lineAt(session.state.selection.main.head).number == lineNum
    val line = session.state.doc.line(lineNum)
    val configs = session.state.facet(gutters)
    val hasActiveLineGutter = isActive &&
        configs.any { it.type == GutterType.ActiveLineGutter }

    Row(
        modifier = Modifier
            .then(
                if (hasActiveLineGutter) {
                    modifier.background(theme.activeLineGutterBackground)
                } else {
                    modifier
                }
            )
            // <本仓库补丁> 装订线整列给回**箭头**光标。编辑器给整块面板设了 I 形（见 `KodeMirror`
            // 里那处 `pointerHoverIcon(PointerIcon.Text)`），而装订线是它的一部分，于是鼠标移到
            // 行号或折叠箭头上时仍然显示「这里能插字」——那里既不能落光标也不能选中文本。
            // 折叠那一列由各自的 `clickable` 再改成手型（子节点优先），不受这一行影响。
            .pointerHoverIcon(PointerIcon.Default),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (config in configs) {
            if (config.type == GutterType.LineNumbers) {
                // Line number column
                Box(
                    modifier = Modifier.weight(1f).padding(
                        start = theme.layout.gutterStartPadding,
                        end = theme.layout.gutterEndPadding
                    ),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    val contentStyle = LocalContentTextStyle.current
                    BasicText(
                        text = lineNumber.toString(),
                        style = contentStyle.copy(
                            color = if (isActive) {
                                theme.gutterActiveForeground
                            } else {
                                theme.gutterForeground
                            },
                            textAlign = TextAlign.End
                        )
                    )
                }
            } else if (config.lineMarker != null) {
                // Other gutter columns (fold gutter, etc.)
                val clickHandler = config.lineMarkerClick
                Box(
                    // <本仓库补丁> `lineMarkerClick` 挂着时才整格可点。上游把它挂在每一格上，
                    // 于是「这一行没有箭头」的空格也成了可点区域、悬停还会亮一块底色——看着就是
                    // 平白多出来一个按钮。折叠 / 展开改由箭头自己接（见 `FoldGutterMarker`），
                    // 这里因此通常没有 handler；留着这条分支是给别的 gutter 列用的。
                    modifier = Modifier.width(theme.layout.customGutterWidth)
                        .let { mod ->
                            if (clickHandler != null) {
                                mod.fillMaxHeight().clickable {
                                    clickHandler(session, line.from.value)
                                }
                            } else {
                                mod
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val marker = config.lineMarker.invoke(session, line.from.value)
                    if (marker != null) {
                        marker.Content(theme)
                    }
                }
            }
        }
    }
}

/**
 * Facet that allows extensions to contribute custom
 * [GutterMarker]s to the line number gutter column. Each
 * provider maps a line start position to an optional marker.
 */
val lineNumberMarkers: Facet<
    (
        view: EditorSession,
        lineFrom: Int
    ) -> GutterMarker?,
    List<(view: EditorSession, lineFrom: Int) -> GutterMarker?>
    > =
    Facet.define()

/**
 * Extension that adds line numbers to the editor gutter.
 */
val lineNumbers: Extension = gutter(
    GutterConfig(
        type = GutterType.LineNumbers,
        // line numbers rendered by GutterView composable
        lineMarker = { _, _ -> null }
    )
)

/**
 * Extension that highlights the active line's gutter entry.
 * Works in conjunction with [highlightActiveLine].
 */
val highlightActiveLineGutter: Extension = gutter(
    GutterConfig(
        type = GutterType.ActiveLineGutter
    )
)
