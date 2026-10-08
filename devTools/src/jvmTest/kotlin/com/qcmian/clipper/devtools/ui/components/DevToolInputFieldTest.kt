@file:OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)

package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolPasteKey
import com.qcmian.clipper.devtools.api.LocalDevToolPasteKey
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DevToolInputField] 的验收：**两个方向 × 三种载荷（文本 / 文件 / 图片）真的都接得住**。
 *
 * 为什么必须真跑组合：接线（键盘拦截挂在谁的上面、拖放接收器有没有跟着卡片一起搬走）全是 Compose
 * 节点树上的事，纯逻辑测不出来——Base64 的图片粘贴当初就是这么漏掉的：控件编译得过、文本照常输入，
 * 只是那一路上根本没人接。
 *
 * 粘贴有**两条路**，两条都测：
 *  - **主路**是窗口层那条（`DevToolPasteKey`）：内容区里有没有 Compose 焦点节点取决于用户点没点过，
 *    卡片一上来（文本框被替掉）就一个都不剩，挂在这里的键盘拦截收不到 `⌘V`。所以真机上的入口在
 *    窗口层，测试里直接调 [DevToolPasteKey.handle]，与 `ClipperDevToolsWindow` 做的事一致。
 *  - **退路**是输入区自己那层 `onPreviewKeyEvent`（宿主没提供窗口入口时用）：按仓库里的做法注入
 *    **构造好的** `KeyEvent`——桌面端 `compose-ui-test` 的按键注入不带修饰键状态，`⌘V` 只能用
 *    构造的事件送进去（见 `AcceptancePanelInteractionTest` 的类注释）。
 */
class DevToolInputFieldTest {

    @Test
    fun `粘贴图片交给工具`() = runComposeUiTest {
        val png = pngBytes()
        val host = FakeHost(image = png)
        var received: ByteArray? = null
        var origin: DevToolInputOrigin? = null
        render {
            DevToolInputField(
                label = "输入",
                value = "",
                onValueChange = {},
                host = host,
                modifier = Modifier.fillMaxSize(),
                onImage = { bytes, from -> received = bytes; origin = from },
            )
        }

        paste()

        assertContentEquals(png, received, "剪贴板里的图片应当原样交给工具")
        assertEquals(DevToolInputOrigin.Paste, origin)
    }

    @Test
    fun `不收图片的输入框连剪贴板图片都不问`() = runComposeUiTest {
        val host = FakeHost(image = pngBytes())
        render {
            DevToolInputField(
                label = "输入",
                value = "",
                onValueChange = {},
                host = host,
                modifier = Modifier.fillMaxSize(),
            )
        }

        paste()

        // 没有 `onImage` 时控件根本不挂那层键盘拦截（不然会把「粘一段文字」这种别的工具该正常
        // 处理的事吞掉）。问没问剪贴板图片，是这件事在测试里唯一稳定量得到的证据——这次 ⌘V
        // 之后正文变成什么由系统剪贴板决定，不该拿来断言。
        assertEquals(0, host.imageReads)
    }

    @Test
    fun `粘贴文件按文本填进输入框`() = runComposeUiTest {
        val path = tempFile("{\"a\": 1}")
        val host = FakeHost(files = listOf(path))
        val origins = mutableListOf<DevToolInputOrigin>()
        var typed = ""
        render {
            DevToolInputField(
                label = "输入",
                value = typed,
                onValueChange = { typed = it },
                host = host,
                modifier = Modifier.fillMaxSize(),
                onFiles = { paths, origin ->
                    origins += origin
                    paths.joinToString("\n") { Files.readString(java.nio.file.Path.of(it)) }
                },
            )
        }

        paste()

        assertEquals("{\"a\": 1}", typed, "文件内容应当替掉系统默认那手「粘成文件名」")
        assertEquals(listOf(DevToolInputOrigin.Paste), origins)
    }

