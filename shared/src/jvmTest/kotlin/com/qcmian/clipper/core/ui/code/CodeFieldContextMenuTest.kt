@file:OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)

package com.qcmian.clipper.core.ui.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 代码框的**右键菜单**：剪切 / 复制 / 粘贴 / 全选。
 *
 * 为什么必须真跑组合：右键这条路要三样东西同时在位——按压得先落到输入区那一层（`Initial` 那一趟
 * 抢在编辑器那颗 `awaitEachGesture` 之前）、菜单得真的弹出来（`DropdownMenu` 是另一层）、四行要
 * 按**当时的**选区与只读状态算出可不可点。三样都是节点树上的事，纯逻辑测不出来。
 *
 * 顺带钉住两条容易写反的：只读结果框里**剪切与粘贴必须点不动**（`editable` facet 只管输入那一路，
 * 菜单里的命令走的是程序化派发，绕得过它），而右键落在**已有选区之内**时**不能**把选区收掉
 * （收掉了「选一段、右键、复制」就永远是空的）。
 */
class CodeFieldContextMenuTest {

    @Test
    fun `右键弹出四行，按当时的选区算可不可点`() = runComposeUiTest {
        field(value = """{"a": 1}""")

        rightClick()

        listOf("剪切", "复制", "粘贴", "全选").forEach { onNodeWithText(it).assertIsDisplayed() }
        // 还没有选区：剪切与复制够不着；可编辑、有内容：粘贴与全选够得着。
        onNodeWithText("剪切").assertIsNotEnabled()
        onNodeWithText("复制").assertIsNotEnabled()
        onNodeWithText("粘贴").assertIsEnabled()
        onNodeWithText("全选").assertIsEnabled()

        // 全选之后再右键（这一次落在**选区之内**）：复制这一下可点了——既证明「全选」真的选中了
        // 整篇，也证明右键没有把刚选好的选区收成一个光标。
        onNodeWithText("全选").performClick()
        waitForIdle()
        rightClick()
        onNodeWithText("复制").assertIsEnabled()
        onNodeWithText("剪切").assertIsEnabled()
    }

    @Test
    fun `只读结果框里剪切与粘贴点不动`() = runComposeUiTest {
        field(value = "解出来的文本", editable = false)

        rightClick()

        onNodeWithText("剪切").assertIsNotEnabled()
        onNodeWithText("粘贴").assertIsNotEnabled()
        // 从结果框里挑一段拷走是常规期待：这两项得留着。
        onNodeWithText("全选").assertIsEnabled()
    }

    @Test
    fun `菜单里的粘贴先问「粘贴文件」那条钩子`() = runComposeUiTest {
        var typed = ""
        setContent {
            MaterialTheme {
                DevToolCodeField(
                    label = "输入",
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxSize(),
                    // 与工具接上的那条钩子同一个约定：剪贴板里是文件时它先说话（系统只给文件名，
                    // 直接走文本粘贴会把这个名字贴进正文）。
                    filePaste = { "来自文件" },
                )
            }
        }

        rightClick()
        onNodeWithText("粘贴").performClick()
        waitForIdle()

        assertEquals("来自文件", typed, "钩子说了话就不该再走系统那条文本粘贴")
    }

    /**
     * 菜单要弹在**鼠标落点**上，与输入框在页面里的位置无关。
     *
     * 这条是冲着「有的页面位置不对」来的：原来那一版自己用 Material 的下拉摆菜单，它按锚点往下
     * 排——输入框一挪（标题行、上面的其它控件、面板内边距），菜单就跟着飘出去老远。这里把框推离
     * 原点一百多像素再点，位置还贴在鼠标上才算数。
     */
    @Test
    fun `菜单弹在鼠标落点上`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    // 页面里上面还有别的东西：把输入框推离原点（这一截正是老那版会飘掉的量）。
                    Spacer(Modifier.height(120.dp))
                    DevToolCodeField(
                        label = "输入",
                        value = "abc",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }

        val click = Offset(400f, 360f)
        onRoot().performMouseInput { click(click, button = MouseButton.Secondary) }
        waitForIdle()

        // 量菜单里第一行（它自己带 16dp 左边距、菜单上下各 4dp 内边距）。
        val item = onNodeWithText("剪切").fetchSemanticsNode().positionInRoot
        assertTrue(abs(item.x - click.x) < 32f, "菜单左缘要贴住鼠标：${item.x} vs ${click.x}")
        assertTrue(abs(item.y - click.y) < 32f, "菜单上缘要贴住鼠标：${item.y} vs ${click.y}")
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    private fun ComposeUiTest.field(value: String, editable: Boolean = true) {
        setContent {
            MaterialTheme {
                DevToolCodeField(
                    label = "输入",
                    value = value,
                    onValueChange = {},
                    editable = editable,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /**
     * 在编辑区里右键。
     *
     * 落点用根节点（整块输入框）的**中心**：那里一定在正文区里（标题行只占最上面那一行）。落点会
     * 影响光标，但 `posAtCoords` 认的是它，不需要精确到某个字符。
     */
    private fun ComposeUiTest.rightClick() {
        onRoot().performMouseInput { click(button = MouseButton.Secondary) }
        waitForIdle()
    }
}
