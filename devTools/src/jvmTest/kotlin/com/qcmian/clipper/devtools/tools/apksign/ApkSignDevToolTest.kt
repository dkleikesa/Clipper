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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolPasteKey
import com.qcmian.clipper.devtools.api.LocalDevToolPasteKey
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **粘贴文件**这一条路：真机上按 `⌘V`，走的是窗口层那条（见 `DevToolPasteKey`）。
 *
 * 为什么专门盯着它：三处文件格都是 `DevToolFileField`，**都不登记**窗口层的粘贴（它们的回调只知道
 * 「有文件来了」，不知道按键从哪来），所以入口由工具自己登记一处（`Content` 里那段 `register`）。
 * 而窗口那一层**只记得住一个登记**（后登记的胜出）：忘登记、或登记了两处，症状都只是「粘贴不好使」
 * ——不会报错。
 *
 * 这是一条**只影响粘贴**的 bug：拖放与「打开」不走登记，照旧能用——所以它很容易被漏掉，用例得
 * 单独钉住。
 *
 * 断言看的是**路径有没有出现在界面上**，而不是状态栏：状态栏那句是给「哪一步不对」准备的，拿它当
 * 断言会把 bug 当成预期。
 */
class ApkSignDevToolTest {

    @Test
    fun `密钥库页粘一个密钥库就装进那一格`() = runComposeUiTest {
        val store = emptyKeyStoreFile()
        val host = FakeHost(clipboardFiles = listOf(store))
        val pasteKey = render(host)

        assertTrue(pasteKey.handle(), "输入区在，这次粘贴就该由它接过去")
        waitUntil(timeoutMillis = 10_000) { emptyKeyStoreHintShown().not() }

        assertNull(host.status, "装进去了就不该再抱怨什么")
        // 那一格显示的是**绝对路径**（不是文件名），所以按子串找。
        assertTrue(onAllNodesWithText("release.p12", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * APK 那一页粘一个包，要落进「APK」那一行。
     *
     * 这一页上有**两个**放文件的地方（APK、KeyStore），而窗口层只记得住最后登记的那一块输入区
     * ——两个都挂上，粘进来的东西永远只会往后登记的那个去。所以这一页的粘贴由**工具自己登记一次**
     * （见 `Content` 里那段 `register`），落点按**内容**分流。
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
        val store = emptyKeyStoreFile()
        val host = FakeHost(clipboardFiles = listOf(store))
        val pasteKey = render(host)

        // 落在第一页（默认就在那一页）。
        assertTrue(pasteKey.handle())
        waitUntil(timeoutMillis = 10_000) { emptyKeyStoreHintShown().not() }

        // 切到签名页：签名那一栏的密钥库是空的，而口令框**就在这一页上**——不必回第一页去填。
        onNodeWithText("APK").performClick()
        waitForIdle()
        onNodeWithText("拖拽、粘贴、打开KeyStore文件，或输入路径").assertIsDisplayed()
        assertTrue(
            // 子串匹配：那一格显示的是绝对路径，按文件名整串找会平凡通过（等于没测）。
            onAllNodesWithText("release.p12", substring = true).fetchSemanticsNodes().isEmpty(),
            "第一页放的文件不该出现在签名页",
        )
        onNodeWithText("KeyStore 密码").assertIsDisplayed()
    }

    @Test
    fun `在 APK 那一页粘一个包，落进 APK 那一行`() = runComposeUiTest {
        val apk = tempFile("app-release.apk", zipMagicBytes())
        val host = FakeHost(clipboardFiles = listOf(apk))
        val pasteKey = render(host)

        onNodeWithText("APK").performClick()
        waitForIdle()

        assertTrue(pasteKey.handle())
        // 那一格显示的是**绝对路径**（不是文件名），所以按路径找。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(apk, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        assertNull(host.status, "包该落进「APK」那一行，而不是被抱怨放错了框")
    }

    /** 反过来的那一半：在这一页粘一把钥匙，要落进下面签名那一栏的「密钥库」，且**不翻页**。 */
    @Test
    fun `在 APK 那一页粘一把钥匙，落进签名那一栏`() = runComposeUiTest {
        val store = emptyKeyStoreFile()
        val host = FakeHost(clipboardFiles = listOf(store))
        val pasteKey = render(host)

        onNodeWithText("APK").performClick()
        waitForIdle()

        assertTrue(pasteKey.handle())
        // 那一格显示的是**绝对路径**（不是文件名），所以按路径找。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(store, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        assertNull(host.status)
        // 两处都在眼前，没有翻页的理由——翻走反而让人以为自己点错了。
        //
        // 断言看 APK 那一格的占位符而不是它的行标签：行标签是「APK」，与页签同名，`onNodeWithText`
        // 会同时命中两个。占位符只此一处。
        onNodeWithText("拖拽、粘贴、打开APK文件，或输入路径").assertIsDisplayed()
        onNodeWithText("KeyStore").assertIsDisplayed()
    }

    /** 落在「密钥库」页时粘一个**包**：这一页只有密钥库那一格，所以替用户翻到 APK 那一页。 */
    @Test
    fun `在密钥库页粘一个包会翻到 APK 那一页`() = runComposeUiTest {
        val apk = tempFile("app-release.apk", zipMagicBytes())
        val host = FakeHost(clipboardFiles = listOf(apk))
        val pasteKey = render(host)

        assertTrue(pasteKey.handle())
        // 翻过去了、而且落进了 APK 那一格：两件事一起看——那个路径只会出现在签名页那一行上。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(apk, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        assertNull(host.status)
    }

    /**
     * 既不是密钥库也不是 APK 的东西：**也留在框里**，由那一格自己说原因。
     *
     * 原先只丢一句状态栏提示、一格都不动。那条提示会自己淡掉——而「我粘进来的东西呢？它哪里不对？」
     * 恰恰是最需要一直挂着的一句话，淡掉之后屏幕上什么线索都不剩。现在路径进框（看得见，还能改个
     * 字再试），原因由读它的那一方**常驻**地说出来。
     */
    @Test
    fun `认不出的文件也留在框里，由那一格自己说原因`() = runComposeUiTest {
        // 内容用与「敲一个不是 KeyStore 的路径」那条同一个夹具：它的字节让 JDK 抛的是「认不出这个
        // 容器」那一支，报的正是下面断言的那句话（换成 `0123456789` 这种首字节像个 DER 头的，
        // 报的就是另一句「无法读取该 KeyStore：…」——这条用例关心的是**留不留、说不说**，不该被
        // 夹具的字面碰巧决定）。
        val junk = tempFile("notes.txt", "这不是密钥库".encodeToByteArray())
        val host = FakeHost(clipboardFiles = listOf(junk))
        val pasteKey = render(host)

        assertTrue(pasteKey.handle())
        // 页面里只说这一件事「这个 KeyStore 无法读取」；**具体原因**在状态栏右段（常驻那一段）。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("这个 KeyStore 无法读取", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 10_000) { host.reported?.contains("不是有效的 JKS / PKCS#12 文件") == true }
        // 路径留在框里：这是「它到底进了哪儿」的唯一线索。
        assertNotNull(onAllNodesWithText("notes.txt", substring = true).fetchSemanticsNodes().firstOrNull())

        assertNull(host.status, "失败不走那条会自己淡掉的临时提示")
    }

    /**
     * 三个签名方案是**固定的三个开关**：取消勾选只该把它关掉，不该让它从这一行里消失。
     *
     * 钉的是一个写错就会犯的错——遍历错了集合（拿「当前选中的那几个」当遍历对象），一取消勾选，
     * 那个开关自己就没了，而且再也点不回来。
     */
    @Test
    fun `取消勾选签名方案后开关还在`() = runComposeUiTest {
        render(FakeHost())
        onNodeWithText("APK").performClick()
        waitForIdle()

        SignScheme.entries.forEach { onNodeWithText(it.title).assertIsDisplayed() }

        onNodeWithText("v3").performClick()
        waitForIdle()

        // 关掉了也还在（也就点得回来）。
        SignScheme.entries.forEach { onNodeWithText(it.title).assertIsDisplayed() }
    }

    /**
     * 签名那一栏的路径**可以直接敲**（三条文件来路之外的第四条），敲完要真读出来。
     *
     * 这一条同时盖住防抖：敲进去的路径先停在框里，过一小会儿没再动才拿去读——不然每敲一个字都要
     * 开一次文件，并把还没敲完的路径报成「读不了」。
     */
    @Test
    fun `在签名那一栏直接敲路径也能读出来`() = runComposeUiTest {
        // 造一个**真带私钥**的密钥库：别名那一栏得真有东西可读。
        val path = File(tempDir(), "typed.p12").absolutePath
        val created = createKeyStore(
            KeyStoreRequest(
                outPath = path,
                storeType = StoreFormat.Pkcs12.keyStoreType,
                alias = "key0",
                password = "123456".toCharArray(),
                algorithm = KeyAlgorithm.Rsa2048,
                validityDays = DefaultValidityDays,
                subject = "CN=demo",
            ),
        )
        assertTrue(created is CreateOutcome.Done, "$created")

        render(FakeHost())
        onNodeWithText("APK").performClick()
        waitForIdle()

        onNodeWithText("拖拽、粘贴、打开KeyStore文件，或输入路径").performTextInput(path)
        // 读出来之后，别名那一栏显示的就是库里的那个别名。
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("key0").fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 换一个**不是 KeyStore** 的文件进来，要**说出原因**，而不是笼统地报「没有可用的别名」。
     *
     * 这一条走的是**手敲路径**那条路：它不过「按魔数认文件」那一关（拖动 / 粘贴 / 打开都会先认
     * 一遍），所以一个 .txt 也能被敲进来，直到读的时候才失败。
     */
    @Test
    fun `敲一个不是 KeyStore 的路径会说出原因`() = runComposeUiTest {
        val junk = tempFile("notes.txt", "这不是密钥库".encodeToByteArray())
        val host = FakeHost()
        render(host)
        onNodeWithText("APK").performClick()
        waitForIdle()

        onNodeWithText("拖拽、粘贴、打开KeyStore文件，或输入路径").performTextInput(junk)
        // 状态只在状态栏显示一处（签名按钮下方那行已删除）：断言报出去的那句，而不是页面上的文字。
        waitUntil(timeoutMillis = 10_000) { host.reported?.contains("不是有效的 JKS / PKCS#12 文件") == true }
    }

    /**
     * 只看一个包时，状态栏**不提签名缺什么**。
     *
     * 这一页最常见的用法是打开一个包看它签没签；此时常驻一句「需要选一个 KeyStore 用于签名」，与
     * 用户当下在做的事无关，还和读取结果挤在同一行（见 `Content` 里那段 `reportStatus`）。那句话
     * 改由点签名按钮时按需给出（见下一条）。
     */
    @Test
    fun `只看一个包时状态栏不提签名缺什么`() = runComposeUiTest {
        val apk = tempFile("app-release.apk", zipMagicBytes())
        val host = FakeHost()
        render(host)
        onNodeWithText("APK").performClick()
        waitForIdle()

        onNodeWithText("拖拽、粘贴、打开APK文件，或输入路径").performTextInput(apk)
        // 等这一页真读完这个包（读完才有话可说，那时报的必须是读取结果）。
        waitUntil(timeoutMillis = 10_000) { host.reported != null }
        assertFalse(host.reported.orEmpty().contains("KeyStore"), host.reported.orEmpty())
    }

    /**
     * 缺东西时签名按钮**点得动**，点一下就把缺的那一项说出来。
     *
     * 代替原先那种「按钮灰着、原因常驻在状态栏」的做法：一个点不动又不说明原因的按钮是最难受的
     * 控件（同「新建KeyStore」页那个按钮）。
     */
    @Test
    fun `缺东西时点签名按钮会说明缺什么`() = runComposeUiTest {
        val apk = tempFile("app-release.apk", zipMagicBytes())
        val host = FakeHost()
        render(host)
        onNodeWithText("APK").performClick()
        waitForIdle()

        onNodeWithText("拖拽、粘贴、打开APK文件，或输入路径").performTextInput(apk)
        waitUntil(timeoutMillis = 10_000) { host.reported != null }

        onNodeWithText("签名并另存为…").performClick()
        waitForIdle()
        // 有 APK、没有 KeyStore：报的就是这一件。
        assertEquals("需要选一个 KeyStore 用于签名", host.status)
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
        onAllNodesWithText("打开一个 KeyStore 后，这里显示它的证书与指纹").fetchSemanticsNodes().isNotEmpty()

    /**
     * 落一个**真的**空 PKCS#12：JDK 自己写出来的，因此长度形式与真实文件一致——用一段手搓的假
     * 字节去测分流，测的就不是真东西了（这条路径上真栽过一次：只有百来字节的空密钥库用的是短形式
     * 长度，而当时的判据只认 `30 82`）。
     */
    private fun emptyKeyStoreFile(): String {
        val file = File(tempDir(), "release.p12")
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
        /** 工具最后一次**临时**提示（`showStatus`）；没报过就是 `null`。 */
        var status: String? = null

        /**
         * 工具最后报给状态栏**右段**的那句常驻说明（`reportStatus`）；没报过或已清空就是 `null`。
         *
         * 与本工具的分工有关（见 `Content` 里那段）：**失败原因统一走这里**，页面里只留一句指向
         * 它的话。所以「说出了原因」这件事得在这上面断言，而不是页面里的那句指路话。
         */
        var reported: String? = null

        override fun copyToClipboard(text: String) = Unit

        override fun showStatus(message: String) {
            status = message
        }

        override fun reportStatus(text: String?) {
            reported = text
        }

        override fun pickFileToOpen(): String? = null

        override fun pickFileToSave(suggestedName: String): String? = null

        override fun droppedFilePaths(event: DragAndDropEvent): List<String> = emptyList()

        override fun clipboardFilePaths(): List<String> = clipboardFiles
    }
}
