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
package com.monkopedia.kodemirror.language

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.monkopedia.kodemirror.lezer.common.NodeProp
import com.monkopedia.kodemirror.lezer.common.SyntaxNode
import com.monkopedia.kodemirror.lezer.common.Tree
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.Facet
import com.monkopedia.kodemirror.state.LineNumber
import com.monkopedia.kodemirror.state.RangeSet
import com.monkopedia.kodemirror.state.RangeSetBuilder
import com.monkopedia.kodemirror.state.StateEffect
import com.monkopedia.kodemirror.state.StateEffectType
import com.monkopedia.kodemirror.state.StateField
import com.monkopedia.kodemirror.state.StateFieldSpec
import com.monkopedia.kodemirror.state.TransactionSpec
import com.monkopedia.kodemirror.state.endPos
import com.monkopedia.kodemirror.state.extensionListOf
import com.monkopedia.kodemirror.view.Decoration
import com.monkopedia.kodemirror.view.DecorationSet
import com.monkopedia.kodemirror.view.EditorSession
import com.monkopedia.kodemirror.view.EditorTheme
import com.monkopedia.kodemirror.view.GutterConfig
import com.monkopedia.kodemirror.view.GutterMarker
import com.monkopedia.kodemirror.view.GutterType
import com.monkopedia.kodemirror.view.KeyBinding
import com.monkopedia.kodemirror.view.LocalContentTextStyle
import com.monkopedia.kodemirror.view.LocalEditorSession
import com.monkopedia.kodemirror.view.LocalEditorTheme
import com.monkopedia.kodemirror.view.ReplaceDecorationSpec
import com.monkopedia.kodemirror.view.WidgetType
import com.monkopedia.kodemirror.view.decorations
import com.monkopedia.kodemirror.view.gutter

/** A range that can be folded. */
data class FoldRange(val from: DocPos, val to: DocPos)

/**
 * Facet for registering fold range providers.
 *
 * A fold service receives the state and a line-start position,
 * and returns a [FoldRange] if that line can be folded.
 */
val foldService:
    Facet<(EditorState, DocPos) -> FoldRange?, List<(EditorState, DocPos) -> FoldRange?>> =
    Facet.define()

/**
 * A node prop that attaches fold information to node types.
 *
 * The function receives a [SyntaxNode] and the state, and returns
 * a [FoldRange] or null.
 */
val foldNodeProp: NodeProp<(SyntaxNode, EditorState) -> FoldRange?> = NodeProp()

/**
 * Helper that creates a fold range covering the inside of a node
 * (excluding the first and last characters, typically brackets).
 */
fun foldInside(node: SyntaxNode): FoldRange? {
    val first = node.firstChild ?: return null
    val last = node.lastChild ?: return null
    if (first.to >= last.from) return null
    return FoldRange(DocPos(first.to), DocPos(last.from))
}

/** Effect to fold a range. */
val foldEffect: StateEffectType<FoldRange> = StateEffect.define(
    map = { range, changes ->
        val from: DocPos = changes.mapPos(range.from, 1)
        val to: DocPos = changes.mapPos(range.to, -1)
        if (from < to) FoldRange(from, to) else null
    }
)

/** Effect to unfold a range. */
val unfoldEffect: StateEffectType<FoldRange> = StateEffect.define(
    map = { range, changes ->
        val from: DocPos = changes.mapPos(range.from, 1)
        val to: DocPos = changes.mapPos(range.to, -1)
        if (from < to) FoldRange(from, to) else null
    }
)

/**
 * 折叠占位符的 widget 定义（`ReplaceDecoration` 上的那一份）。
 *
 * **它现在画不到屏幕上**：上游那套 widget 渲染（`KodeMirror.kt` 里的 `inlineWidgets`）只收
 * `WidgetDecoration`，替换类装饰带的 widget 从来没被收集过。屏幕上的 `{…}` 里那个 `…` 是
 * `DecorationApplication` 注进正文的一个字符，点击落点也走那条线（见 [unfoldFoldAt] 与
 * `KodeMirror` 里正文那一层的命中判定）——所以这里不再挂 `clickable`：真点得着的是那个字符，
 * 给一个画不出来的节点挂点击只会让人以为点在它身上。
 *
 * 留着这个类是因为它仍是「折叠占位符长什么样」的唯一定义（底色圆角小方块），与
 * [FoldGutterMarker] 里那枚箭头一起构成折叠标记的全貌；上游哪天把替换类 widget 也接上，
 * 这里就是现成的画面。
 */
