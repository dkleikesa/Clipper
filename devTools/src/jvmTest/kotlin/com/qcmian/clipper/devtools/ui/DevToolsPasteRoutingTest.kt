@file:OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)

package com.qcmian.clipper.devtools.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.devtools.api.DevToolPasteKey
import com.qcmian.clipper.devtools.api.isPasteShortcut
import com.qcmian.clipper.devtools.registry.DevToolsRegistry
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 窗口层的粘贴按键有没有真的落到**当前工具的输入区**上（面板这一段接线）。
 *
 * 真机上的入口在窗口层——`ClipperDevToolsWindow` 的 `onPreviewKeyEvent` 由 AWT 的按键监听驱动，
 * 排在内容区派发之前、也不看 Compose 焦点；内容区那一层是收不到 `⌘V` 的（工具载入文件 / 图片
 * 之后卡片替掉了文本框，一个焦点节点都不剩）。那条链路的每一段都得接通，这两个用例盯的是中间
 * 那一段：面板把窗口给的 [DevToolPasteKey] 交给工具，工具里的输入区再把处理函数登记上去。
 *
 * 断言落在**用户看得见的东西**上：真 `DevToolsRegistry` + 真 Base64 工具，粘贴之后来源卡片上
 * 显示的是哪个文件名 / 是不是「剪贴板图片」。宿主的两个回调（`onClipboardFilePaths` /
 * `onClipboardImage`）就是测试手里的剪贴板。
 */
class DevToolsPasteRoutingTest {

    @Test
    fun `文本框那一侧窗口层的粘贴接得住图片`() = runComposeUiTest {
        val pasteKey = DevToolPasteKey()
        var clipboardImageReads = 0
        setContent {
            MaterialTheme {
                DevToolsPanel(
                    registry = DevToolsRegistry.builtIn(),
                    // 一段 Base64：探测器据此把 Base64 工具推荐出来（只有它吃 base64）。
                    item = ClipItem(id = "id0", text = HELLO_BASE64),
                    onClose = {},
                    onCopyToClipboard = {},
                    pasteKey = pasteKey,
                    onClipboardImage = {
                        clipboardImageReads++
                        PNG
                    },
                )
            }
        }

        // 类型探测在后台线程上跑，回来之前面板不画任何工具，也就没人登记。这一段 Base64 会被
        // 认成 Base64，工具因此落在**解码**方向，输入框的框名是「输入 · Base64」。
        awaitText(INPUT_LABEL)

        // 剪贴板里没有文件、只有图片：输入区应当把这次粘贴接过去。
        assertTrue(pasteKey.handle(), "输入区登记过了，这次粘贴该由它接")
        assertEquals(1, clipboardImageReads, "接了就一定问过宿主要图片")

        // 而且图片真的进了工具：Base64 会切到编码方向，并用来源卡片替掉文本框。
        awaitText(CLIPBOARD_IMAGE)
    }

    @Test
    fun `文件卡片上窗口层的粘贴既能换图片也能换文件`() = runComposeUiTest {
        // 打开时带的就是一个文件：Base64 工具直接落在「编码 + 文件卡片」上，正是用户说的
        // 「文件状态」。
        val opened = tempFile(HELLO_BASE64)
        val replacement = tempFile("第二个文件")
        val pasteKey = DevToolPasteKey()
        var clipboardFiles = emptyList<String>()
        var clipboardImageReads = 0
        setContent {
            MaterialTheme {
                DevToolsPanel(
                    registry = DevToolsRegistry.builtIn(),
                    item = ClipItem(id = "id0", files = listOf(opened)),
                    onClose = {},
                    onCopyToClipboard = {},
                    pasteKey = pasteKey,
                    onClipboardFilePaths = { clipboardFiles },
                    onClipboardImage = {
                        clipboardImageReads++
                        PNG
                    },
                )
            }
        }
        awaitText(File(opened).name)

        // 粘一张图片：卡片换成「剪贴板图片」。
        assertTrue(pasteKey.handle(), "卡片上粘图片要接得住")
        awaitText(CLIPBOARD_IMAGE)
        assertEquals(1, clipboardImageReads)

        // 再粘一个文件：卡片换回文件名。这一步同时钉住**登记的内容要跟着状态走**——停在
        // 「文本框那一侧」的旧判断上时，这里会因为「文件放行」而什么都不发生。
        clipboardFiles = listOf(replacement)
        assertTrue(pasteKey.handle(), "卡片上粘文件也要接得住")
        awaitText(File(replacement).name)
    }

    @Test
    fun `粘贴的判据认得 meta 与 control`() {
        // 窗口层那一行就是靠它把 `⌘V` / `⌃V` 挑出来的（`ClipperDevToolsWindow` 里那三个键与
        // 粘贴共用同一个分支）。判据写松了会把普通 `V` 也吞掉，写紧了则整条路不通。
        assertTrue(KeyEvent(Key.V, KeyEventType.KeyDown, isMetaPressed = true).isPasteShortcut())
        assertTrue(KeyEvent(Key.V, KeyEventType.KeyDown, isCtrlPressed = true).isPasteShortcut())
        assertFalse(KeyEvent(Key.V, KeyEventType.KeyDown).isPasteShortcut(), "裸 V 是打字")
        assertFalse(
            KeyEvent(Key.V, KeyEventType.KeyUp, isMetaPressed = true).isPasteShortcut(),
            "抬起那一下不该再触发一次",
        )
        assertFalse(KeyEvent(Key.C, KeyEventType.KeyDown, isMetaPressed = true).isPasteShortcut())
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /**
     * 等到某个文案出现。
     *
     * 超时给得比默认的 1 秒宽：类型探测与假文件的读盘都在后台线程上跑，慢一点的 CI 上默认值不够。
     */
    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tempFile(content: String): String =
        Files.createTempFile("clipper-paste", ".txt").also {
            it.toFile().deleteOnExit()
            Files.writeString(it, content)
        }.toString()

    private companion object {
        /** Base64 工具输入框的框名；认成 Base64 之后它落在解码方向。 */
        const val INPUT_LABEL = "输入 · Base64"

        /** 粘贴进来的图片没有文件名，卡片上就叫这个。 */
        const val CLIPBOARD_IMAGE = "剪贴板图片"

        /** 一段能被认成 Base64 的文本（`Hello, World!`）。 */
        const val HELLO_BASE64 = "SGVsbG8sIFdvcmxkIQ=="

        /** 一张真 PNG（1×1，透明）：卡片会拿它解缩略图，别给一段假字节。 */
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmM" +
                "IQAAAABJRU5ErkJggg=="
        )
    }
}
