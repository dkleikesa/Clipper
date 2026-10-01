@file:OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)

package com.qcmian.clipper.feature.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.core.testutil.InProcessCluster
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.ui.HistoryScreen
import com.qcmian.clipper.feature.history.viewmodel.ClipboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F6（C 层）：**面板交互**。
 *
 * 渲染**真的 [HistoryScreen]**，用真 `ClipboardViewModel` + 真存储驱动，按键经 Compose 的按键
 * 分发送进 `onPreviewKeyEvent`——因此验的是「用户在面板上按这个键，面板会做什么」，而不是把
 * 映射规则抄一遍。
 *
 * **平台限制（为什么有些组合用构造的事件）**：桌面端 compose-ui-test 的按键注入
 * （`performKeyInput`）只会送出裸按键——它构造 `KeyEvent` 时**不带任何修饰键状态**
 * （见 `InputDispatcher.skiko.kt`），指针事件同样不带键盘修饰键。默认绑定里 `↑`/`↓`/`⏎`/`⎋`
 * 本来就是裸键，可以照常注入；带 `⌥`/`⌘`/`⌃`/`⇧` 的组合则用 `KeyEvent(...)` 直接构造后经
 * `performKeyPress` 送入同一套分发路径（`@InternalComposeUiApi`，仅测试内使用）。
 *
 * 多选（`⌘` 点击）同样受此限：渲染层读的是指针事件里的修饰键，测试里改为直接派发点击本会产生
 * 的那条动作，面板其余部分照旧。
 */
class AcceptancePanelInteractionTest {