private class FoldWidget(private val range: FoldRange) : WidgetType() {
    @Composable
    override fun Content() {
        val theme = LocalEditorTheme.current
        val contentStyle = LocalContentTextStyle.current
        val shape = RoundedCornerShape(2.dp)
        Box(
            modifier = Modifier
                .padding(horizontal = 1.dp)
                .background(theme.foldPlaceholderBackground, shape)
                .border(1.dp, theme.foldPlaceholderColor.copy(alpha = 0.3f), shape)
                .padding(horizontal = 1.dp)
        ) {
            BasicText(
                text = "\u2026",
                style = contentStyle.copy(
                    color = theme.foldPlaceholderColor
                )
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is FoldWidget && other.range == range

    override fun hashCode(): Int = range.hashCode()
}

/**
 * <本仓库补丁> 展开一个已折叠的区间：装订线箭头那一格被点，走的就是它。
 *
 * 区间由调用方拿着（见 [FoldGutterMarker]），因此这里不必再去查一遍折叠状态。
 */
internal fun unfoldFold(session: EditorSession, range: FoldRange) {
    session.dispatch(
        TransactionSpec(
            effects = listOf(unfoldEffect.of(range))
        )
    )
}

/**
 * <本仓库补丁> 展开**落在 [pos] 上**的那个折叠区间（`pos` 是文档偏移）；没有就什么都不做。
 *
 * 给「点正文里那个 `…`」用：`…` 是 `DecorationApplication` 注进正文的一个字符，手势那一层只有
 * 屏幕坐标，换算回文档偏移之后拿到的正是折叠区间的起点（见 `KodeMirror` 里正文那一层的命中判定）。
 *
 * 与 [unfoldCode] 的区别是它不碰选区：那一个要求光标已经落在折叠区间上，是给键位命令用的。
 */
internal fun unfoldFoldAt(session: EditorSession, pos: Int) {
    var range: FoldRange? = null
    foldedRanges(session.state).between(DocPos(pos), DocPos(pos)) { from, to, _ ->
        range = FoldRange(DocPos(from), DocPos(to))
        false
    }
    range?.let { unfoldFold(session, it) }
}

/**
 * State field that tracks the set of currently folded ranges.
 * Folded ranges are represented as [ReplaceDecoration]s.
 */
val foldState: StateField<DecorationSet> = StateField.define(
    StateFieldSpec(
        create = { RangeSet.empty() },
        update = { decos, tr ->
            var result = decos.map(tr.changes)
            for (effect in tr.effects) {
                val fold = effect.asType(foldEffect)
                if (fold != null) {
                    val range = fold.value
                    // 区间交给 widget 自己：点开占位符时要照着它派发 unfold（见 [FoldWidget]）。
                    val widget = FoldWidget(range)
                    val deco = Decoration.replace(
                        ReplaceDecorationSpec(widget = widget)
                    )
                    val builder = RangeSetBuilder<Decoration>()
                    result.between(DocPos.ZERO, tr.newDoc.endPos) { from, to, value ->
                        builder.add(DocPos(from), DocPos(to), value)
                        true
                    }
                    builder.add(range.from, range.to, deco)
                    result = builder.finish()
                }
                val unfold = effect.asType(unfoldEffect)
                if (unfold != null) {
                    val range = unfold.value
                    val builder = RangeSetBuilder<Decoration>()
                    result.between(DocPos.ZERO, tr.newDoc.endPos) { from, to, value ->
                        // Keep all ranges except those overlapping with the unfold range
                        if (DocPos(from) < range.from || DocPos(to) > range.to) {
                            builder.add(DocPos(from), DocPos(to), value)
                        }
                        true
                    }
                    result = builder.finish()
                }
            }
            result
        },
        provide = { field -> decorations.from(field) }
    )
)

/**
 * Core code folding extension that wires the fold state.
 */
fun codeFolding(): Extension = foldState

/**
 * Query the currently folded ranges in a state.
 */
fun foldedRanges(state: EditorState): DecorationSet =
    state.field(foldState, require = false) ?: RangeSet.empty()

/**
 * Find a foldable range at the given line position, using registered
 * fold services and tree-based fold props.
 */
fun foldable(state: EditorState, lineStart: DocPos): FoldRange? {
    // Try fold services first
    for (service in state.facet(foldService)) {
        val range = service(state, lineStart)
        if (range != null) return range
    }

    // Try tree-based folding
    val tree = syntaxTree(state)
    val line = state.doc.lineAt(lineStart)
    val lineEnd = line.to
    if (tree.length < lineEnd.value) return null

    return syntaxFolding(tree, state, lineStart, lineEnd)
}

private fun syntaxFolding(
    tree: Tree,
    state: EditorState,
    lineStart: DocPos,
    lineEnd: DocPos
): FoldRange? {
    var onlyInner = false
    var cur: SyntaxNode? = tree.resolveInner(lineEnd.value, 1)
    while (cur != null) {
        if (cur.to <= lineEnd.value || cur.from > lineEnd.value) {
            cur = cur.parent
            continue
        }
        if (cur.from < lineStart.value) onlyInner = true
        val strategy = cur.type.prop(foldNodeProp)
        if (strategy != null &&
            (cur.to < tree.length - 50 || tree.length == state.doc.length || !onlyInner)
        ) {
            val range = strategy(cur, state)
            if (range != null &&
                range.from <= lineEnd &&
                range.from >= lineStart &&
                range.to > lineEnd
            ) {
                return range
            }
        }
        cur = cur.parent
    }
    return null
}

// --- Fold commands ---

/** Fold the code at the current cursor line. */
val foldCode: (EditorSession) -> Boolean = { view ->
    val state = view.state
    val line = state.doc.lineAt(state.selection.main.head)
    val range = foldable(state, line.from)
    if (range != null) {
        view.dispatch(
            TransactionSpec(
                effects = listOf(foldEffect.of(range))
            )
        )
        true
    } else {
        false
    }
}

/** Unfold the code at the current cursor position. */
val unfoldCode: (EditorSession) -> Boolean = { view ->
    val state = view.state
    val pos = state.selection.main.head
    val folded = foldedRanges(state)
    var found = false
    folded.between(pos, pos) { from, to, _ ->
        view.dispatch(
            TransactionSpec(
                effects = listOf(unfoldEffect.of(FoldRange(DocPos(from), DocPos(to))))
            )
        )
        found = true
        false
    }
    found
}

/** Toggle fold at the current cursor line. */
val toggleFold: (EditorSession) -> Boolean = { view ->
    val state = view.state
    val pos = state.selection.main.head
    val folded = foldedRanges(state)
    var isFolded = false
    folded.between(pos, pos) { _, _, _ ->
        isFolded = true
        false
    }
    if (isFolded) unfoldCode(view) else foldCode(view)
}

/** Fold all foldable ranges in the document. */
val foldAll: (EditorSession) -> Boolean = { view ->
    val state = view.state
    val effects = mutableListOf<StateEffect<*>>()
    for (lineNum in 1..state.doc.lines) {
        val line = state.doc.line(LineNumber(lineNum))
        val range = foldable(state, line.from)
        if (range != null) {
            effects.add(foldEffect.of(range))
        }
    }
    if (effects.isNotEmpty()) {
        view.dispatch(TransactionSpec(effects = effects))
        true
    } else {
        false
    }
}

/** Unfold all folded ranges. */
val unfoldAll: (EditorSession) -> Boolean = { view ->
    val state = view.state
    val folded = foldedRanges(state)
    val effects = mutableListOf<StateEffect<*>>()
    folded.between(DocPos.ZERO, state.doc.endPos) { from, to, _ ->
        effects.add(unfoldEffect.of(FoldRange(DocPos(from), DocPos(to))))
        true
    }
    if (effects.isNotEmpty()) {
        view.dispatch(TransactionSpec(effects = effects))
        true
    } else {
        false
    }
}

/** Default fold key bindings. */
val foldKeymap: List<KeyBinding> = listOf(
    KeyBinding(key = "Ctrl-Shift-[", mac = "Meta-Alt-[", run = foldCode),
    KeyBinding(key = "Ctrl-Shift-]", mac = "Meta-Alt-]", run = unfoldCode),
    KeyBinding(key = "Ctrl-Alt-[", run = foldAll),
    KeyBinding(key = "Ctrl-Alt-]", run = unfoldAll)
)

/**
 * Extension that adds a gutter column with fold indicators.
 *
 * Shows a clickable chevron next to lines that can be folded or unfolded.
 * <本仓库补丁> 整个格子都可点，箭头画在里面（见 [FoldGutterMarker]）。
 */
fun foldGutter(): Extension = extensionListOf(
    codeFolding(),
    gutter(
        GutterConfig(
            type = GutterType.Custom("fold"),
            lineMarker = { view, lineFrom ->
                val state = view.state
                val lineFromPos = DocPos(lineFrom)
                val line = state.doc.lineAt(lineFromPos)
                val folded = foldedRanges(state)
                var foldedRange: FoldRange? = null
                folded.between(lineFromPos, line.to) { from, to, _ ->
                    if (from >= lineFrom && DocPos(from) <= line.to) {
                        foldedRange = FoldRange(DocPos(from), DocPos(to))
                        false
                    } else {
                        true
                    }
                }
                // 折叠中与「可以折叠」两种情形都带**区间**而不只是一个布尔：箭头那一格自己要知道
                // 该收哪一段、该放哪一段（见 `FoldGutterMarker`）。
                foldedRange?.let { FoldGutterMarker(folded = true, range = it) }
                    ?: foldable(state, lineFromPos)?.let { FoldGutterMarker(folded = false, range = it) }
            },
            lineMarkerChange = { update ->
                update.docChanged ||
                    update.transactions.any { tr ->
                        tr.effects.any {
                            it.asType(foldEffect) != null || it.asType(unfoldEffect) != null
                        }
                    }
            }
            // 刻意**不留** `lineMarkerClick`：它会让装订线的每一格都变成可点区域（见
            // `GutterView`——`clickable` 是按配置挂上去的，与这一行有没有箭头无关），于是没有折叠
            // 箭头的行也会冒出一块悬停底色，看着像多出来一个按钮。
            //
            // 折叠 / 展开由箭头自己接（见 [FoldGutterMarker]）：网格里只有有箭头的那几格可点，
            // 「看得出来」与「点得到」正好对上。上游把这一项当兜底，是因为它的箭头只覆盖很小一块；
            // 本仓库的箭头已经铺满整格，兜底反而制造幻觉。
        )
    )
)

private class FoldGutterMarker(val folded: Boolean, val range: FoldRange) : GutterMarker() {
    @Composable
    override fun Content(theme: EditorTheme) {
        // 会话由编辑器在顶层提供（见 `KodeMirror`）。在这里读而不是在点击回调里读：局部值
        // 只有组合期拿得到，而回调跑在事件期。
        val session = LocalEditorSession.current
        // <本仓库补丁> 铺满**整格**：箭头本身只有 8×4dp（见 [FoldChevron]），而这一格是
        // `customGutterWidth` × 行高。上游把 `clickable` 挂在「包住箭头」的那一层上，于是真正
        // 能点的只有箭头那么大一块——差几个像素就点不到。这里让内容撑满格子、箭头居中画在里面，
        // 整格因此都可点，**看得出与点得到一致**：鼠标进了这一列就是手型。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { unfoldOrFold(session) },
            contentAlignment = Alignment.Center
        ) {
            FoldChevron(folded = folded, color = theme.gutterForeground)
        }
    }

    /**
     * 点箭头：折叠中（或**刚折叠、会话已被替换**）就展开它代表的区间，否则折叠它。
     *
     * 这里刻意不复刻 `lineMarkerClick` 那套「按行查一遍当前折叠状态」的判断：那一套拿的是
     * `lineMarker` 求值那一刻的会话，而标记随组合存活——用户在箭头所在的这一行上改一个字，
     * 会话就是新的了，旧标记指的区间早已不存在（拿它去展开等于展开一段已经不折叠的文本，
     * 界面上就是「点一下没反应」）。展开用的区间就在 [range] 里，直接照着它派发。
     */
    private fun unfoldOrFold(session: EditorSession) {
        // 这一格对应的区间此刻还折着吗？折着就展开——与「点一下占位符」同一条路。
        var foldedNow = false
        foldedRanges(session.state).between(range.from, range.to) { _, _, _ ->
            foldedNow = true
            false
        }
        if (foldedNow) {
            unfoldFold(session, range)
        } else if (foldable(session.state, session.state.doc.lineAt(range.from).from) != null) {
            session.dispatch(TransactionSpec(effects = listOf(foldEffect.of(range))))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is FoldGutterMarker && folded == other.folded && range == other.range

    override fun hashCode(): Int = 31 * folded.hashCode() + range.hashCode()
}

/** <本仓库补丁> 折叠箭头：展开时的长边（横向）与短边（纵向）。两条边等长，尖端因此是 90°。 */
private val FoldChevronLong = 8.dp
private val FoldChevronShort = 4.dp
private val FoldChevronStroke = 1.5.dp

/**
 * <本仓库补丁> 折叠箭头：**画**出来的圆头 chevron，展开指下、折叠指右。
 *
 * 上游用的是两个字符（U+2304 展开 / U+203A 折叠）：角度全看字体，实际渲染出来又窄又尖。
 * 改成按几何画之后角度是确定的——长边 8dp、短边 4dp、线宽 1.5dp，尖端 90°，展开与折叠只差
 * 一个方向；尺寸与原生实现的 `FoldChevron` 取同一套，两个引擎看起来才是同一个标记。
 *
 * 用 `Canvas` 而不是换一个更「开」的字符：字符给不了角度，换字体、换字号还会跟着变。
 */
@Composable
private fun FoldChevron(folded: Boolean, color: Color) {
    val width = if (folded) FoldChevronShort else FoldChevronLong
    val height = if (folded) FoldChevronLong else FoldChevronShort
    Canvas(modifier = Modifier.width(width).height(height)) {
        val stroke = FoldChevronStroke.toPx()
        val inset = stroke / 2f
        val left = inset
        val right = size.width - inset
        val top = inset
        val bottom = size.height - inset
        val path = Path().apply {
            if (folded) {
                moveTo(left, top)
                lineTo(right, (top + bottom) / 2f)
                lineTo(left, bottom)
            } else {
                moveTo(left, top)
                lineTo((left + right) / 2f, bottom)
                lineTo(right, top)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
