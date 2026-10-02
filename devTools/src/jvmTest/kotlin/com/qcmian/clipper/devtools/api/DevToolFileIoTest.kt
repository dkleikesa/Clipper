package com.qcmian.clipper.devtools.api

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工具侧的文件读写：`打开文件` / `拖文件进来` / `保存文件` 三个动作最后都落到这两个函数上。
 *
 * 它们同时是「异常见结果、不抛异常」的边界——读不了、写不进都得给调用方一个明确的答复，
 * 否则界面上会表现为「点了没反应」。
 */
class DevToolFileIoTest {

    private fun tempDir() = Files.createTempDirectory("clipper-devtool-io").toFile()

    @Test
    fun `写进去的内容能原样读回来`() {
        val path = tempDir().resolve("config.json").absolutePath
        val text = "{\n  \"名字\": \"剪贴板\",\n  \"emoji\": \"✅\"\n}"

        assertTrue(writeTextFile(path, text))
        assertEquals(text, readTextFileOrNull(path))
    }

    /** 保存是覆盖语义：已经存在的文件要被整份换掉，而不是接在后面。 */
    @Test
    fun `再次保存会覆盖旧内容`() {
        val path = tempDir().resolve("out.json").absolutePath

        writeTextFile(path, """{"old":1}""")
        writeTextFile(path, """{"new":2}""")

        assertEquals("""{"new":2}""", readTextFileOrNull(path))
    }

    @Test
    fun `读不存在的文件返回 null`() {
        val path = tempDir().resolve("nope.json").absolutePath

        assertNull(readTextFileOrNull(path))
    }

    /** 二进制文件不能当文本灌进编辑区：读出来会是一堆乱码，还会被当成非法 JSON 报错。 */
    @Test
    fun `二进制文件读成 null`() {
        val path = tempDir().resolve("blob.bin")
        path.writeBytes(byteArrayOf(0x7B, 0x00, 0x7D, 0x01))

        assertNull(readTextFileOrNull(path.absolutePath))
    }

    /** 目录不是文件：当成文件读只会抛异常，这里要的是安静的 `null`。 */
    @Test
    fun `把目录当文件读返回 null`() {
        assertNull(readTextFileOrNull(tempDir().absolutePath))
    }

    /** 写不进的位置（父目录不存在）返回 `false`，由界面给一句提示。 */
    @Test
    fun `写不进的位置返回 false 而不是抛异常`() {
        val path = tempDir().resolve("no-such-dir/out.json").absolutePath

        assertFalse(writeTextFile(path, "{}"))
    }
}
