package com.qcmian.clipper.desktop.viewmodel

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.platform.macos.MacStatusItem
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.desktop.domain.APPLIED_SIZE_HISTORY
import com.qcmian.clipper.desktop.domain.RESIZE_SETTLE_MILLIS
import com.qcmian.clipper.desktop.domain.RESIZE_TOLERANCE_DP
import com.qcmian.clipper.desktop.domain.WINDOW_SLIDE_MILLIS
import com.qcmian.clipper.desktop.domain.WINDOW_SLIDE_STEP_MILLIS
import com.qcmian.clipper.desktop.domain.autoWindowSize
import com.qcmian.clipper.desktop.domain.constrained
import com.qcmian.clipper.desktop.domain.contentWidthOf
import com.qcmian.clipper.desktop.domain.cursorAnchor
import com.qcmian.clipper.desktop.domain.minimumWindowSizeOf
import com.qcmian.clipper.desktop.domain.nearlyEquals
import com.qcmian.clipper.desktop.domain.resolvePosition
import com.qcmian.clipper.desktop.domain.screenBounds
import com.qcmian.clipper.desktop.domain.slideoutWidthOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * 窗口几何：位置与尺寸的计算、预览停靠侧、用户拖拽与最小尺寸。
 *
 * 它持有全部几何状态（锚点、上一次应用的几何、程序自己设过的尺寸历史、拖拽静默期），
 * 并把这些字段从 ViewModel 里收拢到一处——原 `DesktopShellViewModel` 里耦合最深的就是这一块。
 *
 * 对外只有三个观察入口（[observeWindowGeometry] / [observeMinimumWindowSize] /
 * [observeUserResize]）与一个命令（[moveToCursor]）；[applyWindowGeometry] 是它们共同落点。
 * 面板显隐、预览开关、内容高度变化都不直接调用它——它们只改 [state] 或偏好，由这里的观察者
 * 自己收敛，因此窗口几何永远只有一个「算-应用」路径。
 *
 * @param openedByTray 本次显示是否由托盘触发；托盘呼出时锚点固定在菜单栏图标上
 *   （见 [placementPreference]）。
 */
