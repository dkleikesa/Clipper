package com.qcmian.clipper.desktop.domain

/**
 * 开发者工具窗口收到热键（`⇧⌘D`）时该做什么。
 *
 * 「开着的开发者工具窗口再按一次」有两种截然不同的意图，而**状态上看不出差别**
 * （`ClipboardUiState.devToolsOpen` 两种情况都是 `true`）：
 * - [CLOSE]：窗口就在眼前，用户按这一下是要关掉它；
 * - [PRESENT]：窗口开着但被别的应用压在后面——或者刚被关掉——用户按这一下是要它出现在眼前。
 */
internal enum class DevToolsHotKeyAction { CLOSE, PRESENT }

/**
 * 开发者工具热键的意图：窗口在最前时是「关掉」，其余情况都是「让它出现在眼前」。
 *
 * 为什么不能一律切换：窗口开到后台之后再按快捷键，切换等于把一个**用户根本看不见**的窗口
 * 关掉——界面上的表现就是「按了没反应」（用户不知道它其实在后台悄悄关了），而用户按这一下
 * 想要的显然是「把我刚才那个窗口调出来」。
 *
 * 判据是窗口此刻在不在最前（`WindowController.isDevToolsWindowFocused`，由那个窗口自己的
 * 焦点监听维护），而不是「窗口开没开着」：后者在两种意图下都是 `true`。这一位在窗口被隐藏时
 * 也会被清掉，因此「窗口根本没开」同样落到 [DevToolsHotKeyAction.PRESENT]——那个窗口收到
 * 请求时自己会把它开出来（见 `ClipperDevToolsWindow`），热键这一侧不必再认识「开没开着」。
 */
internal fun devToolsHotKeyActionOf(isWindowFocused: Boolean): DevToolsHotKeyAction =
    if (isWindowFocused) DevToolsHotKeyAction.CLOSE else DevToolsHotKeyAction.PRESENT
