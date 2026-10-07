@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.devtools.tools.apksign

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolPasteKey
import com.qcmian.clipper.devtools.api.LocalDevToolPasteKey
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **粘贴文件**这一条路：真机上按 `⌘V`，走的是窗口层那条（见 `DevToolPasteKey`）。
 *
 * 为什么专门盯着它：本工具的输入区是**卡片**而不是文本框（这两页都没有可敲的正文），卡片一上来，
 * 内容区里就一个 Compose 焦点节点都不剩，输入区自己那层键盘拦截收不到按键——只有登记给窗口才接
 * 得住。而窗口那一层**只记得住一个输入区**（后登记的胜出），于是「一页摆几块输入区」这件事本身
 * 就是用户看得见的行为：多摆一块，另一块就再也收不到 `⌘V`。
 *
 * 这是一条**只影响粘贴**的 bug：拖放与「打开」不走登记，照旧能用——所以它很容易被漏掉，用例得
 * 单独钉住。
 *
 * 断言看的是**文件名有没有出现在界面上**，而不是状态栏：有 bug 时状态栏那句反而是「这个文件不属于
 * 这个框」，拿它当断言会把 bug 当成预期。
 */
class ApkSignDevToolTest {

    @Test
    fun `密钥库页粘一个密钥库就装进那一格`() = runComposeUiTest {
        val store = emptyKeyStoreFile("release.p12")
        val host = FakeHost(clipboardFiles = listOf(store))
        val pasteKey = render(host)

        assertTrue(pasteKey.handle(), "输入区在，这次粘贴就该由它接过去")
        waitUntil(timeoutMillis = 10_000) { emptyKeyStoreHintShown().not() }

        assertNull(host.status, "装进去了就不该再抱怨什么")
        onNodeWithText("release.p12").assertIsDisplayed()
    }

    /**
     * APK 那一页粘一个包，要落进「输入 · APK」那一格。
     *
     * 这一页上有**两个**放文件的地方（左 APK、右密钥库），而窗口层只记得住最后登记的那一块输入区
     * ——两格各挂一块的话，粘进来的东西永远只会往右边那格去（左边那格的粘贴就此失效）。所以这一页
     * 的输入区必须是**一整块**（见 `FileInputArea`），里面再分两个槽；粘贴按**内容**落到该去的那
     * 一格。
     */
    /**
     * **两页的密钥库互不相干。**
     *
     * 第一页看一个文件，签名那一页用它自己那个（见 `KeyStoreSession`）。原先两页共用一套「当前密钥
     * 库」，后果有两个：签名页没有口令可填（只能先切到第一页把口令敲进去），而第一页上换个文件还会
     * 把签名要用的钥匙顶掉。
     */
    @Test
    fun `第一页放的密钥库不会跑到签名页去`() = runComposeUiTest {
        val store = emptyKeyStoreFile("release.p12")
        val host = FakeHost(clipboardFiles = listOf(store))
        val pasteKey = render(host)

        // 落在第一页（默认就在那一页）。
        assertTrue(pasteKey.handle())
        waitUntil(timeoutMillis = 10_000) { emptyKeyStoreHintShown().not() }

        // 切到签名页：那一格是空的，而口令框**就在这一页上**——不必回第一页去填。
        onNodeWithText("APK").performClick()
        waitForIdle()
        onNodeWithText("插进要用来签名的密钥库，或粘贴 / 打开一个").assertIsDisplayed()
        assertTrue(
            onAllNodesWithText("release.p12").fetchSemanticsNodes().isEmpty(),
            "第一页放的文件不该出现在签名页",
        )
        onNodeWithText("密钥库口令").assertIsDisplayed()
    }

    @Test
    fun `在 APK 那一页粘一个包，装进左边那一格`() = runComposeUiTest {
        val apk = tempFile("app-release.apk", zipMagicBytes())
        val host = FakeHost(clipboardFiles = listOf(apk))
        val pasteKey = render(host)

        onNodeWithText("APK").performClick()
        waitForIdle()

        assertTrue(pasteKey.handle())
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("app-release.apk").fetchSemanticsNodes().isNotEmpty() }

