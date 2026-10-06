package com.qcmian.clipper.devtools.tools.regex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 速查表的内容：结构、与开关的对应关系，以及两条口径（写法得是各平台都认的、接进去的形状得对）。
 *
 * 为什么值得单独测：这张表是**写给用户照着抄的**——抄错了多半不会再回来查第二遍，而它自己只是一串
 * 常量，写错一个字不会有任何东西报错。这里把它与实现、与 `RegexRewrite` 的取舍绑在一起。
 */
class RegexSyntaxTest {

    @Test
    fun `开关那一组就是工具栏上那几枚`() {
        // 这一组是拿 `RegexFlag` 生成的，所以这里守的是「别把某枚开关漏在表外」——漏了就查不到它的
        // 意思，而那正是这张表存在的理由。
        val switches = RegexSyntax.groups.single { it.target == null }

        assertEquals(RegexFlag.entries.map { it.letter.toString() }, switches.rows.map { it.spelling })
        assertEquals(RegexFlag.entries.map { it.title }, switches.rows.map { it.meaning })
        assertEquals(RegexFlag.entries.map { it.effect }, switches.rows.map { it.example })
    }

    @Test
    fun `每组都有标题有去处，每行都填满了`() {
        assertTrue(RegexSyntax.groups.size >= 3, "开关、常用写法、替换模板是三组")
        RegexSyntax.groups.forEach { group ->
            assertTrue(group.title.isNotBlank())
            assertTrue(group.rows.isNotEmpty(), group.title)
            group.rows.forEach { row ->
                assertTrue(row.meaning.isNotBlank(), group.title)
                assertTrue(row.spelling.isNotBlank(), group.title)
                assertTrue(row.example.isNotBlank(), group.title)
            }
        }
    }

    @Test
    fun `可点的组在标题里就说清接进哪个框`() {
        RegexSyntax.groups.forEach { group ->
            val target = group.target ?: return@forEach
            // 一张表管两个输入框（不像时间戳那样一页只管一个），去处必须写在看得见的地方。
            assertTrue(
                group.title.contains(target.title),
                "「${group.title}」里没写清落到「${target.title}」",
            )
        }
    }

    @Test
    fun `表里不放只有部分平台认的写法`() {
        // 与 `RegexRewrite` 同一条口径：写进工具的写法得是各平台都成立的。下面这些是 JVM / Native
        // 有、JS 没有的（`\Q…\E` 在 JS 上连转义都不合法）。
        val spellings = RegexSyntax.groups.flatMap { it.rows }.map { it.spelling }

        listOf("\\Q", "\\p{", "(?>", "++", "*+", "?+", "(?#").forEach { exclusive ->
            assertTrue(spellings.none { exclusive in it }, "「$exclusive」不是所有平台都认")
        }
    }

    @Test
    fun `接进去的写法形状是对的`() {
        // `*`、`{2,4}`、`\1` 这些是后缀或引用，单独一个本来就编译不过——它们要接在别的东西后面才有
        // 意义。所以拿一段最小的前缀来验形状（前缀里那个捕获组让 `\1` 也引用得到）：接进去之后用户
        // 可能还要改内容，但至少不该一接进去就是个语法错的串。
        val prefix = """(\w)"""

        RegexSyntax.groups.filter { it.target == RegexSyntaxField.Pattern }.forEach { group ->
            group.rows.forEach { row ->
                val failure = RegexFormat
                    .scan(RegexPattern(prefix + row.spelling), "x")
                    .exceptionOrNull()
                assertNull(failure, "「${row.spelling}」接在模式后面不该是坏的：${failure?.message}")
            }
        }
    }

    @Test
    fun `模板写法能被模板校验认下来`() {
        // 模板那一组的写法得直接能用：`$1` 引用得到第 1 个组、`${name}` 引用得到那个命名组。
        val pattern = RegexPattern("""(?<name>\w)""")

        RegexSyntax.groups.filter { it.target == RegexSyntaxField.Template }.forEach { group ->
            group.rows.forEach { row ->
                assertNull(
                    RegexFormat.templateProblem(pattern, row.spelling),
                    "「${row.spelling}」该是一段能用的模板",
                )
            }
        }
    }
}
