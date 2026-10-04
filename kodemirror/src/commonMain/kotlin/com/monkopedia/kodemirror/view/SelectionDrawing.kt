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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.ExtensionList

/** Extension to select the draw-selection extension. */
val drawSelection: Extension =
    ExtensionList(emptyList())

/**
 * Draw the cursor and selection ranges as a per-line [Modifier] overlay.
 *
 * Only draws the portion of the selection that intersects the given line,
 * using coordinates local to the line's composable.
 *
 * @param state  Current editor state.
 * @param lineFrom Document offset of the start of this line.
 * @param lineTo  Document offset of the end of this line.
 * @param theme  Editor theme for colors.
 * @param textLayoutResult Layout result for accurate character positioning.
 * @param cursorVisible <本仓库补丁> 光标此刻该不该画。做成**取值函数**而不是 Boolean：本仓库用
 *   它接闪烁，传 Boolean 会每半秒重组一次所有可见行，取值函数则只让这些行重绘（它在绘制里被
 *   调用，快照读取因此落在绘制阶段）。
 */
@Composable
fun Modifier.drawSelectionOverlay(
    state: EditorState,
    lineFrom: Int,
    lineTo: Int,
    theme: EditorTheme,
    textLayoutResult: TextLayoutResult? = null,
    tabOffsetMap: IntArray? = null,
    cursorVisible: () -> Boolean = { true }
): Modifier = this.drawWithContent {
    drawLineSelection(
        state,
        lineFrom,
        lineTo,
        theme,
        textLayoutResult,
        tabOffsetMap,
        cursorVisible
    )
    drawContent()
}

/** Default block cursor color (salmon/pink, matching upstream `#ff9696`). */
private val BLOCK_CURSOR_COLOR = Color(0xFFFF9696)

/**
 * Map a document-relative offset to the expanded text offset,
 * accounting for tab expansion. Returns the offset unchanged when
 * there is no tab offset map.
 */
private fun mapOffset(docOffset: Int, lineLength: Int, tabOffsetMap: IntArray?): Int {
    if (tabOffsetMap == null) return docOffset
    val index = docOffset.coerceIn(0, minOf(lineLength, tabOffsetMap.size - 1))
    return tabOffsetMap[index]
}

private fun DrawScope.drawLineSelection(
    state: EditorState,
    lineFrom: Int,
    lineTo: Int,
    theme: EditorTheme,
    textLayoutResult: TextLayoutResult?,
    tabOffsetMap: IntArray?,
    cursorVisible: () -> Boolean
) {
    // <本仓库补丁> 只读的编辑器既不画选区、也不画光标（见 `editable` facet）。
    //
    // 只读结果框（预览面板）里拖不出选区，那就不该把它画出来——否则用户拖过之后看到一片
    // 高亮，会以为「这里能选、能复制」。判断用 `editable` 而不是再看一个参数：`session.editable`
    // 就是这件事的唯一定义（见 `EditorSessionImpl.editable`）。
    if (!state.facet(editable)) return
    val lineLength = lineTo - lineFrom
    // Check for block cursors (vim normal/visual mode)
    val blockCursors = state.facet(blockCursorProvider)
        .flatMap { it.invoke() }
        .filter { it.offset in lineFrom..lineTo }
    val hasBlockCursors = blockCursors.isNotEmpty()

    val selection = state.selection
    for (range in selection.ranges) {
        val rangeFrom = range.from.value
        val rangeTo = range.to.value
        val rangeHead = range.head.value
        if (!range.empty) {
            // Draw selection highlight if it overlaps this line
            val selFrom = maxOf(rangeFrom, lineFrom)
            val selTo = minOf(rangeTo, lineTo)
            if (selFrom < selTo || (selFrom == selTo && selFrom > lineFrom)) {
                val extendsToNextLine = rangeTo > lineTo
                val expandedLineLen = mapOffset(
                    lineLength,
                    lineLength,
                    tabOffsetMap
                )
                drawLineSelectionRange(
                    mapOffset(
                        selFrom - lineFrom,
                        lineLength,
                        tabOffsetMap
                    ),
                    mapOffset(
                        selTo - lineFrom,
                        lineLength,
                        tabOffsetMap
                    ),
                    expandedLineLen,
                    theme.selection,
                    textLayoutResult,
                    extendsToNextLine
                )
            }
        }
        // Draw thin cursor only when block cursors are NOT active.
        // <本仓库补丁> 且只在 `cursorVisible()` 说该亮的时候画（上游画的是静态光标）。
        if (!hasBlockCursors && cursorVisible() && rangeHead in lineFrom..lineTo) {
            drawLineCursor(
                mapOffset(
                    rangeHead - lineFrom,
                    lineLength,
                    tabOffsetMap
                ),
                theme.cursor,
                textLayoutResult
            )
        }
    }

    // Draw block cursors at full line height
    for (cursor in blockCursors) {
        drawBlockCursor(
            mapOffset(
                cursor.offset - lineFrom,
                lineLength,
                tabOffsetMap
            ),
            cursor.alpha,
            textLayoutResult
        )
    }
}