    private lateinit var cluster: InProcessCluster
    private var hideRequests = 0

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        cluster = InProcessCluster()
        hideRequests = 0
    }

    @AfterTest
    fun tearDown() {
        cluster.close()
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------------------

    @Test
    fun `面板渲染出历史条目`() = runComposeUiTest {
        seed("第一条", "第二条")
        val viewModel = viewModel()
        render(viewModel)

        waitUntil { viewModel.uiState.value.results.size == 2 }
        onNodeWithText("第一条").assertIsDisplayed()
        onNodeWithText("第二条").assertIsDisplayed()
    }

    @Test
    fun `上下方向键在列表里移动光标`() = runComposeUiTest {
        seed("甲", "乙", "丙")
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.size == 3 }

        assertEquals(0, viewModel.uiState.value.historySelection, "默认落在内容区第一条")

        send(Key.DirectionDown)
        waitUntil { viewModel.uiState.value.historySelection == 1 }

        send(Key.DirectionUp)
        waitUntil { viewModel.uiState.value.historySelection == 0 }
    }

    @Test
    fun `回车直接粘贴到上一个应用`() = runComposeUiTest {
        seed("待粘贴")
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.size == 1 }

        send(Key.Enter)

        // 记账发生在写回之后（`recordBatchCopy`），因此等计数而不是只等粘贴动作。
        waitUntil(timeoutMillis = 10_000) { cluster.repository.unpinned.value.single().numberOfCopies == 2 }
        assertEquals("待粘贴", cluster.clipboard.written?.text)
        assertEquals(1, cluster.clipboard.pasteCount, "粘贴要合成一次 ⌘V")
    }

    @Test
    fun `选项加回车只复制不粘贴`() = runComposeUiTest {
        seed("只复制不上屏")
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.size == 1 }

        send(KeyEvent(Key.Enter, KeyEventType.KeyDown, isAltPressed = true))

        waitUntil(timeoutMillis = 10_000) { cluster.repository.unpinned.value.single().numberOfCopies == 2 }
        assertEquals(1, cluster.clipboard.writes.size)
        assertEquals(0, cluster.clipboard.pasteCount, "激活只写剪贴板，不合成 ⌘V")
    }

    @Test
    fun `选项加 P 置顶与取消置顶`() = runComposeUiTest {
        seed("甲乙")
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.size == 1 }

        send(KeyEvent(Key.P, KeyEventType.KeyDown, isAltPressed = true))
        waitUntil { cluster.repository.pinned.value.size == 1 }

        send(KeyEvent(Key.P, KeyEventType.KeyDown, isAltPressed = true))
        waitUntil { cluster.repository.pinned.value.isEmpty() }
    }

    @Test
    fun `选项加退格删除选中项`() = runComposeUiTest {
        seed("要删掉的", "留下的")
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.size == 2 }

        send(KeyEvent(Key.Backspace, KeyEventType.KeyDown, isAltPressed = true))

        waitUntil { cluster.repository.unpinned.value.size == 1 }
        assertEquals(
            listOf("留下的"),
            cluster.repository.unpinned.value.map { it.title },
            "删除的是光标那一条（默认第一条）",
        )
    }

    @Test
    fun `控制加空格开合预览`() = runComposeUiTest {
        seed("预览我")
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.size == 1 }

        send(KeyEvent(Key.Spacebar, KeyEventType.KeyDown, isCtrlPressed = true))
        waitUntil { viewModel.uiState.value.previewOpen }
        waitUntil { viewModel.uiState.value.previewItem?.id == "id0" }
        assertTrue(cluster.repository.settings.value.previewOpen, "预览开合是持久化的用户选择")

        send(KeyEvent(Key.Spacebar, KeyEventType.KeyDown, isCtrlPressed = true))
        waitUntil { !viewModel.uiState.value.previewOpen }
    }

    @Test
    fun `命令加数字快速粘贴置顶项`() = runComposeUiTest {
        seed("置顶项", "普通项")
        runBlocking { cluster.repository.setPinned(listOf("id0"), true) }
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.firstOrNull()?.meta?.id == "id0" }

        send(KeyEvent(Key.One, KeyEventType.KeyDown, isMetaPressed = true))

        waitUntil(timeoutMillis = 10_000) { cluster.clipboard.pasteCount == 1 }
        assertEquals("置顶项", cluster.clipboard.written?.text, "数字键固定执行「直接粘贴」")
    }

    @Test
    fun `命令加 P 暂停与恢复记录`() = runComposeUiTest {
        seed("一条")
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.size == 1 }

        send(KeyEvent(Key.P, KeyEventType.KeyDown, isMetaPressed = true))
        waitUntil { cluster.repository.settings.value.ignoreEvents }

        send(KeyEvent(Key.P, KeyEventType.KeyDown, isMetaPressed = true))
        waitUntil { !cluster.repository.settings.value.ignoreEvents }
    }

    @Test
    fun `Shift 加下方向键连续选中`() = runComposeUiTest {
        seed("甲", "乙", "丙")
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.size == 3 }

        send(KeyEvent(Key.DirectionDown, KeyEventType.KeyDown, isShiftPressed = true))

        waitUntil { viewModel.uiState.value.selectedIds.size == 2 }
        assertEquals(setOf("id0", "id1"), viewModel.uiState.value.selectedIds)
    }

    @Test
    fun `Esc 逐级退出 多选 到 搜索 到 关窗`() = runComposeUiTest {
        seed("甲", "乙", "丙")
        val viewModel = viewModel()
        render(viewModel)
        waitUntil { viewModel.uiState.value.results.size == 3 }

        // ① 多选：点击时按住 ⌘ 产生的就是这条动作（修饰键无法注入，见类注释）。
        viewModel.onAction(ClipboardUiAction.SelectOnly(0))
        viewModel.onAction(ClipboardUiAction.ToggleSelection(1))
        waitUntil { viewModel.uiState.value.isMultiSelect }

        send(Key.Escape)
        waitUntil { !viewModel.uiState.value.isMultiSelect }
        assertEquals(0, hideRequests, "第一下 Esc 只退回单选，不关窗")

        // ② 搜索词非空：Esc 清空搜索。
        viewModel.onAction(ClipboardUiAction.UpdateQuery("甲"))
        waitUntil { viewModel.uiState.value.appliedQuery == "甲" }

        send(Key.Escape)
        waitUntil { viewModel.uiState.value.appliedQuery.isEmpty() }
        assertEquals(0, hideRequests, "第二下 Esc 只清搜索，不关窗")

        // ③ 搜索为空：Esc 关闭面板。
        send(Key.Escape)
        waitUntil { hideRequests == 1 }
    }

    // -------------------------------------------------------------------------------------

    /**
     * 按传入顺序落条目：列表默认按「最后复制」降序，因此时间戳要**递减**，`id0` 才能排在第一条
     * （光标默认落在内容区第一条）。
     */
    private fun seed(vararg texts: String) = runBlocking {
        cluster.start()
        texts.forEachIndexed { index, text ->
            cluster.seedText("id$index", text, lastCopiedAt = (texts.size - index).toLong())
        }
    }

    private fun viewModel(): ClipboardViewModel =
        ClipboardViewModel(cluster.repository, cluster.repository, cluster.useCases)

    /** 渲染真面板；`hideRequests` 记录面板被要求关闭的次数。 */
    private fun ComposeUiTest.render(viewModel: ClipboardViewModel) {
        viewModel.onRequestHideWindow = { hideRequests++ }
        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsState()
                HistoryScreen(
                    state = state,
                    onAction = viewModel::onAction,
                    onPreferredHeightChange = {},
                    onMinimumHeightChange = {},
                    applicationIcon = { null },
                )
            }
        }
        // 面板在若干帧里申领搜索框焦点；没有焦点，按键根本派发不到 onPreviewKeyEvent。
        waitForIdle()
    }

    /** 裸键：直接走平台注入（键位映射由 `shortcutCharacterOf` 还原）。 */
    private fun ComposeUiTest.send(key: Key) {
        onRoot().performKeyPress(KeyEvent(key, KeyEventType.KeyDown))
    }

    /** 带修饰键的组合：构造好的事件走同一套分发路径（见类注释）。 */
    private fun ComposeUiTest.send(event: KeyEvent) {
        onRoot().performKeyPress(event)
    }
}