    @Test
    fun `粘贴文件时图片那一路不被抢`() = runComposeUiTest {
        // 在访达里复制一张图片：粘贴板上既有文件 URL、也有图片表示。要的是**那个文件本身**。
        val path = tempFile("不是图片")
        val host = FakeHost(files = listOf(path), image = pngBytes())
        var images = 0
        var files = 0
        render {
            DevToolInputField(
                label = "输入",
                value = "",
                onValueChange = {},
                host = host,
                modifier = Modifier.fillMaxSize(),
                onFiles = { _, _ -> files++; null },
                onImage = { _, _ -> images++ },
            )
        }

        paste()

        assertEquals(1, files, "剪贴板里有文件时走文件那一路")
        assertEquals(0, images, "文件优先，别把图标当成用户要的图片")
    }

    @Test
    fun `粘贴读不出文本的文件只给提示`() = runComposeUiTest {
        // 二进制文件：`readTextFileOrNull` 认得出 NUL 字节，会把它拒掉。
        val path = tempFile("二进制\u0000内容")
        val host = FakeHost(files = listOf(path))
        var typed = ""
        render {
            DevToolInputField(
                label = "输入",
                value = typed,
                onValueChange = { typed = it },
                host = host,
                modifier = Modifier.fillMaxSize(),
            )
        }

        paste()

        assertEquals("", typed, "读不出文本时不能把文件名贴进正文")
        assertTrue(
            host.statuses.any { it.contains("读不出文本") },
            "至少要说明一句，否则这一次粘贴看上去毫无反应：${host.statuses}",
        )
    }

    @Test
    fun `载入来源卡片后画的是卡片而不是文本框`() = runComposeUiTest {
        // 卡片替掉文本框时，拖放接收器要跟着搬过去（见 `DevToolInputField` 的接线）——否则
        // 「再拖一个文件进来」这条路就断了。拖放事件在离屏测试里造不出来，这里验的是另一面：
        // 卡片模式下画的确实是卡片、文本框确实让位了；真拖放要手动过一遍。
        render {
            DevToolInputField(
                label = "输入",
                value = "",
                onValueChange = {},
                host = FakeHost(),
                modifier = Modifier.fillMaxSize(),
                sourceCard = { cardModifier -> Text("来源卡片", modifier = cardModifier) },
            )
        }

        onNodeWithText("来源卡片").assertIsDisplayed()
        assertTrue(
            onAllNodesWithText("输入").fetchSemanticsNodes().isEmpty(),
            "卡片在时不再画文本框（框名由卡片自己说）",
        )
    }

    @Test
    fun `载入来源卡片后窗口层的粘贴接得住文件`() = runComposeUiTest {
        // 已经载入一张图 / 一个文件之后再粘一个文件进来，是这条路上最自然的下一步动作。真机上
        // 这条按键从窗口层进来（见类注释），这里直接调 `handle`，与 `ClipperDevToolsWindow` 一致。
        val path = tempFile("换一个文件")
        val host = FakeHost(files = listOf(path))
        val pasteKey = DevToolPasteKey()
        var handled: List<String>? = null
        var origin: DevToolInputOrigin? = null
        render(pasteKey) {
            DevToolInputField(
                label = "输入",
                value = "",
                onValueChange = {},
                host = host,
                modifier = Modifier.fillMaxSize(),
                onImage = { _, _ -> },
                onFiles = { paths, from ->
                    handled = paths
                    origin = from
                    // 与 Base64 同一种写法：自己安置，返回空串表示输入框不必再动别的。
                    ""
                },
                sourceCard = { cardModifier -> Text("来源卡片", modifier = cardModifier) },
            )
        }
        waitForIdle()

        assertTrue(pasteKey.handle(), "卡片显示着的时候，这次粘贴得由输入区接过去")
        assertEquals(listOf(path), handled, "卡片上粘文件要接得住")
        assertEquals(DevToolInputOrigin.Paste, origin)
    }

    @Test
    fun `载入来源卡片后窗口层的粘贴接得住图片`() = runComposeUiTest {
        val png = pngBytes()
        val host = FakeHost(image = png)
        val pasteKey = DevToolPasteKey()
        var received: ByteArray? = null
        render(pasteKey) {
            DevToolInputField(
                label = "输入",
                value = "",
                onValueChange = {},
                host = host,
                modifier = Modifier.fillMaxSize(),
                onImage = { bytes, _ -> received = bytes },
                sourceCard = { cardModifier -> Text("来源卡片", modifier = cardModifier) },
            )
        }
        waitForIdle()

        assertTrue(pasteKey.handle())
        assertContentEquals(png, received)
    }