        assertNull(host.status, "包该落进「输入 · APK」那一格，而不是被抱怨放错了框")
    }

    /** 反过来的那一半：在这一页粘一把钥匙，要落进右边「签名 · 密钥库」那一格，且**不翻页**。 */
    @Test
    fun `在 APK 那一页粘一把钥匙，装进右边那一格`() = runComposeUiTest {
        val store = emptyKeyStoreFile("release.p12")
        val host = FakeHost(clipboardFiles = listOf(store))
        val pasteKey = render(host)

        onNodeWithText("APK").performClick()
        waitForIdle()

        assertTrue(pasteKey.handle())
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("release.p12").fetchSemanticsNodes().isNotEmpty() }

        assertNull(host.status)
        // 两个槽都在眼前，没有翻页的理由——翻走反而让人以为自己点错了。
        onNodeWithText("输入 · APK").assertIsDisplayed()
        onNodeWithText("签名 · 密钥库").assertIsDisplayed()
    }

    /** 落在「密钥库」页时粘一个**包**：这一页只有密钥库那一格，所以替用户翻到 APK 那一页。 */
    @Test
    fun `在密钥库页粘一个包会翻到 APK 那一页`() = runComposeUiTest {
        val apk = tempFile("app-release.apk", zipMagicBytes())
        val host = FakeHost(clipboardFiles = listOf(apk))
        val pasteKey = render(host)

        assertTrue(pasteKey.handle())
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("输入 · APK").fetchSemanticsNodes().isNotEmpty() }

        assertNull(host.status)
    }

    /** 既不是密钥库也不是 APK 的东西：说一句，并且**不动**任何一格。 */
    @Test
    fun `认不出的文件只说一句，不塞进任何一格`() = runComposeUiTest {
        val junk = tempFile("notes.txt", "0123456789".encodeToByteArray())
        val host = FakeHost(clipboardFiles = listOf(junk))
        val pasteKey = render(host)

        assertTrue(pasteKey.handle())
        waitUntil(timeoutMillis = 10_000) { host.status != null }

        assertEquals("剪贴板里的这个文件既不像密钥库，也不像 APK", host.status)
        assertNotNull(onNodeWithText("把 .jks / .keystore / .p12 拖进来，或粘贴 / 打开一个").fetchSemanticsNode())
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    private fun ComposeUiTest.render(host: DevToolHost): DevToolPasteKey {
        val pasteKey = DevToolPasteKey()
        setContent {
            MaterialTheme {
                // 窗口层那条粘贴入口：真机由 `ClipperDevToolsWindow` 提供，面板转交给工具。
                CompositionLocalProvider(LocalDevToolPasteKey provides pasteKey) {
                    ApkSignDevTool.Content(input = null, host = host)
                }
            }
        }
        waitForIdle()
        return pasteKey
    }

    /** 密钥库页还没装进东西时那句空态提示（用它判断「装进去了没有」，比读状态栏可靠）。 */
    private fun ComposeUiTest.emptyKeyStoreHintShown(): Boolean =
        onAllNodesWithText("打开一个密钥库后，这里显示它的证书与指纹").fetchSemanticsNodes().isNotEmpty()

    /**
     * 落一个**真的**空 PKCS#12：JDK 自己写出来的，因此长度形式与真实文件一致——用一段手搓的假
     * 字节去测分流，测的就不是真东西了（这条路径上真栽过一次：只有百来字节的空密钥库用的是短形式
     * 长度，而当时的判据只认 `30 82`）。
     */
    private fun emptyKeyStoreFile(name: String): String {
        val file = File(tempDir(), name)
        file.deleteOnExit()
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        file.outputStream().use { store.store(it, "secret".toCharArray()) }
        return file.absolutePath
    }

    /** 一个 APK 的开头：ZIP 的本地文件头。它后面接什么不重要——分流只看开头几个字节。 */
    private fun zipMagicBytes(): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64)

    private fun tempDir(): File =
        Files.createTempDirectory("clipper-apksign").toFile().also { it.deleteOnExit() }

    private fun tempFile(name: String, bytes: ByteArray): String {
        val file = File(tempDir(), name)
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** 只实现 [ApkSignDevTool.Content] 真会用到的那几道口子；其余接口自带空实现。 */
    private class FakeHost(
        /** 剪贴板里放着的那几个文件路径（粘贴用它）。 */
        private val clipboardFiles: List<String> = emptyList(),
    ) : DevToolHost {
        /** 工具最后一次报给状态栏的话；没报过就是 `null`。 */
        var status: String? = null

        override fun copyToClipboard(text: String) = Unit

        override fun showStatus(message: String) {
            status = message
        }

        // 状态栏右段那一句（「这份内容有多大」）。本工具不报它，实现成空操作即可——但要显式占位，
        // 免得将来真报了却悄悄混进 `status`，让上面几条「不该有抱怨」的断言一起失效。
        override fun reportStatus(text: String?) = Unit

        override fun pickFileToOpen(): String? = null

        override fun pickFileToSave(suggestedName: String): String? = null

        override fun droppedFilePaths(event: DragAndDropEvent): List<String> = emptyList()

        override fun clipboardFilePaths(): List<String> = clipboardFiles
    }
}
