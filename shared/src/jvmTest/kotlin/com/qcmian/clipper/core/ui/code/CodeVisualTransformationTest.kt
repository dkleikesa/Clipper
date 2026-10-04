package com.qcmian.clipper.core.ui.code

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 显示变换：高亮只加样式、折叠只改显示，以及折叠后双向偏移映射仍然单调。
 *
 * 折叠一旦映射错了，表现是「光标画到别处」「点一下落到别的行」这类很难复现的现象，所以
 * 直接对映射本身下断言。
 */
class CodeVisualTransformationTest {

    private val colors = CodeColors(
        editorBackground = Color(0xFF000000),
        key = Color(0xFF000001),
        string = Color(0xFF000002),
        number = Color(0xFF000003),
        constant = Color(0xFF000004),
        punctuation = Color(0xFF000005),
        comment = Color(0xFF00000C),
        foldPlaceholder = Color(0xFF000006),
        foldPlaceholderBackground = Color(0x11000006),
        gutterDivider = Color(0xFF000008),
        // 当前行 / 括号配对走的是绘制层，不参与这个变换；这里只需凑齐构造参数。
        currentLineBackground = Color(0x1100000A),
        bracketBackground = Color(0x1100000B),
    )

    private fun transform(text: String, folded: List<BracketPair> = emptyList()) =
        CodeVisualTransformation(scanJson(text).tokens, folded, colors).filter(AnnotatedString(text))

    @Test
    fun `highlighting only adds styles and leaves the text alone`() {
        val text = """{"a": 1}"""
        val result = transform(text)
        assertEquals(text, result.text.text)
        assertEquals(text.length, result.offsetMapping.originalToTransformed(text.length))
    }

    @Test
    fun `each token kind is coloured over its own range`() {
        val text = """{"a": "b", "c": true}"""
        val styles = transform(text).text.spanStyles
        fun colorAt(offset: Int): Color? =
            styles.firstOrNull { offset >= it.start && offset < it.end }?.item?.color

        assertEquals(colors.punctuation, colorAt(0)) // {
        assertEquals(colors.key, colorAt(1)) // "a"
        assertEquals(colors.punctuation, colorAt(4)) // :
        assertEquals(colors.string, colorAt(6)) // "b"
        assertEquals(colors.constant, colorAt(16)) // true
        // 键的着色范围正好是它自己那一段（含两个引号）。
        assertTrue(styles.any { it.item.color == colors.key && it.start == 1 && it.end == 4 })
    }

    @Test
    fun `a folded range collapses to a placeholder and the mapping stays monotonic`() {
        val text = "{\n  \"a\": 1\n}"
        val pair = scanJson(text).brackets.single()
        val result = transform(text, listOf(pair))

        assertEquals("{…}", result.text.text)
        assertTrue(
            result.text.spanStyles.any { it.item.background == colors.foldPlaceholderBackground }
        )

        val mapping = result.offsetMapping
        // 被折叠区间内的偏移全部落到占位符上（光标不会画到不存在的字符上）。
        assertEquals(1, mapping.originalToTransformed(pair.foldStart))
        assertEquals(1, mapping.originalToTransformed(pair.foldEnd - 1))
        // 闭括号留在占位符之后：显示成 `{…}`。
        assertEquals(2, mapping.originalToTransformed(pair.foldEnd))
        assertEquals(pair.foldStart, mapping.transformedToOriginal(1))
        assertEquals(result.text.text.length, mapping.originalToTransformed(text.length))

        var previous = -1
        for (offset in 0..text.length) {
            val mapped = mapping.originalToTransformed(offset)
            assertTrue(mapped >= previous, "偏移 $offset 破坏了 originalToTransformed 的单调性")
            previous = mapped
        }
        previous = -1
        for (offset in 0..result.text.text.length) {
            val mapped = mapping.transformedToOriginal(offset)
            assertTrue(mapped >= previous, "偏移 $offset 破坏了 transformedToOriginal 的单调性")
            previous = mapped
        }
    }

    @Test
    fun `nested folds collapse to the outermost one`() {
        val text = """{"a": {"b": 1}}"""
        val pairs = scanJson(text).brackets
        val result = transform(text, pairs.sortedByDescending { it.close - it.open })
        assertEquals("{…}", result.text.text)
    }

    @Test
    fun `unfolding gives the original text back`() {
        val text = "{\n  \"a\": 1\n}"
        val result = transform(text, emptyList())
        assertEquals(text, result.text.text)
    }

    // ------------------------------------------------------------------ 制表符

    /**
     * 这条钉住用户报的现象：选「制表符」与选「1 个空格」，在屏幕上必须看得出不同。
     *
     * 数据本来就不同（`JsonFormatTest` 断言过一个产出 `\t`、一个产出空格），坏的是显示——
     * Compose 自己的排版把 `\t` 画成**正好一个空格宽**（实测 8px == 1 个空格），于是两者一模一样。
     */
    @Test
    fun `制表符与一个空格在屏幕上不再一样`() {
        val tabbed = transform("{\n\t\"a\": 1\n}").text.text
        val spaced = transform("{\n \"a\": 1\n}").text.text

        assertNotEquals(spaced, tabbed)
        assertEquals(TabDisplayWidth, tabbed.lines()[1].takeWhile { it == ' ' }.length)
        assertEquals(1, spaced.lines()[1].takeWhile { it == ' ' }.length)
    }

    /** 显示里只该有宽度上的差别：不该多出文本里没有的字符（例如标记箭头）。 */
    @Test
    fun `展开只补空格不加别的字符`() {
        assertEquals(" ".repeat(TabDisplayWidth) + "X", transform("\tX").text.text)
    }

    @Test
    fun `制表符在屏幕上占满一个缩进档`() {
        val line = transform("{\n\t\"a\": 1\n}").text.text.lines()[1]

        assertEquals(" ".repeat(TabDisplayWidth) + "\"a\": 1", line)
    }

    /**
     * 展开成多列之后，偏移映射必须跟着改：否则光标会画到错的地方、点一下落错位。
     * 关键性质是「展开出来的每一列都指回那一个制表符」。
     */
    @Test
    fun `制表符展开后每一列都指回它自己且映射仍然单调`() {
        val text = "\tX"
        val result = transform(text)
        val mapping = result.offsetMapping

        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(TabDisplayWidth, mapping.originalToTransformed(1), "制表符之后的原文偏移要跨过整档")
        assertEquals(TabDisplayWidth + 1, mapping.originalToTransformed(2))

        for (column in 0 until TabDisplayWidth) {
            assertEquals(0, mapping.transformedToOriginal(column), "第 $column 列属于那个制表符")
        }
        assertEquals(1, mapping.transformedToOriginal(TabDisplayWidth))

        var previous = -1
        for (offset in 0..text.length) {
            val mapped = mapping.originalToTransformed(offset)
            assertTrue(mapped >= previous, "偏移 $offset 破坏了 originalToTransformed 的单调性")
            previous = mapped
        }
        previous = -1
        for (offset in 0..result.text.text.length) {
            val mapped = mapping.transformedToOriginal(offset)
            assertTrue(mapped >= previous, "偏移 $offset 破坏了 transformedToOriginal 的单调性")
            previous = mapped
        }
    }

    /** 制表符与折叠同时出现时，折叠照样把它整段盖掉——两条规则各管各的。 */
    @Test
    fun `折叠区间里的制表符跟着一起被盖住`() {
        val text = "{\n\t\"a\": 1\n}"
        val pair = scanJson(text).brackets.single()
        val result = transform(text, listOf(pair))

        assertEquals("{…}", result.text.text)
    }
}
