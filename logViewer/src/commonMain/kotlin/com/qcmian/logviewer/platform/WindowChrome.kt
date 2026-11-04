package com.qcmian.logviewer.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon

/**
 * 桌面端窗口引用（java.awt.Window），由 desktopApp 入口注入；
 * 其他平台恒为 null。
 */
val LocalAppWindow = staticCompositionLocalOf<Any?> { null }

/** 左右调整大小光标（表格列宽拖拽用）；无平台对应时回退 Crosshair */
expect val colResizeIcon: PointerIcon

/**
 * 可拖拽移动窗口的区域（Chrome 式标题栏：Tab 行空白处拖拽移动窗口）。
 * - 桌面：实现 AWT 窗口拖拽
 * - 其他平台：普通容器
 */
@Composable
expect fun WindowDragRegion(modifier: Modifier = Modifier, content: @Composable () -> Unit)

/**
 * macOS：左侧需为系统交通灯（关闭/最小化/最大化）预留空间
 */
expect val windowTrafficLightsInset: Boolean