private fun DrawScope.drawBlockCursor(
    offsetInLine: Int,
    alpha: Float,
    textLayoutResult: TextLayoutResult?
) {
    val lineHeight = size.height
    val textLen = textLayoutResult?.layoutInput?.text?.length ?: 0

    val x: Float
    val charWidth: Float
    // Vertical extent: default to the full line, override with the cursor rect's
    // row for wrapped lines so the block only covers the caret's visual row.
    var top = 0f
    var height = lineHeight

    if (textLayoutResult != null && offsetInLine < textLen) {
        // Character position: get exact bounds from text layout
        x = textLayoutResult.getHorizontalPosition(offsetInLine, true)
        val boundingBox = textLayoutResult.getBoundingBox(offsetInLine)
        charWidth = boundingBox.width.coerceAtLeast(4f)
        val cursorRect = textLayoutResult.getCursorRect(offsetInLine)
        top = cursorRect.top
        height = cursorRect.height
    } else if (textLayoutResult != null && textLen > 0) {
        // End of line: position after last character, use average char width
        x = textLayoutResult.getHorizontalPosition(textLen, true)
        charWidth = textLayoutResult.size.width.toFloat() / textLen
    } else {
        // Empty line: position at start, use fallback width
        x = 0f
        charWidth = lineHeight * 0.55f // approximate monospace char width
    }

    drawRect(
        color = BLOCK_CURSOR_COLOR.copy(alpha = alpha),
        topLeft = Offset(x, top),
        size = Size(charWidth, height)
    )
}

/**
 * Resolve the offset to query the line's [TextLayoutResult] for the caret rect,
 * or null when there is no layout to query (draw the line-start fallback).
 *
 * The layout lags the document by one frame: right after a keystroke the doc
 * change has applied but the text hasn't re-laid-out yet, so [offsetInLine] is
 * transiently `textLen + 1`. We must NOT bail to the line-start (column 0) in
 * that case — that produced a one-frame caret flash to column 0 on every
 * keypress (#146, regression from #76). Instead clamp into the current layout so
 * the caret sits at the line END for that single frame and snaps exact next
 * frame. The fallback is reserved for a genuinely absent layout (empty line).
 */
internal fun cursorLayoutOffset(offsetInLine: Int, textLen: Int?): Int? =
    if (textLen == null) null else offsetInLine.coerceIn(0, textLen)

private fun DrawScope.drawLineCursor(
    offsetInLine: Int,
    cursorColor: Color,
    textLayoutResult: TextLayoutResult?
) {
    val textLen = textLayoutResult?.layoutInput?.text?.length
    val safe = cursorLayoutOffset(offsetInLine, textLen)
    if (textLayoutResult != null && safe != null) {
        // Use the cursor rect so the caret only spans its actual visual row
        // (a full-height span on wrapped multi-row lines is wrong).
        val r = textLayoutResult.getCursorRect(safe)
        drawLine(
            color = cursorColor,
            start = Offset(r.left, r.top),
            end = Offset(r.left, r.bottom),
            strokeWidth = 2f
        )
    } else {
        // Fallback: start of line, full height (no layout — e.g. empty line).
        drawLine(
            color = cursorColor,
            start = Offset(0f, 0f),
            end = Offset(0f, size.height),
            strokeWidth = 2f
        )
    }
}

