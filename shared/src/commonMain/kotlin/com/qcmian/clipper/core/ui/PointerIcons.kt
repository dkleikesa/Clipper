package com.qcmian.clipper.core.ui

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * 左右拖拽（水平调整尺寸）时用的鼠标指针。
 *
 * Compose 的公共 [PointerIcon] 只有 `Default` / `Text` / `Hand` / `Crosshair` 四种，没有
 * 「左右箭头」这一类——而它恰恰是系统分隔条通用的暗示：用户看到指针变成 ↔ 就知道这里能拖。
 * 因此这里按目标各自提供形状。
 *
 * 对模块外可见（**不是 `internal`**）：开发者工具的分隔条（`:devTools`）也要用它，而 `internal`
 * 是按模块可见的，会把跨模块的调用方挡在外面；这条指针本来就是「分隔条通用」的图形词汇。
 */
expect val HorizontalResizePointerIcon: PointerIcon

/** 上下拖拽（垂直调整尺寸）时用的鼠标指针，形状是 ↕。 */
expect val VerticalResizePointerIcon: PointerIcon
