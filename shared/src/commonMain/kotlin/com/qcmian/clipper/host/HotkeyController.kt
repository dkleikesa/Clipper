package com.qcmian.clipper.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局热键通道：桌面外壳 ViewModel 的热键状态机把用户的按键意图以计数器形式发给面板，
 * 由 `App` 转成 `ClipboardUiAction`（`Opened` / `Cycle` / `Accept`）。
 *
 * 与 [WindowController] 的分工：这里传递的是「按键意图」——打开、循环、接受，
 * 与窗口生命周期无关；窗口事件（显示、隐藏、退出）见 [WindowController]。
 */
class HotkeyController {
    private val _openRequests = MutableStateFlow(0)

    /** 通过全局热键打开面板时自增。 */
    val openRequests: StateFlow<Int> = _openRequests.asStateFlow()

    private val _cycleRequests = MutableStateFlow(0)

 /** 面板已经打开时按下全局热键则自增。面板会据此高亮下一条，即。 */
    val cycleRequests: StateFlow<Int> = _cycleRequests.asStateFlow()

    private val _acceptRequests = MutableStateFlow(0)

    /** 循环模式下松开全局热键时自增，松开时接受当前高亮的条目。 */
    val acceptRequests: StateFlow<Int> = _acceptRequests.asStateFlow()

    /** 请求打开面板。 */
    fun requestOpen() {
        _openRequests.value++
    }

    /** 移到下一条历史。 */
    fun requestCycle() {
        _cycleRequests.value++
    }

    /** 松开修饰键时接受高亮的条目。 */
    fun requestAccept() {
        _acceptRequests.value++
    }
}
