package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect

/** 预览卡片逐帧揭示的时长。 */
private const val PreviewAnimationMillis = 180

/**
 * 预览卡片的进场动画：从**主面板那一侧**逐帧揭示出来。
 *
 * 刻意不做水平位移，也不用 `expandHorizontally`——后两者都会让卡片在动画期间的布局宽度与最终
 * 值不同：位移方案里内容滑进来、槽位先空着，展开方案则会把布局宽度从 0 拉起来，主列表跟着从
 * 「整窗宽」缩到最终宽度，整段过程都在重排。这里卡片的布局宽度从一开始就是最终宽度，内容原地
 * 不动，只是被裁剪着逐帧露出：
 *
 * - 主列表的宽度全程不变（槽位从第一帧起就已占住）；
 * - 揭示方向是「主面板 → 窗口外缘」：停靠右侧时自左向右推开，停靠左侧时自右向左；
 * - 和窗口变宽是同一个动作。桌面宿主的加宽是瞬时的（原生 `setBounds` 一次到位，逐帧改窗口
 *   尺寸会拖垮界面，见 `DesktopShellViewModel.applyBounds`），卡片若再花 180ms 滑进来，就会
 *   看成「背景先撑开、内容后滑入」两段。
 *
 * 内容不做淡入：淡入会让文字一点点浮现，看起来像「整块在闪」。
 *
 * 关闭不做退场动画是有意为之：桌面宿主收到「预览已关闭」后会把窗口收窄，卡片若还占着布局，
 * 就会和收窄中的窗口抢同一段宽度。现在卡片立即让出槽位，槽位由 `PreviewSlotMetrics.slotReserved`
 * 先占着，等窗口收回来再一起消失——主列表在整段过程中宽度不变。
 *
 * 三处卡片由同一个 `PreviewPlacement` 驱动，同一时刻只有一处可见，因此一次打开只会跑一次进场动画。
 */
@Composable
internal fun AnimatedPreviewCard(
    visible: Boolean,
    onLeft: Boolean,
    content: @Composable () -> Unit,
) {
    // 关闭是瞬时的：立刻从组合里移除，槽位交给 `slotReserved`。
    if (!visible) return

    // 进度只在绘制阶段读取，动画的每一帧因此只触发重绘、不触发重组。
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(durationMillis = PreviewAnimationMillis))
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .drawWithContent {
                val revealed = size.width * reveal.value
                clipRect(
                    left = if (onLeft) size.width - revealed else 0f,
                    top = 0f,
                    right = if (onLeft) size.width else revealed,
                    bottom = size.height,
                ) {
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        content()
    }
}
