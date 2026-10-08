@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.core.ui.code

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * 代码框在**业务层那套调用面**上的契约：框名、动作槽、占位提示、错误说明、`showLabel`。
 *
 * 只断言**能被语义树看到**的东西。像行号、折叠箭头这种画在编辑器内部、不进语义节点的表现，
 * 属于实现自己的测试——见 `KodemirrorCodeFieldTest` 与 `CodeStructureTest`。
 */
class CodeFieldContractTest {

    @Test
    fun `画出框名与动作槽`() = runComposeUiTest {
        underTest {
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

    @Test
    fun `显示空内容时的占位提示`() = runComposeUiTest {
        underTest {
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

    /**
     * 占位提示要**跟着参数换**，而不是只在首帧看一眼就冻住。
     *
     * 各工具都在动态换它：URL 与 Base64 按编解码方向、条码按码制、时间戳按输入格式。KodeMirror
     * 那侧的占位是**建会话时注册的扩展**，只在首次组合捕一次参数，于是真机上表现成「切了码制，
     * 输入框里还挂着上一句示例」。把「动态换」写成契约，再冻回去就在这里红。
     */
    @Test
    fun `占位提示会跟着参数换`() = runComposeUiTest {
        var placeholder by mutableStateOf("第一句提示")
        underTest {
            DevToolCodeField(
                label = "输入",
                value = "",
                onValueChange = {},
                modifier = Modifier.fillMaxSize(),
                placeholder = placeholder,
            )
        }
        onNodeWithText("第一句提示").assertIsDisplayed()

        placeholder = "第二句提示"
        waitForIdle()

        onNodeWithText("第二句提示").assertIsDisplayed()
        onNodeWithText("第一句提示").assertDoesNotExist()
    }

    @Test
    fun `把错误说明显示在框里`() = runComposeUiTest {
        // `isError` 的约定：错误本身也是一份「结果」，交给同一个框显示，不在框外另开一行提示。
        underTest {
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

    @Test
    fun `能关掉自带的那行标题`() = runComposeUiTest {
        underTest {
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

    /** 走业务层真正走的那条入口（[DevToolCodeField]）。 */
    private fun ComposeUiTest.underTest(content: @Composable () -> Unit) {
        setContent {
            MaterialTheme {
                content()
            }
        }
    }
}
