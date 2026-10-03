package com.qcmian.clipper.devtools.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.qcmian.clipper.devtools.api.DevToolHost

/**
 * 状态栏那句「眼前这段内容从哪来」里，用户**自己在编辑框里改过**的那一档。
 *
 * 各工具共用一份字面量：同一个状态在两个工具里叫两个词，读起来就像两回事。
 */
internal const val DevToolTypedSource = "文本输入"

/**
 * 把「眼前这段内容从哪来」报告给窗口底部的状态栏；[label] 为 `null` 时交回面板判断
 * （剪贴板 / 文件）。
 *
 * 面板只知道打开时带了哪条剪贴板记录、用户挑过哪些文件（见 `DevToolsPanel` 里的 `sourceLabel`），
 * 却不知道用户随后是否在编辑框里自己改了内容——那发生在工具内部。所以这一档只能由工具说：工具
 * 在灌入剪贴板内容的副作用里把来源复位成 `null`，在编辑框的 `onValueChange` 里置成
 * [DevToolTypedSource]。
 *
 * 做成 `SideEffect`（每次重组都同步）而不是「只在 [label] 变化时报告」：面板换工具 / 换剪贴板条目
 * 时会清空它手里的来源，而那一刻本工具的 [label] 并没有变，只在变化时报告就会漏掉这一次，状态栏
 * 退回兜底文案。每次都同步即可自我修复；写的是同一个值时 Compose 不会因此再多重组一次
 * （`mutableStateOf` 走结构相等）。
 */
@Composable
internal fun DevToolReportSource(host: DevToolHost, label: String?) {
    SideEffect {
        host.reportSource(label)
    }
}
