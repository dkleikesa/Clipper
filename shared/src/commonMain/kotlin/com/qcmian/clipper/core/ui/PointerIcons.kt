package com.qcmian.clipper.core.ui

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * 左右拖拽（水平调整尺寸）时用的鼠标指针。
 *
 * Compose 的公共 [PointerIcon] 只有 `Default` / `Text` / `Hand` / `Crosshair` 四种，没有
 * 「左右箭头」这一类——而它恰恰是系统分隔条通用的暗示：用户看到指针变成 ↔ 就知道这里能拖。
 * 因此这里按目标各自提供形状。
 */
internal expect val HorizontalResizePointerIcon: PointerIcon
