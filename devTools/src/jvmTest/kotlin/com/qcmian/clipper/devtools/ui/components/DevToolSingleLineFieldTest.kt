@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.devtools.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.core.ui.theme.ClipperTheme
import kotlin.test.Test

/**
 * 单行输入框的**右键菜单**：桌面端 Compose 默认给 `BasicTextField` 挂的是平台那套长相，与主面板 /
 * 代码框的菜单对不上。`ClipperTheme` 那一层已经把它换成本应用自己的那份（见 `AppTextContextMenu`），
 * 这条钉住的就是这件事。
 *
 * 判据用**键位提示**：两边都有「剪切 / 复制 / 粘贴 / 全选」这些字，但平台那份不带 `⌘A` 这样的提示，
 * 「提示在不在」正好能把「现在画的是哪一份」量出来。
 */
class DevToolSingleLineFieldTest {

    @Test
    fun `右键菜单是应用自己的那一套`() = runComposeUiTest {
        setContent {
            // 走真实的主题那一层：菜单就是从这里换掉的，绕开它就测不到那件事。
            ClipperTheme(darkTheme = false) {
                DevToolSingleLineField(
                    value = "abc",
                    onValueChange = {},
                    placeholder = "占位",
                    modifier = Modifier,
                )
            }
        }

        onNodeWithText("abc").performMouseInput { click(button = MouseButton.Secondary) }
        waitForIdle()

        listOf("剪切", "复制", "粘贴", "全选").forEach { onNodeWithText(it).assertExists() }
        // 平台那份没有键位提示：这一条能过，就说明画的确实是应用自己那份。
        onNodeWithText("⌘A").assertExists()
    }
}
