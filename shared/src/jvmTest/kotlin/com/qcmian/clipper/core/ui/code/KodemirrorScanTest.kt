package com.qcmian.clipper.core.ui.code

import com.monkopedia.kodemirror.state.ChangeSpec
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.EditorStateConfig
import com.monkopedia.kodemirror.state.TransactionSpec
import com.monkopedia.kodemirror.state.asDoc
import com.monkopedia.kodemirror.state.asInsert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [KodemirrorScan] 是「本项目扫描器 → KodeMirror」那座桥，配色与折叠区间都来自本项目的
 * `CodeStructure`，所以它必须**逐项**与那份结构对得上：
 *
 *  - 折叠区间挂在**开括号那一行**的行首（与 `CodeStructure.foldableOnLine` 同一条规则）；
 *  - 空块（`{}`、`<a></a>`）不算可折（`BracketPair.isFoldable`）；
 *  - 文档一变就重算——这条最关键，因为 KodeMirror 的折叠箭头是**每帧**去问 `foldService` 的，
 *    缓存不刷新就会一直按旧文档折。
 *
 * 纯逻辑，不需要跑组合：桥接错一点，界面上的表现是「折叠箭头不见了」，肉眼很难定位。
 */
class KodemirrorScanTest {

    @Test
    fun `JSON 的折叠区间挂在开括号那一行`() {
        val scan = KodemirrorScan(::scanJson)
        val text = "{\n  \"a\": 1\n}"
        val folds = stateOf(text, scan).field(scan.field)!!

        val fold = folds.foldAt(0)
        assertNotNull(fold, "第一行是 `{`，应当可折")
        assertEquals(1, fold.from.value, "可折区间从开括号之后开始")
        assertEquals(text.lastIndexOf('}'), fold.to.value, "到闭括号之前结束（闭括号留在外面）")

        assertNull(folds.foldAt(text.indexOf('}')), "最后一行没有开括号，挂不上箭头")
    }

    @Test
    fun `每一层各拿到自己那一行的区间`() {
        val scan = KodemirrorScan(::scanJson)
        val folds = stateOf("{\n  \"a\": {\n    \"b\": 1\n  }\n}", scan).field(scan.field)!!

        assertNotNull(folds.foldAt(0), "外层 `{`")
        assertNotNull(folds.foldAt(2), "内层 `{` 在第二行")
    }

    @Test
    fun `空块不算可折`() {
        val scan = KodemirrorScan(::scanJson)
        val folds = stateOf("{}", scan).field(scan.field)!!

        assertNull(folds.foldAt(0), "`{}` 中间没有内容，不显示折叠箭头")
    }

    @Test
    fun `XML 的元素内容也能折`() {
        val scan = KodemirrorScan(::scanXml)
        val text = "<a>\n  <b/>\n</a>"
        val folds = stateOf(text, scan).field(scan.field)!!

        val fold = folds.foldAt(0)
        assertNotNull(fold, "`<a>` 的内容可折")
        assertEquals(3, fold.from.value, "自起始标签的 `>` 之后开始")
        assertEquals(text.indexOf("</a>"), fold.to.value, "到结束标签的 `<` 之前结束")
    }

    @Test
    fun `纯文本没有可折区间也没有着色片段`() {
        val scan = KodemirrorScan(::scanPlain)
        val result = stateOf("1 + 2 * 3", scan).field(scan.field)!!

        assertNull(result.foldAt(0))
        assertEquals(emptyList(), result.tokens)
    }

    @Test
    fun `文档变了以后折叠区间跟着重算`() {
        val scan = KodemirrorScan(::scanJson)
        val before = stateOf("{}", scan)
        assertNull(before.field(scan.field)!!.foldAt(0), "空对象本来没有可折内容")

        // 往两个括号之间插一个字段：`{}` → `{"a": 1}`。
        val after = before.update(
            TransactionSpec(
                changes = ChangeSpec.Single(from = DocPos(1), insert = "\"a\": 1".asInsert())
            )
        ).state

        assertNotNull(
            after.field(scan.field)!!.foldAt(0),
            "缓存必须跟着文档刷新，否则箭头会一直按旧文档的结论画（这里是**没有**箭头）",
        )
    }

    @Test
    fun `着色片段就是扫描器给出的那一串`() {
        val scan = KodemirrorScan(::scanJson)
        val tokens = stateOf("{\"a\": 1}", scan).field(scan.field)!!.tokens

        assertEquals(
            listOf(
                CodeKind.Punctuation,
                CodeKind.Key,
                CodeKind.Punctuation,
                CodeKind.Number,
                CodeKind.Punctuation,
            ),
            tokens.map { it.kind },
        )
    }

    private fun stateOf(text: String, scan: KodemirrorScan): EditorState =
        EditorState.create(EditorStateConfig(doc = text.asDoc(), extensions = scan.extensions))
}
