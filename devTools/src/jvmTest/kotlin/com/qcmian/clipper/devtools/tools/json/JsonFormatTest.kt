package com.qcmian.clipper.devtools.tools.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 缩进：美化按给定的 [JsonIndent] 排，压缩与之无关。 */
class JsonFormatTest {

    private val source = """{"a":[1,2]}"""

    private fun pretty(indent: JsonIndent): String = JsonFormat.format(source, indent).getOrThrow()

    @Test
    fun `spaces indent is exactly that many spaces per level`() {
        assertEquals(
            "{\n  \"a\": [\n    1,\n    2\n  ]\n}",
            pretty(JsonIndent.Spaces(2)),
        )
    }

    @Test
    fun `one tab is one level`() {
        assertEquals(
            "{\n\t\"a\": [\n\t\t1,\n\t\t2\n\t]\n}",
            pretty(JsonIndent.Tab),
        )
    }

    @Test
    fun `omitting the indent uses the default`() {
        assertEquals(pretty(JsonIndent.Spaces(JsonFormat.DefaultIndentSpaces)), JsonFormat.format(source).getOrThrow())
    }

    /**
     * 「1..8 个空格 + 制表符都能选」是这个工具对用户的承诺，但它在界面上只是一列菜单项，
     * 漏掉一档、重复一档或顺序乱了都很难靠眼看发现，所以在取值域这里钉死。
     */
    @Test
    fun `indent options are one to eight spaces then tab`() {
        val spaces = JsonFormat.IndentOptions.filterIsInstance<JsonIndent.Spaces>().map { it.count }

        assertEquals((1..8).toList(), spaces)
        assertEquals(JsonIndent.Tab, JsonFormat.IndentOptions.last())
        assertEquals(JsonFormat.IndentOptions.size, JsonFormat.IndentOptions.distinct().size)
        assertTrue(JsonFormat.DefaultIndent in JsonFormat.IndentOptions)
    }

    // ------------------------------------------------------------------ 键排序

    @Test
    fun `without sorting the input order is kept`() {
        assertEquals(
            "{\n  \"b\": 1,\n  \"a\": 2\n}",
            JsonFormat.format("""{"b":1,"a":2}""", JsonIndent.Spaces(2)).getOrThrow(),
        )
    }

    @Test
    fun `sorting reorders keys and keeps values with them`() {
        assertEquals(
            "{\n  \"a\": 2,\n  \"b\": 1\n}",
            JsonFormat.format("""{"b":1,"a":2}""", JsonIndent.Spaces(2), sortKeys = true).getOrThrow(),
        )
    }

    @Test
    fun `sorting goes all the way down nested objects`() {
        assertEquals(
            "{\n  \"outer\": {\n    \"inner\": {\n      \"a\": 1,\n      \"z\": 2\n    }\n  }\n}",
            JsonFormat.format(
                """{"outer":{"inner":{"z":2,"a":1}}}""",
                JsonIndent.Spaces(2),
                sortKeys = true,
            ).getOrThrow(),
        )
    }

    @Test
    fun `objects inside arrays are sorted too`() {
        assertEquals(
            "{\n  \"list\": [\n    {\n      \"a\": 1,\n      \"b\": 2\n    },\n    {\n      \"a\": 3,\n      \"b\": 4\n    }\n  ]\n}",
            JsonFormat.format(
                """{"list":[{"b":2,"a":1},{"b":4,"a":3}]}""",
                JsonIndent.Spaces(2),
                sortKeys = true,
            ).getOrThrow(),
        )
    }

    /**
     * 这条是「为什么必须在解析后的树上排、不能按文本行排」的凭据：字符串值里也会出现 `"键":` 的
     * 样子，按文本去排会把它当成真键一起挪走，而在合法 JSON 里它只是个字符串。
     */
    @Test
    fun `json looking text inside a string value is left alone`() {
        assertEquals(
            "{\n  \"a\": 1,\n  \"z\": \"{\\\"b\\\":1,\\\"a\\\":2}\"\n}",
            JsonFormat.format("""{"z":"{\"b\":1,\"a\":2}","a":1}""", JsonIndent.Spaces(2), sortKeys = true)
                .getOrThrow(),
        )
    }

    @Test
    fun `sorting also applies when minifying`() {
        assertEquals(
            """{"a":2,"b":1}""",
            JsonFormat.minify("""{"b":1,"a":2}""", sortKeys = true).getOrThrow(),
        )
    }

    /** 码位序：大写排在小写之前。与 jq `--sort-keys`、Python `sort_keys=True` 一致。 */
    @Test
    fun `order is by code point so uppercase comes first`() {
        assertEquals(
            "{\n  \"Name\": 1,\n  \"apple\": 2,\n  \"zebra\": 3\n}",
            JsonFormat.format("""{"zebra":3,"apple":2,"Name":1}""", JsonIndent.Spaces(2), sortKeys = true)
                .getOrThrow(),
        )
    }

    @Test
    fun `sorting does not make invalid input parseable`() {
        assertTrue(JsonFormat.format("""{"a":}""", sortKeys = true).isFailure)
    }

    /** 根节点不是对象时没有键可排：数组与标量原样输出，不能崩。 */
    @Test
    fun `scalar and array roots survive sorting`() {
        assertEquals("[3,1,2]", JsonFormat.minify("[3,1,2]", sortKeys = true).getOrThrow())
        assertEquals("42", JsonFormat.minify("42", sortKeys = true).getOrThrow())
        assertEquals("null", JsonFormat.minify("null", sortKeys = true).getOrThrow())
    }

    /**
     * 一条用例压住四种走法：对象套数组、数组里的对象、数组套数组、以及字符串值里长得像 JSON 的文本。
     *
     * 顶层还顺带钉住大小写：`Alpha` 排在 `beta` 前面，因为用的是码位序。
     */
    @Test
    fun `sorting walks objects arrays and arrays of arrays`() {
        val source =
            """{"zoo":[{"zebra":1,"ant":2},[{"dog":3,"cat":4}]],"Alpha":{"y":5,"x":{"n":6,"m":7}},"note":"{\"z\":1,\"a\":2}","beta":8}"""

        val expected = """
            {
              "Alpha": {
                "x": {
                  "m": 7,
                  "n": 6
                },
                "y": 5
              },
              "beta": 8,
              "note": "{\"z\":1,\"a\":2}",
              "zoo": [
                {
                  "ant": 2,
                  "zebra": 1
                },
                [
                  {
                    "cat": 4,
                    "dog": 3
                  }
                ]
              ]
            }
        """.trimIndent()

        assertEquals(
            expected,
            JsonFormat.format(source, JsonIndent.Spaces(2), sortKeys = true).getOrThrow(),
        )
    }
}
