package com.qcmian.clipper.feature.history.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * 呼出面板后为搜索框争取焦点的最大重试帧数。
 *
 * 面板显示时界面会重新测量并重组，搜索框节点随之重建；一次请求很容易落在节点就绪之前而静默
 * 失败（异常被 `runCatching` 吞掉），表现为「呼出后打不了字」。因此多试几帧——`requestFocus()`
 * 幂等，已聚焦时是空操作。
 */
private const val FOCUS_REQUEST_ATTEMPTS = 12

/**
 * 跨若干帧重申搜索框焦点。
 *
 * `BoxWithConstraints` 会在布局阶段对内容做子组合，因此搜索框的焦点修饰符要等到第一帧之后才会
 * 挂上；没挂上时 `requestFocus()` 会抛 "FocusRequester is not initialized"，所以统一包一层
 * `runCatching`。只请求一次是不够的：面板每次显示都会重新测量、搜索框节点随之重建，请求早一帧
 * 就会静默失败，因此跨帧重申。
 *
 * 注意这里只解决「场景内哪个节点接收键盘」；「按键能不能进到场景」是另一件事，由宿主在窗口显示
 * 时把 AWT 焦点交给窗口内容组件负责（见 `ClipperWindow.focusKeyboardTarget`）。
 */
internal suspend fun awaitSearchFocus(focusRequester: FocusRequester) {
    repeat(FOCUS_REQUEST_ATTEMPTS) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}