internal class WindowGeometryController(
    private val state: MutableStateFlow<DesktopShellUiState>,
    private val repository: ClipboardRepository,
    /** 由宿主创建、交给本类读写的窗口状态（位置 / 尺寸）。 */
    private val windowState: WindowState,
    /**
     * 把位置与尺寸一次性应用到窗口上。
     *
     * 必须一次调用完成：Compose 自己的窗口实现把它拆成 `setSize` + `setLocation` 两次原生
     * 调用，而预览停靠左侧时窗口要同时「左移」和「变宽」（右边缘不动，主列表才停在原地），
     * 两次调用之间的中间帧会被系统画出来——整个窗口左右闪一下。宿主改为一次 `setBounds`。
     */
    private val applyBounds: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    /**
     * 设置窗口允许的最小尺寸（AWT `window.minimumSize`）。
     *
     * 手动拖拽窗口边缘时由框架读它拦下收缩（`UndecoratedWindowResizer`），因此这里算的
     * 就是内容区的下限（预览并排时再加上滑出面板），见 [minimumWindowSizeOf]。
     */
    private val applyMinimumSize: (width: Int, height: Int) -> Unit,
    /** 窗口几何过渡（[geometryJob]）运行在这里。 */
    private val scope: CoroutineScope,
    private val openedByTray: () -> Boolean,
) {
    /**
     * 程序自己最近应用过的窗口尺寸（新的在前）。
     *
     * 不能只记「最后那一个」：尺寸通知会滞后于程序的最新一次设定。预览打开 / 收起、内容高度
     * 变化、偏好改动都可能在几帧内连着改几次几何，第 N 次的尺寸通知常常在第 N+1 次之后才到，
     * 此时最后值已经是第 N+1 次的了——这条「自己造成的」通知会被误判成用户在拖窗口边缘，几何
     * 更新随之冻结（见 [userIsResizing]），表现为窗口不再跟随设置。
     */
    private val recentAppliedSizes = ArrayDeque<DpSize>()

    /** 应用自己最后设定过的窗口几何（位置 + 尺寸），用来跳过重复的原生调用。 */
    private var lastAppliedBounds: Rectangle? = null

    /** 最后**落到原生窗口上**的几何。预览滑出 / 收回的过渡从这里开始插值，见 [applyWindowBounds]。 */
    private var lastNativeBounds: Rectangle? = null

    /** 正在跑的窗口几何过渡（位置 / 宽度逐帧插值）；新目标到来时取代上一次。 */
    private var geometryJob: Job? = null

    /** 应用自己最后设定过的窗口最小尺寸，用来跳过重复的原生调用。 */
    private var lastAppliedMinimumSize: DpSize? = null

    /**
     * 用户正在拖动窗口边缘时的「静默期」截止时刻。拖动是一个连续过程，期间不接受任何
     * 程序化的尺寸改动，否则会和用户的手抢同一个窗口。
     */
    private var userResizeUntil = 0L

    /**
     * 主列表（内容区）左上角的锚点。预览打开时窗口以此为准向一侧展开，因此主列表本身不会移动；
     * 面板隐藏后清空，下次显示时重新取。
     */
    private var contentAnchor: WindowPosition.Absolute? = null

    /**
     * [contentAnchor] 是在「预览宽度为这个值」时定下来的。
     *
     * 预览停靠左侧时，窗口左边缘由「锚点 − 滑出宽度」推出：预览宽度一变，窗口就会跟着平移。
     * 而拖动分隔条只该改变两个面板的划分（见 `HistoryScreen.draggedPreviewWidth`），窗口一帧
     * 都不该动——因此按这个差值把锚点挪过去（见 [applyWindowGeometry]），两者必须一起更新。
     * `null` 表示锚点已失效（面板隐藏或尚未摆放）。
     */
    private var anchorPreviewWidth: Int? = null

    /** 上一次取锚点所用的位置偏好；变化时要重新取。 */
    private var lastPlacementSignature: Pair<PopupPosition, Int>? = null

    /**
     * 面板已稳定显示时再按热键：把窗口移到鼠标处。
     *
     * 只重设锚点，位置与尺寸一起交给 [applyWindowGeometry]（它对同一个锚点算尺寸、再把窗口夹进
     * 屏幕）。刻意**不**在这里用「当前窗口尺寸」摆放窗口：那样算出来的 y 会被当前高度顶高，而它
     * 紧接着又被记成锚点——高度于是等于自己的旧值，鼠标往下移也不会变小，只能往上长。
     */
    fun moveToCursor() {
        val settings = repository.settings.value
        val cursor = cursorAnchor(settings.popupScreen) as WindowPosition.Absolute
        // 同步「内容区锚点」：否则下次切换预览会按旧锚点摆放，窗口会跳回去。
        contentAnchor = if (state.value.previewOnLeft) {
            WindowPosition.Absolute(cursor.x + slideoutWidthOf(settings), cursor.y)
        } else {
            cursor
        }
        anchorPreviewWidth = settings.previewWidth
        lastPlacementSignature = Pair(placementPreference(settings), settings.popupScreen)
        applyWindowGeometry(state.value, settings)
    }

    /**
     * 当前这次摆放实际使用的位置偏好：点托盘呼出时直接锚定菜单栏图标（点托盘那一刻光标就在
     * 图标上，「光标位置」与「菜单栏图标」是同一个落点）。
     *
     * 锚点签名必须用它而不是 `settings.popupPosition`：签名只用来判断「锚点是否还有效」，
     * 托盘呼出时两者并不相等，用错会让下一次几何重算重新取锚点，把窗口拉回菜单栏。
     */
    private fun placementPreference(settings: AppSettings): PopupPosition =
        if (openedByTray()) PopupPosition.MENU_BAR else settings.popupPosition

    /**
     * 窗口允许的最小尺寸（内容区下限 + 预览开着时的滑出面板），交给 AWT 的 `window.minimumSize`。
     *
     * 用户拖拽窗口边缘时由框架读它拦下收缩（`UndecoratedWindowResizer` 对左 / 上两侧做了
     * `coerceAtLeast(window.minimumSize)`），所以下限要在拖拽开始之前就已经设好。宽度由
     * [minimumWindowSizeOf] 给出（预览开着时含滑出面板），高度用界面报上来的
     * [DesktopShellUiState.minimumHeight]。值没变时不产生原生调用。
     *
     * 下限**变小**时额外补一次几何：之前可能有一次尺寸请求被旧下限夹住（系统接受的是夹过之后
     * 的值，而 [lastAppliedBounds] 记的是请求值，于是再也去重不掉），清掉记录再请求一次，
     * 窗口才收得回来。
     */
    suspend fun observeMinimumWindowSize() {
        combine(
            state,
            // 预览开关决定下限要不要含滑出面板（见 [minimumWindowSizeOf]）。
            repository.settings.map { it.previewOpen }.distinctUntilChanged(),
        ) { snapshot, previewOpen -> minimumWindowSizeOf(snapshot.minimumHeight, previewOpen) }
            .distinctUntilChanged()
            .collect { size ->
                val previous = lastAppliedMinimumSize
                if (size == previous) return@collect
                lastAppliedMinimumSize = size
                applyMinimumSize(size.width.value.roundToInt(), size.height.value.roundToInt())
                if (previous != null && size.height < previous.height) {
                    lastAppliedBounds = null
                    applyWindowGeometry(state.value, repository.settings.value)
                }
            }
    }

    /**
     * 窗口几何：位置与尺寸一起算、一起写。
     *
     * 两者必须落在同一帧。预览停靠在左侧时窗口要同时「左移」和「变宽」（右边缘不动，主列表
     * 才停在原地）；若位置与尺寸由两次独立的写入驱动，界面就会先看到「位置已移、宽度未变」
     * 或「宽度已变、位置未移」的中间状态——主列表于是左右抖一下。
     *
     * 触发来源涵盖：面板显示 / 隐藏、预览开关、内容高度、偏好设置，以及窗口位置本身的变化。
     * 位置只当**触发键**用，不参与计算：尺寸由内容与偏好决定，位置由锚点决定，因此每次重算都
     * 收敛到同一个结果（[applyWindowBounds] 再去重），顺带保证窗口被外部挪走后仍会被拉回锚点。
     */
    suspend fun observeWindowGeometry() {
        combine(
            state,
            // 直接用仓库里的偏好，而不是 `panel.hostUiState` 投影：投影由 `App` 在组合里用
            // `SideEffect` 写入，于是「改设置」到「窗口跟上」要多等一帧。拖动预览分隔条时每帧
            // 都在改设置，那一帧的差值会让预览抢在窗口前面变宽、主列表替它吸收差额——两边的
            // 尺寸一起抖（与 `GlobalHotKeyController.observeShortcut` 是同一个坑）。
            repository.settings,
            snapshotFlow { windowState.position },
        ) { snapshot, settings, position ->
            Triple(snapshot, settings, position)
        }.collect { (snapshot, settings, _) ->
            applyWindowGeometry(snapshot, settings)
        }
    }

    /**
     * 按内容、偏好与屏幕空间算出窗口的位置与尺寸，并一次写入。
     *
     * 主列表（内容区）的锚点先定下来，预览再以它为基准向一侧展开——默认在右侧（窗口向右
     * 加宽），主列表右边缘放不下时改到左侧（窗口向左加宽），因此预览永远不会盖住主列表，
     * 主列表本身也不会移动。
     */
    private fun applyWindowGeometry(
        snapshot: DesktopShellUiState,
        settings: AppSettings,
    ) {
        if (!snapshot.windowVisible) {
            // 隐藏后下次显示要重新取锚点，否则会把上一次的旧位置带过来。
            contentAnchor = null
            anchorPreviewWidth = null
            lastPlacementSignature = null
            return
        }
        // 用户正在拖边缘：这一刻以他的手为准。程序化改几何会和拖动互相打架
        // （两边都在 setSize / setLocation），拖左边框时尤其明显——位置同时也在变。
        // 手停下来后由 [observeUserResize] 补一次。
        if (userIsResizing()) return

        val contentWidth = contentWidthOf(settings)
        val desiredSlideout = slideoutWidthOf(settings)
        val bounds = screenBounds(settings.popupScreen)

        val preferred = placementPreference(settings)

        // 只在「重新显示」或「位置偏好变化」时取锚点；仅切换预览时沿用旧锚点，
        // 主列表不会跟着鼠标或上次的窗口尺寸跳动。
        val signature = Pair(preferred, settings.popupScreen)
        var anchor = contentAnchor?.takeIf { signature == lastPlacementSignature }
            ?: (
                resolvePosition(
                    position = preferred,
                    size = DpSize(contentWidth, windowState.size.height),
                    screenIndex = settings.popupScreen,
                    statusItem = MacStatusItem.currentAnchor(),
                ) as WindowPosition.Absolute
                ).also {
                    lastPlacementSignature = signature
                    anchorPreviewWidth = settings.previewWidth
                }

        // 预览停靠左侧时，窗口左边缘是「锚点 − 滑出宽度」推出来的：预览宽度一变，窗口就会
        // 跟着平移。而拖动分隔条只该改变两个面板的划分（见 `HistoryScreen.draggedPreviewWidth`），
        // 窗口一帧都不该动。锚点本来就表示「主列表左上角在哪」，预览变宽时主列表的左边缘正是
        // 往右让出这么多，因此把它按同样的差值挪过去——算出来的窗口位置与改动前完全相同，
        // [applyWindowBounds] 直接去重，原生窗口一动不动。
        //
        // 用 [snapshot] 里的停靠侧判断（那是上一次布局的结论）：本次停靠侧要等锚点定下来才能算，
        // 而锚点正是这里要先挪的东西。预览开关、换屏幕、换位置偏好都会重取锚点，那些情况下这次
        // 挪动作用在即将被丢弃的旧锚点上，没有影响。
        if (snapshot.previewOnLeft) {
            val reference = anchorPreviewWidth
            if (reference != null && reference != settings.previewWidth) {
                anchor = WindowPosition.Absolute(
                    anchor.x + (settings.previewWidth - reference).dp,
                    anchor.y,
                )
                anchorPreviewWidth = settings.previewWidth
            }
        }
        contentAnchor = anchor

        // 预览停靠在哪一侧：优先右侧，右侧放不下时改左侧，两侧都放不下就退回覆盖层。
        //
        // 判断的是「窗口整个（主列表 + 滑出面板）放不放得进屏幕」，而不是「预览放不放得进
        // 列表旁边」：主列表是跟着窗口走的，只要窗口被 `constrained` 夹回屏幕内，主列表就会
        // 跟着平移——表现为「预览先盖在主列表原来的位置上，主列表被推到一边」，收起时再推回来。
        //
        // 余量按「能不能放下**最小**宽度的预览」算，实际给出去的宽度再夹进这个余量：面板宽度
        // 超过这一侧的余量时（用户把窗口拖到了屏幕边上），它只会在边缘停住，而不是翻到另一侧
        // ——翻侧会让整个窗口跳到锚点另一边，主列表跟着平移。
        val roomRight = (bounds.x + bounds.width - anchor.x.value - contentWidth.value).dp
            .coerceAtLeast(0.dp)
        val roomLeft = (anchor.x.value - bounds.x).dp.coerceAtLeast(0.dp)
        val fitsRight = roomRight >= Popup.minimumSlideoutWidth
        // 预览开没开直接读设置，**不**从界面上报：这里它决定窗口要不要为预览让位，这一份一旦
        // 停在「开着」，窗口就再也收不回来——预览早已收起，窗口右侧却留着预览那一块空白。
        val previewOpen = settings.previewOpen
        // 桌面端**永远**并排，不走覆盖层（`overlays` 恒为 false，见 [DesktopShellUiState]）：
        // 窗口宽度由内容 + 滑出面板决定，放不下时把滑出宽度按这一侧的余量夹小（下面 `slideout`），
        // 而不是让预览盖在主列表上——覆盖层只要在过渡里出现一帧，看起来就是「预览整块盖住了
        // 列表」，而并排布局里卡片与列表各占一边，任何一帧都不可能互相遮盖。
        val previewOnLeft = previewOpen && !fitsRight

        // 尺寸由内容与偏好决定（预览并排时额外容纳滑出面板）；位置以锚点为基准，预览停靠左侧时
        // 窗口向左展开——两者用的必须是**同一个**滑出宽度，否则窗口边缘和面板会差着一段。
        //
        // 高度截的是「锚点下方还剩多少」，不是窗口当前的 y：那样会让「窗口有多高」和「窗口在哪」
        // 互相追赶（见 `autoWindowSize`）。实在放不下时由下面的 `constrained` 把窗口上移。
        val slideout = if (previewOpen) {
            desiredSlideout.coerceAtMost(if (previewOnLeft) roomLeft else roomRight)
        } else {
            null
        }
        // 界面拿它当分隔条的拖动上限（与「窗口内剩余空间」取较小值）：必须与这里真正会接受的
        // 宽度同源，否则拖动会报出一个收不下的值，松手又被夹回来——看起来就是「拖了没用」。
        val maxPreviewWidth =
            ((if (previewOnLeft) roomLeft else roomRight) - Popup.previewDividerWidth)
                .coerceAtLeast(Popup.minimumPreviewWidth)
        // 屏幕放不下时，把设置里的预览宽度也收敛掉——只夹窗口是不够的：界面里的面板会照设置值
        // 继续变宽，多出来的部分只能挤主列表；而设置值一路涨到拖动上限之后，用户往回拖一大段都
        // 不见效（一段死区）。夹到同一个值，分隔条就会在屏幕边缘自然停住，与窗口被屏幕夹住是
        // 同一件事。
        if (slideout != null && slideout < desiredSlideout) {
            val capped = (slideout - Popup.previewDividerWidth).value.roundToInt()
                .coerceAtLeast(Popup.minimumPreviewWidth.value.toInt())
            if (capped < settings.previewWidth) {
                repository.setSettings(settings.copy(previewWidth = capped))
            }
        }
        val target = autoWindowSize(
            settings = settings,
            slideoutWidth = slideout,
            preferredHeight = snapshot.preferredHeight,
            minimumHeight = snapshot.minimumHeight,
            anchorY = anchor.y.value.toInt(),
            bounds = bounds,
        )
        val windowX = if (previewOnLeft) {
            anchor.x.value - (slideout ?: 0.dp).value
        } else {
            anchor.x.value
        }

        // 预览是不是（还）在窗口里：设置说开着；或者这次收起的过渡还没把窗口收回去（原生宽度仍
        // 多着一段）——那几帧里卡片要继续占着槽位，主列表才不会随窗口一起挪。用户自己拖窗口
        // 边缘时窗口也会比内容宽，那种情况不算（否则已经关闭的预览会凭空冒出来）。
        val windowReady = previewOpen || (
            !userIsResizing() &&
                (lastNativeBounds?.width ?: 0) >
                target.width.value.roundToInt() + RESIZE_TOLERANCE_DP.toInt()
            )
        state.update {
            if (it.previewOnLeft == previewOnLeft &&
                it.previewWindowReady == windowReady &&
                it.maxPreviewWidth == maxPreviewWidth
            ) {
                it
            } else {
                it.copy(
                    previewOnLeft = previewOnLeft,
                    previewWindowReady = windowReady,
                    maxPreviewWidth = maxPreviewWidth,
                )
            }
        }

        rememberAppliedSize(target)
        // 位置与尺寸必须一次应用（见 [applyBounds]），`windowState` 也由 [applyWindowBounds] 一并
        // 对齐——不写它会留下一帧「尺寸已新、位置仍旧」的窗口，理由见那里。
        val placed = constrained(
            x = windowX.toInt(),
            y = anchor.y.value.toInt(),
            size = target,
            bounds = bounds,
        )
        applyWindowBounds(
            x = placed.x.value.roundToInt(),
            y = placed.y.value.roundToInt(),
            size = target,
        )
    }

    /** 应用一次窗口几何；与上次应用过的完全相同就跳过，避免每次状态变化都动一次原生窗口。 */
    private fun applyWindowBounds(x: Int, y: Int, size: DpSize) {
        val bounds = Rectangle(
            x,
            y,
            size.width.value.roundToInt(),
            size.height.value.roundToInt(),
        )
        if (lastAppliedBounds == bounds) return
        val from = lastNativeBounds
        lastAppliedBounds = bounds
        geometryJob?.cancel()

        // 首次摆放、或高度也变了：一次到位。高度由内容决定（条目增减），逐帧插值只会让窗口
        // 长得比内容慢；首次摆放时窗口本来就在进场，也不该滑一下。`WINDOW_SLIDE_MILLIS` 置 0
        // 即关掉过渡，退回一次到位。
        if (from == null || from.height != bounds.height || from.width == bounds.width ||
            WINDOW_SLIDE_MILLIS <= 0L
        ) {
            applyNativeBounds(bounds)
            return
        }

        // 只有「预览滑出 / 收回」这一类变化（位置与宽度一起变、高度不变）才逐帧插值，见
        // [WINDOW_SLIDE_MILLIS]：一次到位会留下系统按旧内容补位的那一帧，看起来就是预览整块
        // 盖在主列表上。
        geometryJob = scope.launch {
            val startedAt = System.nanoTime()
            try {
                while (true) {
                    val progress = (
                        (System.nanoTime() - startedAt) / 1_000_000f / WINDOW_SLIDE_MILLIS
                        ).coerceIn(0f, 1f)
                    applyNativeBounds(
                        Rectangle(
                            from.x + ((bounds.x - from.x) * progress).roundToInt(),
                            bounds.y,
                            from.width + ((bounds.width - from.width) * progress).roundToInt(),
                            bounds.height,
                        ),
                    )
                    if (progress >= 1f) return@launch
                    delay(WINDOW_SLIDE_STEP_MILLIS)
                }
            } catch (cancellation: CancellationException) {
                // 被新的目标取代：停在当前帧，由新的那次过渡接着走。
                throw cancellation
            } catch (error: Throwable) {
                // 过渡本身出岔子也不能让窗口停在半路：直接把目标摆上。
                applyNativeBounds(bounds)
            }
        }
    }

    /**
     * 真正落到原生窗口上的一次应用，并把 `windowState` 对齐到同一组值。
     *
     * 对齐 state 是因为 Compose 自己的窗口实现会把 state 里的尺寸与位置**分两次**应用到窗口
     * 上（先尺寸、后位置），而它读到的这两个值来自窗口的通知，到达有先后：缩放通知先到、移动
     * 通知后到。左侧停靠时窗口要同时「左移」和「变宽」，于是它可能在「尺寸已新、位置仍旧」的
     * 那一帧按旧位置再摆一次窗口。我们把 state 一并对齐后，它那两次应用都是空操作。
     *
     * 每一帧都要记进 [recentAppliedSizes]：插值的中间尺寸也是**程序自己**设的，漏记的话会被
     * 当成「用户在拖窗口边缘」（见 [observeUserResize]）。
     */
    private fun applyNativeBounds(bounds: Rectangle) {
        lastNativeBounds = bounds
        applyBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        val size = DpSize(bounds.width.dp, bounds.height.dp)
        rememberAppliedSize(size)
        windowState.size = size
        windowState.position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp)
    }

    /** 记下这次由程序应用的尺寸；保留一小段历史的原因见 [recentAppliedSizes]。 */
    private fun rememberAppliedSize(size: DpSize) {
        recentAppliedSizes.addFirst(size)
        while (recentAppliedSizes.size > APPLIED_SIZE_HISTORY) recentAppliedSizes.removeLast()
    }

    /** 这个尺寸是不是程序自己刚设过的（[RESIZE_TOLERANCE_DP] 之内）。 */
    private fun wasAppliedByProgram(size: DpSize): Boolean =
        recentAppliedSizes.any { size.nearlyEquals(it) }

    /**
     * 用户拖动窗口边缘改变尺寸：手停下来之后，把最终尺寸记为「自定义尺寸」。
     *
     * 拖动过程中每来一个尺寸通知就写一次偏好，会让整棵界面（含历史搜索）每帧重算，
     * 这正是拖动卡顿的根源。这里按「尺寸持续 [RESIZE_SETTLE_MILLIS] 不变」来判定手已松开，
     * 于是整段拖动只落盘一次；[collectLatest] 负责在下一个尺寸到来时取消上一次等待。
     */
    suspend fun observeUserResize() {
        snapshotFlow { windowState.size }
            .collectLatest { size ->
                // 命中程序最近设过的某个尺寸 —— 这次通知是自己造成的，直接忽略。
                // 判据必须是「最近若干次」而不是「最后一次」，理由见 [recentAppliedSizes]。
                if (wasAppliedByProgram(size)) return@collectLatest

                userResizeUntil = System.currentTimeMillis() + RESIZE_SETTLE_MILLIS
                delay(RESIZE_SETTLE_MILLIS)

                val settings = repository.settings.value
                // 预览打开时拖的是整窗宽度，写回前要减掉滑出面板：自定义宽度始终表示主列表宽度。
                val contentWidth = if (settings.previewOpen) {
                    size.width - slideoutWidthOf(settings)
                } else {
                    size.width
                }
                rememberAppliedSize(size)
                // 窗口已经不在「应用设定的几何」上了：清掉记录，让随后的补正一定重新应用一次
                // （否则算出来的几何恰好等于旧值就会被当成「已经应用过」而跳过）。
                lastAppliedBounds = null
                // 用户可能把窗口拖到了别处（拖左边框加宽时窗口位置就会变）：锚点先跟着走。
                // 必须在写设置之前——设置一变，[observeWindowGeometry] 可能立刻按旧锚点把窗口
                // 拉回去，等于把刚才的拖动撤销掉。
                rememberContentAnchor()
                repository.setSettings(
                    settings.copy(
                        // 内容区下限：右 / 下两侧的拖拽框架不读 `minimumSize`，落盘时收敛一次，
                        // 最终尺寸同样不会低于下限（见 [minimumWindowSizeOf]）。这里用的是
                        // 「划分里的下限」[Popup.minimumSplitContentWidth]（比窗口下限小）：
                        // 预览开着时拖窄窗口，同时被挤窄的是主列表，按窗口下限收敛会把窗口又顶宽。
                        // 高度用的是界面报上来的窗口下限，滑动区因此总是留着「剪贴板为空时」那一档。
                        customWindowWidth = contentWidth.value.roundToInt()
                            .coerceAtLeast(Popup.minimumSplitContentWidth.value.toInt()),
                        customWindowHeight = size.height.value.roundToInt()
                            .coerceAtLeast(state.value.minimumHeight.value.roundToInt()),
                    ),
                )

                userResizeUntil = 0L
                // 用户拖出的尺寸仍要受屏幕约束（例如不能盖住 Dock），补一次程序化几何。
                applyWindowGeometry(
                    snapshot = state.value,
                    settings = repository.settings.value,
                )
            }
    }

    /** 以当前窗口位置更新「内容区锚点」；面板尚未摆放时不动。 */
    private fun rememberContentAnchor() {
        if (contentAnchor == null) return
        val position = windowState.position as? WindowPosition.Absolute ?: return
        val settings = repository.settings.value
        contentAnchor = if (state.value.previewOnLeft) {
            WindowPosition.Absolute(position.x + slideoutWidthOf(settings), position.y)
        } else {
            position
        }
        anchorPreviewWidth = settings.previewWidth
        lastPlacementSignature = Pair(placementPreference(settings), settings.popupScreen)
    }

    /** 用户是否正在手动调整窗口尺寸（含刚停下的一小段静默期）。 */
    private fun userIsResizing(): Boolean = System.currentTimeMillis() < userResizeUntil
}
