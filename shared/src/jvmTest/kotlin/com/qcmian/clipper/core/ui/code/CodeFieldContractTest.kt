@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.core.ui.code

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * 统一抽象的**契约测试**：同一批断言，对 [CodeFieldEngine.entries] 里的**每一个**实现都跑一遍。
 *
 * 这是「两个实现可以切换、业务层不用改」这句话的可执行版本：
 *  - 哪天多一个实现，它自动进入这个循环（不用记得来加测试）；
 *  - 哪天某个实现把某条契约做丢了（占位提示不显示、动作槽不渲染、`showLabel` 关不掉），
 *    在这里就红，而不是等某个工具里有人肉眼发现。
 *
 * 只断言**两边都该有、且都能被语义树看到**的东西。像行号、折叠箭头这种两边表现不同的
 * （原生那侧画在 Canvas 上、没有语义节点），契约测试覆盖不了，属于各实现自己的测试——
 * 见 `KodemirrorCodeFieldTest` 与 `CodeStructureTest`。
 */
class CodeFieldContractTest {

    @Test
    fun `每个实现都画出框名与动作槽`() {
        forEachEngine { engine ->
            underTest(engine) {
                DevToolCodeField(
                    label = "输入",
                    value = "内容",
                    onValueChange = {},
                    modifier = Modifier.fillMaxSize(),
                    actions = { Text("先清空") },
                )
            }
            onNodeWithText("输入").assertIsDisplayed()
            onNodeWithText("先清空").assertIsDisplayed()
        }
    }

    @Test
    fun `每个实现都显示空内容时的占位提示`() {
        forEachEngine { engine ->
            underTest(engine) {
                DevToolCodeField(
                    label = "输入",
                    value = "",
                    onValueChange = {},
                    modifier = Modifier.fillMaxSize(),
                    placeholder = "把内容放进来",
                )
            }
            onNodeWithText("把内容放进来").assertIsDisplayed()
        }
    }

    @Test
    fun `每个实现都把错误说明显示在框里`() {
        // `isError` 的约定：错误本身也是一份「结果」，交给同一个框显示，不在框外另开一行提示。
        forEachEngine { engine ->
            underTest(engine) {
                DevToolCodeField(
                    label = "结果",
                    value = "解析失败：第 1 行 3 列",
                    onValueChange = {},
                    modifier = Modifier.fillMaxSize(),
                    isError = true,
                )
            }
            onNodeWithText("解析失败：第 1 行 3 列").assertIsDisplayed()
        }
    }

    @Test
    fun `每个实现都能不画自带的那行标题`() {
        forEachEngine { engine ->
            underTest(engine) {
                DevToolCodeField(
                    label = "输入",
                    value = "内容",
                    onValueChange = {},
                    modifier = Modifier.fillMaxSize(),
                    showLabel = false,
                    actions = { Text("先清空") },
                )
            }
            onNodeWithText("输入").assertDoesNotExist()
            // 标题行整条都不出现，动作槽也跟着走——调用方既然自己安排标签，就别再冒出半行。
            onNodeWithText("先清空").assertDoesNotExist()
        }
    }

    /**
     * 对每一种实现跑一遍 [block]。
     *
     * 每种实现**各起一个独立的组合环境**：这是契约测试，不是同一个界面里比两家。
     */
    private fun forEachEngine(block: ComposeUiTest.(CodeFieldEngine) -> Unit) {
        for (engine in CodeFieldEngine.entries) {
            runComposeUiTest { block(engine) }
        }
    }

    /** 把引擎钉死成 [engine]，然后走业务层真正走的那条入口（[DevToolCodeField]）。 */
    private fun ComposeUiTest.underTest(engine: CodeFieldEngine, content: @Composable () -> Unit) {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCodeFieldEngine provides engine) { content() }
            }
        }
    }
}
