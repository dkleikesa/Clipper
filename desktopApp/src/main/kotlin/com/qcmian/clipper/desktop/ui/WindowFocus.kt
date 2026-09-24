package com.qcmian.clipper.desktop.ui

/** 窗口显示后为「键盘目标组件」争取 AWT 焦点的最大重试帧数。 */
internal const val FOCUS_TARGET_ATTEMPTS = 12

/**
 * 把 AWT 焦点交给窗口里真正接收键盘的那个组件。
 *
 * Compose 的按键监听挂在窗口**内容组件**上（`ComposeSceneMediator.keyListener` 是个挂在
 * `SkiaLayerComponent` 上的 `KeyListener`），而 AWT 只把按键派发给焦点所有者。直接
 * `window.requestFocus()` 会把焦点给窗口框架本身，于是按键全落在框架上、Compose 侧一条都
 * 收不到——表现为「窗口呼出后打不了字，Esc / 方向键也全没反应」，点一下窗口内部才恢复。
 *
 * 因此这里沿组件树下探到最深的可聚焦组件（也就是 Compose 的内容组件）再请求焦点；
 * 找不到时退回窗口本身，行为与不做这件事时一致。
 *
 * 面板与设置窗口都由程序显示（`visible = true`，没有那一次「用户点进来」的点击），
 * 因此**两个窗口都需要它**，这也是它被抽成公共函数的原因。
 */
internal fun focusKeyboardTarget(window: java.awt.Window): Boolean {
    val target = deepestFocusableChild(window)
    if (target == null) {
        // 找不到内容组件（或它不可聚焦）：退回窗口本身，行为与改动前一致。
        return window.requestFocusInWindow() || window.isFocusOwner
    }
    if (target.isFocusOwner) return true
    // 窗口还没成为 focused window 时 `requestFocusInWindow()` 会返回 `false`，
    // 调用方据此跨帧重试。
    return target.requestFocusInWindow() || target.isFocusOwner
}

/** 组件树里最深的「可聚焦且可见」的组件；只有窗口自身可聚焦时返回 `null`。 */
private fun deepestFocusableChild(container: java.awt.Container): java.awt.Component? {
    var found: java.awt.Component? = null
    fun visit(component: java.awt.Component) {
        if (component.isFocusable && component.isVisible) found = component
        (component as? java.awt.Container)?.components?.forEach(::visit)
    }
    visit(container)
    return found?.takeIf { it !== container }
}