    @Test
    fun `窗口层的粘贴在文本框那一侧也接得住图片`() = runComposeUiTest {
        // 文本框那一侧同样受益：不必先点一下编辑区才粘得进图片。
        val png = pngBytes()
        val host = FakeHost(image = png)
        val pasteKey = DevToolPasteKey()
        var received: ByteArray? = null
        render(pasteKey) {
            DevToolInputField(
                label = "输入",
                value = "",
                onValueChange = {},
                host = host,
                modifier = Modifier.fillMaxSize(),
                onImage = { bytes, _ -> received = bytes },
            )
        }
        waitForIdle()

        assertTrue(pasteKey.handle())
        assertContentEquals(png, received)
    }

    @Test
    fun `窗口层的粘贴不抢文本框那一侧的文件`() = runComposeUiTest {
        // 文本框那一侧的文件要留给代码框自己的 `filePaste` 钩子（内容得插在**光标处**）。
        // 窗口层要是顺手接过去，插在哪儿就没了依据。
        val path = tempFile("{\"a\": 1}")
        val host = FakeHost(files = listOf(path))
        val pasteKey = DevToolPasteKey()
        var files = 0
        render(pasteKey) {
            DevToolInputField(
                label = "输入",
                value = "",
                onValueChange = {},
                host = host,
                modifier = Modifier.fillMaxSize(),
                onFiles = { _, _ -> files++; "文件内容" },
            )
        }
        waitForIdle()

        assertFalse(pasteKey.handle(), "放行，让事件走到代码框那个钩子上")
        assertEquals(0, files, "说「不接」就不该顺手调一次 onFiles")
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /**
     * 画工具真正会画的那份内容。
     *
     * [pasteKey] 就是窗口层那个入口（见类注释）：给了它就等于「宿主提供了窗口层的粘贴接管」，
     * 不给时走的才是输入区自己那层退路。
     */
    private fun ComposeUiTest.render(
        pasteKey: DevToolPasteKey? = null,
        content: @Composable () -> Unit,
    ) {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDevToolPasteKey provides pasteKey) { content() }
            }
        }
    }

    /**
     * 把焦点送进输入框，再按下 `⌘V`。
     *
     * 焦点是必须的：`onPreviewKeyEvent` 只在**焦点路径**上收得到事件。KodeMirror 真正收键盘的是
     * 它那个隐藏输入框（上游自己的 testTag），`performTextInput` 叫得醒它。
     */
    private fun ComposeUiTest.paste() {
        onNodeWithTag("KodeMirror_input").performTextInput("")
        waitForIdle()
        pressPaste()
    }

    /** 按下 `⌘V`，走输入区自己那层退路（装配好的事件，见类注释）。 */
    private fun ComposeUiTest.pressPaste() {
        onRoot().performKeyPress(KeyEvent(Key.V, KeyEventType.KeyDown, isMetaPressed = true))
        waitForIdle()
    }

    private fun tempFile(content: String): String =
        Files.createTempFile("clipper-input-field", ".txt").also {
            it.toFile().deleteOnExit()
            Files.writeString(it, content)
        }.absolutePathString()

    /** 一张真 PNG（1×1，透明）：图片那两路要的是能认出来的图片字节，不是随便一串字节。 */
    private fun pngBytes(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmM" +
            "IQAAAABJRU5ErkJggg=="
    )

    private class FakeHost(
        private val files: List<String> = emptyList(),
        private val image: ByteArray? = null,
    ) : DevToolHost {
        val statuses = mutableListOf<String>()

        /** 问过几次「剪贴板里有没有图片」。 */
        var imageReads = 0

        override fun copyToClipboard(text: String) = Unit

        override fun showStatus(message: String) {
            statuses += message
        }

        override fun reportStatus(text: String?) = Unit

        override fun pickFileToOpen(): String? = null

        override fun pickFileToSave(suggestedName: String): String? = null

        override fun droppedFilePaths(event: DragAndDropEvent): List<String> = emptyList()

        override fun clipboardFilePaths(): List<String> = files

        override fun clipboardImage(): ByteArray? {
            imageReads++
            return image
        }
    }
}
