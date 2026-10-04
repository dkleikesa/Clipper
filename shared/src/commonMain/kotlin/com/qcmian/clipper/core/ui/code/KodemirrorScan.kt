package com.qcmian.clipper.core.ui.code

import androidx.compose.ui.text.SpanStyle
import com.monkopedia.kodemirror.language.FoldRange
import com.monkopedia.kodemirror.language.foldService
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.RangeSet
import com.monkopedia.kodemirror.state.RangeSetBuilder
import com.monkopedia.kodemirror.state.StateField
import com.monkopedia.kodemirror.state.StateFieldSpec
import com.monkopedia.kodemirror.state.extensionListOf
import com.monkopedia.kodemirror.view.Decoration
import com.monkopedia.kodemirror.view.DecorationSet
import com.monkopedia.kodemirror.view.EditorSession
import com.monkopedia.kodemirror.view.MarkDecorationSpec
import com.monkopedia.kodemirror.view.PluginValue
import com.monkopedia.kodemirror.view.ViewPlugin
import com.monkopedia.kodemirror.view.ViewUpdate

/**
 * 一次扫描的产物，**按文档存进 [StateField]**。
 *
 * 高亮与折叠都从这一份数据出：两者都要「整篇扫一遍」的结果，各扫一遍等于每次输入付两次扫描的账
 * （文档一大就是实打实的双倍）。所以扫一次存进状态里：装订线问折叠、装饰器问着色，都只是读它。
 */
class CodeScanResult(
    val tokens: List<CodeToken>,
    /** 行首偏移 → 那一行可折的区间。 */
    private val folds: Map<Int, FoldRange>,
) {
    /**
     * 该行可折的区间，没有则 `null`。
     *
     * 折叠箭头每画**一行**就会问一次（见上游 `foldable()`），所以这里必须是查表：再扫一遍就是
     * 每帧 O(n)，两万行的文档一滚就死。
     */
    fun foldAt(lineStart: Int): FoldRange? = folds[lineStart]
}

/**
 * 把本项目的扫描器（[scanJson] / [scanXml] / [scanPlain]）接进 KodeMirror。
 *
 * 做两件事：
 *  1. 把 `scan` 的结果按文档缓存成一个 [StateField]，只在**文档真的变了**时重扫；
 *  2. 注册成 KodeMirror 的 `foldService`，折叠箭头由此拿到本项目那套括号区间。
 *
 * **刻意不引入 KodeMirror 的语言包**（`lang-json` / `lang-xml`）：那会换掉整套 token 语义与配色，
 * 而本项目已经有自己的扫描器与配色——两套实现并排对比时，这一点尤其重要。
 */
class KodemirrorScan(scan: (String) -> CodeStructure) {

    val field: StateField<CodeScanResult> = StateField.define(
        StateFieldSpec(
            create = { state -> scanInto(state.doc.toString(), scan) },
            update = { value, tr ->
                if (tr.docChanged) scanInto(tr.newDoc.toString(), scan) else value
            },
        )
    )

    /**
     * 折叠区间由本项目的括号配对给出，与原生框用的是**同一批**区间（原生框见
     * `CodeStructure.foldableOnLine`，这里见 [scanInto]）。
     */
    private val foldSupport: Extension = foldService.of { state: EditorState, lineStart: DocPos ->
        state.field(field, require = false)?.foldAt(lineStart.value)
    }

    /** 会放进会话基础扩展里的那一份：[StateField] 与 foldService 必须成对出现。 */
    val extensions: Extension = extensionListOf(field, foldSupport)
}

/** 扫一遍并整理成 [CodeScanResult]。 */
private fun scanInto(text: String, scan: (String) -> CodeStructure): CodeScanResult {
    val structure = scan(text)
    val folds = HashMap<Int, FoldRange>()
    for (pair in structure.brackets) {
        if (!pair.isFoldable) continue
        // 箭头挂在 `foldStart` 所在那一行的行首。同一行出现多个可折区间时取**最先出现**的那个——
        // 与原生框 `foldableOnLine` 里的 `firstOrNull` 同序（`brackets` 两边是同一份列表）。
        folds.putIfAbsent(
            structure.lineStart(structure.lineNumberAt(pair.foldStart)),
            FoldRange(DocPos(pair.foldStart), DocPos(pair.foldEnd)),
        )
    }
    return CodeScanResult(structure.tokens, folds)
}

/**
 * 把 [CodeScanResult.tokens] 画成着色片段。
 *
 * 只做**颜色**：折叠、当前行底纹、括号配对各由 KodeMirror 自己的机制管——这样「点箭头折起来」
 * 「光标旁括号亮起」都是它的原生行为，而不是我们在外面另糊一层。
 *
 * 之所以不走 `syntaxHighlighting`：那个入口要一个「语言」（语法树），而本项目的扫描器只产出着色
 * 区间、不是语法树；直接转成装饰器既省一层适配，也保住了原配色——`CodeKind` → [CodeColors] 用的
 * 是与原生框同一个函数（[colorIn]）。
 */
fun codeHighlight(scan: KodemirrorScan, colors: CodeColors): Extension =
    ViewPlugin.define(
        create = { session -> CodeHighlightPlugin(session, scan, colors) },
        configure = {
            copy(
                decorations = { plugin ->
                    (plugin as? CodeHighlightPlugin)?.decos ?: RangeSet.empty()
                }
            )
        },
    ).asExtension()

private class CodeHighlightPlugin(
    session: EditorSession,
    private val scan: KodemirrorScan,
    private val colors: CodeColors,
) : PluginValue {

    var decos: DecorationSet = build(session)

    override fun update(update: ViewUpdate) {
        // 只在文档变了时重建：这份装饰只依赖文档，光标移动与滚动都不影响它。
        if (update.docChanged) decos = build(update.session)
    }

    private fun build(session: EditorSession): DecorationSet {
        val tokens = session.state.field(scan.field, require = false)?.tokens.orEmpty()
        if (tokens.isEmpty()) return RangeSet.empty()
        val builder = RangeSetBuilder<Decoration>()
        for (token in tokens) {
            // 空片段没有意义，且 `RangeSetBuilder` 对 from == to 的加入没有意义。
            if (token.end <= token.start) continue
            // `RangeSetBuilder` 要求按 from 升序加入：扫描器本来就是顺序产出、且片段互不重叠。
            builder.add(
                DocPos(token.start),
                DocPos(token.end),
                Decoration.mark(MarkDecorationSpec(style = SpanStyle(color = token.kind.colorIn(colors)))),
            )
        }
        return builder.finish()
    }
}
