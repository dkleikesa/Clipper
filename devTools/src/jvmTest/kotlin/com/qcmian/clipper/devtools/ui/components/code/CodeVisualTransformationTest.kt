package com.qcmian.clipper.devtools.ui.components.code

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
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
        foldPlaceholder = Color(0xFF000006),
        foldPlaceholderBackground = Color(0x11000006),
        gutterDivider = Color(0xFF000008),
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
}