/** <本仓库补丁> 一个视觉行上的选区高亮块，坐标是本行这一层自己的本地坐标。 */
internal data class SelectionRowRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * <本仓库补丁> 一个文档行里的选区，落在每个**视觉行**上的高亮块。
 *
 * 上游把「一个文档行 = 一块矩形」写死了：左端取 `from` 的横坐标、右端取 `to` 的横坐标。不开
 * 折行时两者同处一个视觉行，看不出问题；开了折行（`lineWrapping`）之后，一条超长行在排版里
 * 是好几行，而 `getHorizontalPosition` 给的是**那个字符所在视觉行**的行内横坐标——夹在中间的
 * 整行整行因此被漏掉。全选一条超长行时，右端会取到**最后一个视觉行**行内那个横坐标，画出来
 * 就是左侧一条窄带：看着像没选上，其实选区是全的（复制出来是全的）。
 *
 * 这里按视觉行逐行算：起点行从 `from` 起，终点行到 `to` 止，中间那些行（以及选区延续到下一个
 * 文档行的最后一个视觉行）铺到容器右边缘；纵向取排版结果给的行顶 / 行底——只落在中间某个视觉
 * 行上的选区，因此也不会再按整条文档行的高度画成一大块（画布高度是这条折行行的总高）。
 * 不折行时 `lineCount` 为 1，结果与上游那块矩形逐像素一致。
 */
internal fun lineSelectionRowRects(
    layout: TextLayoutResult?,
    fromOffset: Int,
    toOffset: Int,
    lineLength: Int,
    containerWidth: Float,
    containerHeight: Float,
    extendsToNextLine: Boolean
): List<SelectionRowRect> {
    if (layout == null) {
        // Fallback: fraction-based (inaccurate but better than nothing)
        val startFraction = if (lineLength > 0) fromOffset.toFloat() / lineLength else 0f
        val endFraction = if (lineLength > 0) toOffset.toFloat() / lineLength else 1f
        val x = startFraction * containerWidth
        val endX = if (extendsToNextLine) containerWidth else endFraction * containerWidth
        return listOf(
            SelectionRowRect(x, 0f, maxOf(endX, x + 1f), containerHeight)
        )
    }

    // 上界取**排版结果里的**文本长度：打字后一帧内文档已经变了、排版还没跟上（见
    // `cursorLayoutOffset` 那段说明），这期间偏移会比排版里的文本长。
    val textLen = layout.layoutInput.text.length
    val from = fromOffset.coerceIn(0, textLen)
    val to = toOffset.coerceIn(0, textLen)
    val rowCount = layout.lineCount

    val startRow = layout.getLineForOffset(from)
    // 终点行按**最后一个被选中的字符**取：`to` 是开区间端点，正好停在折行处时它已经属于下一个
    // 视觉行了，照它取会多画一行（行首那一像素）。
    val endRow = if (to > from) layout.getLineForOffset(to - 1) else startRow
    val singleRow = rowCount <= 1

    return (startRow..endRow).map { row ->
        val top: Float
        val height: Float
        if (singleRow) {
            // 不折行：纵向仍铺满这一层（层高可能大于排版行高），与上游画法一致。
            top = 0f
            height = containerHeight
        } else {
            top = layout.getLineTop(row)
            height = (layout.getLineBottom(row) - top).coerceAtLeast(1f)
        }
        val left = if (row == startRow) {
            layout.getHorizontalPosition(from, true)
        } else {
            layout.getLineLeft(row)
        }
        // 这一行在文档偏移里的上界：下一个视觉行的起点；最后一个视觉行就是本行文本的末尾。
        val rowLimit = if (row + 1 < rowCount) layout.getLineStart(row + 1) else textLen
        val right = when {
            // 选区还接着往下走，这一行整行都是选中态。
            row < endRow -> containerWidth
            // 正好选到折行前的最后一个字符：铺到容器右边缘。本行末尾这一处只在选区还延续到
            // 下一个文档行时才铺满——否则高亮不该越过文本末端。
            to == rowLimit && (row + 1 < rowCount || extendsToNextLine) -> containerWidth
            else -> layout.getHorizontalPosition(to, true)
        }
        SelectionRowRect(
            left = left,
            top = top,
            right = maxOf(right, left + 1f),
            bottom = top + height
        )
    }
}

private fun DrawScope.drawLineSelectionRange(
    fromOffset: Int,
    toOffset: Int,
    lineLength: Int,
    selectionColor: Color,
    textLayoutResult: TextLayoutResult?,
    extendsToNextLine: Boolean
) {
    val rects = lineSelectionRowRects(
        layout = textLayoutResult,
        fromOffset = fromOffset,
        toOffset = toOffset,
        lineLength = lineLength,
        containerWidth = size.width,
        containerHeight = size.height,
        extendsToNextLine = extendsToNextLine
    )
    for (rect in rects) {
        drawRect(
            color = selectionColor,
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.right - rect.left, rect.bottom - rect.top)
        )
    }
}
