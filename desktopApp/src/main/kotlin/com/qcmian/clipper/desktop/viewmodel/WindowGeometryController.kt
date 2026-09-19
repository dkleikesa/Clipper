package com.qcmian.clipper.desktop.viewmodel

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
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
import com.qcmian.clipper.desktop.domain.PREVIEW_TOGGLE_FRAME_MILLIS
import com.qcmian.clipper.desktop.domain.PREVIEW_TOGGLE_MILLIS
import com.qcmian.clipper.desktop.domain.RESIZE_SETTLE_MILLIS
import com.qcmian.clipper.desktop.domain.RESIZE_TOLERANCE_DP
import com.qcmian.clipper.desktop.domain.autoWindowSize
import com.qcmian.clipper.desktop.domain.constrained
import com.qcmian.clipper.desktop.domain.contentWidthOf
import com.qcmian.clipper.desktop.domain.cursorAnchor
import com.qcmian.clipper.desktop.domain.minimumWindowSizeOf
import com.qcmian.clipper.desktop.domain.nearlyEquals
import com.qcmian.clipper.desktop.domain.resolvePosition
import com.qcmian.clipper.desktop.domain.screenBounds
import com.qcmian.clipper.desktop.domain.slideoutWidthOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * 窗口几何：位置与尺寸的计算、预览停靠侧、用户拖拽与最小尺寸。
 *
 * 它持有全部几何状态（锚点、上一次应用的几何、程序自己设过的尺寸历史、拖拽静默期），
 * 并把这些字段从 ViewModel 里收拢到一处——原 `DesktopShellViewModel` 里耦合最深的就是这一块。
 *
 * 对外只有两个观察入口（[observeWindowGeometry] / [observeUserResize]）与一个命令
 * （[moveToCursor]）；[applyWindowGeometry] 是它们共同的落点。面板显隐、预览开关、内容高度变化
 * 都不直接调用它——它们只改 [state] 或偏好，由这里的观察者自己收敛，因此窗口几何永远只有一个
 * 「算-应用」路径。
 *
 * 唯一的例外是**预览开关**：那一次几何不一次到位，而是由 [animateBoundsTo] 逐帧推过去。界面里
 * 的预览卡不做动画、只跟着窗口走，因此这次窗口动画就是「面板被让出来」的过程本身（见
 * `HistoryScreen` 的预览段）。
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
     * 就是内容区的下限（宽度恒为内容区下限，不含预览那一段，见 [minimumWindowSizeOf]）。
     */
    private val applyMinimumSize: (width: Int, height: Int) -> Unit,
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

    /** 应用自己最后设定过的窗口最小尺寸，用来跳过重复的原生调用。 */
    private var lastAppliedMinimumSize: DpSize? = null

    /**
     * 用户正在拖动窗口边缘时的「静默期」截止时刻。拖动是一个连续过程，期间不接受任何
     * 程序化的尺寸改动，否则会和用户的手抢同一个窗口。
     */
    private var userResizeUntil = 0L

    /**
     * 上一次落地几何时预览是开还是关；`null` 表示「本次显示还没落地过」（隐藏时清空）。
     *
     * 它是「这次几何计算要不要做开关动画」的唯一依据：两者不等，说明这一趟几何正是预览开关
     * 引起的，窗口宽度就该一帧帧地让出来（见 [animateBoundsTo]）。刚显示的那一次是 `null`：
     * 开关在这次呼出里并没有变，窗口一次到位，不为了「打开面板」多跑一段动画。
     */
    private var appliedPreviewOpen: Boolean? = null

    /**
     * 开关动画跑完后补发的布局结论（停靠侧 + 拖动上限，见 [publishLayout]）。
     *
     * 只有**收起**会欠这一笔：窗口停靠在左侧时，窗口左边缘就是「锚点 − 滑出宽度」，预览关掉之后
     * 窗口还要逐帧收回左边那一段。结论先发出去，界面就会在窗口还宽着的那几帧按「预览在右」排版
     * ——列表当场平移一个滑出宽度。打开则相反，必须**先**发布（见 [applyWindowGeometry]）。
     */
    private var deferredLayout: Pair<Boolean, Dp>? = null

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
        // 挪窗口不动预览开关，因此这里不会带出动画；真带出来了（开关刚好在切）也只是少一段过渡
        // ——直接落地，窗口一定落在算出来的位置上，不会停在中间尺寸。
        applyWindowGeometry(state.value, settings)?.let(::applyWindowBounds)
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
     * 应用窗口下限；与上次设过的值相同就是空操作。
     *
     * 它只在 [applyWindowGeometry] 里、**先于**尺寸跑一次：下限是原生窗口的一个属性，值一旦大于
     * 当前窗口，系统会立刻把窗口撑到下限——那是一次程序没安排的原生 resize。放在尺寸之前，这次
     * 撑大与随后的 `setBounds` 落在同一条调用里，不会单独露出来。
     *
     * 宽度是常数（见 [minimumWindowSizeOf]），只有高度会随界面测量的内容高度变。
     */
    private fun applyMinimumSizeIfNeeded(size: DpSize) {
        if (size == lastAppliedMinimumSize) return
        lastAppliedMinimumSize = size
        applyMinimumSize(size.width.value.roundToInt(), size.height.value.roundToInt())
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
            // 开关动画同步跑在这条收集里（不另起协程）：一次开关的那几帧内不会有第二个几何计算
            // 插进来跟它抢窗口，期间积压的状态变化会在动画跑完后按顺序再算一遍——那些计算要么被
            // [applyWindowBounds] 去重，要么就是真正需要的新几何（例如动画那几帧里复制进来的新
            // 条目改变了内容高度）。
            val animation = applyWindowGeometry(snapshot, settings)
            if (animation != null) {
                animateBoundsTo(animation)
                // 收起时押后的布局结论，等窗口收完再发（见 [deferredLayout]）。
                deferredLayout?.let { (onLeft, maxWidth) -> publishLayout(onLeft, maxWidth) }
            }
        }
    }

    /**
     * 按内容、偏好与屏幕空间算出窗口的位置与尺寸，并写进窗口。
     *
     * 主列表（内容区）的锚点先定下来，预览再以它为基准向一侧展开——默认在右侧（窗口向右
     * 加宽），主列表右边缘放不下时改到左侧（窗口向左加宽），因此预览永远不会盖住主列表，
     * 主列表本身也不会移动。
     *
     * 返回值是**需要动画时**窗口该落到的位置：非 `null` 表示这次是预览开关引起的几何变化、
     * 并且还没有落地，调用方要么交给 [animateBoundsTo] 一帧帧过去（界面里的预览卡就是靠它被
     * 让出来的），要么直接 [applyWindowBounds] 一次到位。返回 `null` 表示已经落地完毕。
     */
    private fun applyWindowGeometry(
        snapshot: DesktopShellUiState,
        settings: AppSettings,
    ): Rectangle? {
        if (!snapshot.windowVisible) {
            // 隐藏后下次显示要重新取锚点，否则会把上一次的旧位置带过来。
            contentAnchor = null
            anchorPreviewWidth = null
            lastPlacementSignature = null
            // 下次显示时窗口一次到位：重新呼出面板不是「切换预览」。
            appliedPreviewOpen = null
            return null
        }
        // 用户正在拖边缘：这一刻以他的手为准。程序化改几何会和拖动互相打架
        // （两边都在 setSize / setLocation），拖左边框时尤其明显——位置同时也在变。
        // 手停下来后由 [observeUserResize] 补一次。
        if (userIsResizing()) return null

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
        // 桌面端**永远**并排：窗口宽度由内容 + 滑出面板决定，放不下时把滑出宽度按这一侧的余量
        // 夹小（下面 `slideout`），而不是让预览盖在主列表上——覆盖层只要在过渡里出现一帧，看
        // 起来就是「预览整块盖住了列表」，而并排布局里卡片与列表各占一边，任何一帧都不可能互相
        // 遮盖。
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

        rememberAppliedSize(target)
        // 位置与尺寸必须一次应用（见 [applyBounds]），`windowState` 也由 [applyWindowBounds] 一并
        // 对齐——不写它会留下一帧「尺寸已新、位置仍旧」的窗口，理由见那里。
        val placed = constrained(
            x = windowX.toInt(),
            y = anchor.y.value.toInt(),
            size = target,
            bounds = bounds,
        )
        // 下限先于尺寸落地（见 [applyMinimumSizeIfNeeded]）：值一旦大于当前窗口，系统会立刻把
        // 窗口撑到下限，排在这里就能与随后的 `setBounds` 落在同一条调用里。
        applyMinimumSizeIfNeeded(minimumWindowSizeOf(snapshot.minimumHeight))

        // Java 的 `Rectangle` 不吃具名参数。
        val placedBounds = Rectangle(
            placed.x.value.roundToInt(),
            placed.y.value.roundToInt(),
            target.width.value.roundToInt(),
            target.height.value.roundToInt(),
        )

        // 预览开关带来的宽度变化是全套几何里幅度最大、也最该被看见的一次，交给调用方做动画
        // （见 [animateBoundsTo]）；其余来源（面板显隐、内容高度、偏好改动、窗口被挪走）一律一次
        // 到位——它们本来就该是「悄悄发生」的，做成动画只会让界面上别的变化跟着晃。
        val togglingPreview = appliedPreviewOpen != null && appliedPreviewOpen != previewOpen
        appliedPreviewOpen = previewOpen
        if (togglingPreview) {
            if (previewOpen) {
                // 打开：停靠侧必须**先**发布。窗口是朝预览那一侧长出去的，列表得立刻按新的一侧
                // 贴住锚点（左侧停靠时贴窗口右边缘），否则它会跟着窗口的左边缘一起平移。
                publishLayout(previewOnLeft, maxPreviewWidth)
            } else {
                // 收起：结论押后到窗口收完再发（见 [deferredLayout]）。
                deferredLayout = Pair(previewOnLeft, maxPreviewWidth)
            }
            return placedBounds
        }
        publishLayout(previewOnLeft, maxPreviewWidth)
        applyWindowBounds(placedBounds)
        return null
    }

    /**
     * 把摆窗口的产物发布给界面：预览停靠在哪一侧、这一侧还能给多宽。
     *
     * 界面算不出这两件事（它们是「窗口整个放不放得进屏幕」的结论），但它们也**不**表示「预览
     * 该不该在画面上」——那由设置本身决定（见 `HistoryScreen` 的预览段）：为预览让位的加宽 / 收回
     * 就是窗口宽度本身，界面跟着窗口走就行。值没变时不产生新状态。
     *
     * 发布的一定是最新结论，因此顺手清掉可能欠着的那一笔（见 [deferredLayout]）：它已经被这次
     * 取代，再发出去就是拿旧值往回写。
     */
    private fun publishLayout(previewOnLeft: Boolean, maxPreviewWidth: Dp) {
        deferredLayout = null
        state.update {
            if (it.previewOnLeft == previewOnLeft && it.maxPreviewWidth == maxPreviewWidth) {
                it
            } else {
                it.copy(previewOnLeft = previewOnLeft, maxPreviewWidth = maxPreviewWidth)
            }
        }
    }

    /**
     * 把窗口从当前位置一帧帧推到 [to]：预览开关的过渡就是它。
     *
     * 界面里的预览卡不做任何动画（见 `HistoryScreen` 的预览段）：它按完整宽度贴着窗口的预览侧
     * 外沿排版，露出多少完全由窗口让出多少决定。因此这里的每一帧 `setBounds` 就是把面板「让」
     * 出来一步——面板边界与窗口边界始终是同一条，不存在「窗口已经到位、卡片还在外面滑」的错位
     * （那正是「从左侧出来的面板整块闪一下」的来源）。
     *
     * 逐帧同步跑在几何观察者那条协程里（见 [observeWindowGeometry]）：不占新协程，也就不会与它
     * 抢窗口。这几帧写下去的尺寸都记进了 [recentAppliedSizes]（见 [applyNativeBounds]），因此它们
     * 以及稍稍滞后到达的通知都不会被当成「用户在拖窗口边缘」——[APPLIED_SIZE_HISTORY] 必须多于
     * 这里的帧数。
     *
     * [userIsResizing] 一旦为真立刻让路：用户已经把窗口边缘按在手里了，动画再改尺寸只会打架，
     * 剩下的几何由 `observeUserResize` 松手后那次计算收尾。
     */
    private suspend fun animateBoundsTo(to: Rectangle) {
        // 起点用「程序最后落地过的几何」。它被清掉（用户刚拖过窗口边缘）说明此刻的尺寸是用户的
        // 手说了算，那就别补动画，一次到位。
        val from = lastAppliedBounds
        if (from == null) {
            applyWindowBounds(to)
            return
        }
        if (from == to) return
        // 步进按固定间隔，**进度按实际经过的时间**算：单帧偶尔比 [PREVIEW_TOGGLE_FRAME_MILLIS]
        // 慢（一次 `setBounds` 触发原生 resize + 整棵界面重排，慢起来不止一帧）时，总时长仍
        // 是 [PREVIEW_TOGGLE_MILLIS]，不会跟着被拖长成「帧数 × 实际帧耗」。
        val startedAt = System.currentTimeMillis()
        while (true) {
            delay(PREVIEW_TOGGLE_FRAME_MILLIS)
            if (userIsResizing()) return
            // 进度夹在 [0, 1]：夹到 1 的那一帧插出来的就是 [to] 本身（[smoothStep] 两端取 0 / 1），
            // 因此循环退出时窗口已经落在目标上，不用再补一次。
            val progress = (
                (System.currentTimeMillis() - startedAt).toFloat() /
                    PREVIEW_TOGGLE_MILLIS.toFloat()
                ).coerceIn(0f, 1f)
            val eased = smoothStep(progress)
            applyWindowBounds(
                Rectangle(
                    lerp(from.x, to.x, eased),
                    lerp(from.y, to.y, eased),
                    lerp(from.width, to.width, eased),
                    lerp(from.height, to.height, eased),
                ),
            )
            if (progress >= 1f) return
        }
    }

    /** 应用一次窗口几何；与上次应用过的完全相同就跳过，避免每次状态变化都动一次原生窗口。 */
    private fun applyWindowBounds(bounds: Rectangle) {
        if (lastAppliedBounds == bounds) return
        lastAppliedBounds = bounds
        applyNativeBounds(bounds)
    }

    /**
     * 真正落到原生窗口上的一次应用，并把 `windowState` 对齐到同一组值。
     *
     * **同步**调用 [applyBounds]（不推迟到下一帧）：窗口的 resize 通知由它同步发出、排在当前事件
     * 之后，而下一帧排在通知之后——顺序正好是「窗口动 → 场景按新尺寸布好 → 那一帧画出来」。
     * 试过把提交推迟到帧内（`SideEffect`）省掉中间那一拍，结果场景尺寸反而落后一帧：窗口已经左移
     * 加宽，内容还按旧尺寸排版，整个面板偏出一个滑出宽度。
     *
     * 对齐 state 是因为 Compose 自己的窗口实现会把 state 里的尺寸与位置**分两次**应用到窗口上
     * （先尺寸、后位置），而它读到的这两个值来自窗口的通知，到达有先后：缩放通知先到、移动通知
     * 后到。左侧停靠时窗口要同时「左移」和「变宽」，于是它可能在「尺寸已新、位置仍旧」的那一帧按
     * 旧位置再摆一次窗口。我们把 state 一并对齐后，它那两次应用都是空操作。
     *
     * 尺寸要记进 [recentAppliedSizes]：它的通知随后就到，漏记的话会被当成「用户在拖窗口边缘」
     * （见 [observeUserResize]）。
     */
    private fun applyNativeBounds(bounds: Rectangle) {
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
     *
     * 手还在动的这一整段时间要把 [DesktopShellUiState.userResizing] 报给界面：那几帧设置还是旧
     * 的（落盘要等静默期），界面得跟随实测窗口宽度才跟得上手。
     */
    suspend fun observeUserResize() {
        snapshotFlow { windowState.size }
            .collectLatest { size ->
                // 命中程序最近设过的某个尺寸 —— 这次通知是自己造成的，直接忽略。
                // 判据必须是「最近若干次」而不是「最后一次」，理由见 [recentAppliedSizes]。
                if (wasAppliedByProgram(size)) return@collectLatest

                if (!state.value.userResizing) state.update { it.copy(userResizing = true) }
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
                // 新尺寸已经落盘，界面这一帧起改回「按设置算」。
                if (state.value.userResizing) state.update { it.copy(userResizing = false) }
                // 用户拖出的尺寸仍要受屏幕约束（例如不能盖住 Dock），补一次程序化几何。
                // 拖动期间开关不可能变，因此这里不会有动画；真带出来了也直接落地，理由同
                // [moveToCursor]。
                applyWindowGeometry(
                    snapshot = state.value,
                    settings = repository.settings.value,
                )?.let(::applyWindowBounds)
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

/**
 * 开关动画的进度曲线：两端慢、中间快（`3p² − 2p³`）。
 *
 * 取它而不是线性插值：起手和收尾都是渐入渐出，看不出「启动 / 停住」那两拍；也不必为此引入整
 * 套 `Animatable`——这条动画是宿主的窗口尺寸，本来就跑在协程里，几行插值足够，还顺手避开了
 * 非组合环境没有 `MonotonicFrameClock` 的坑。
 */
private fun smoothStep(progress: Float): Float = progress * progress * (3f - 2f * progress)

/** 整数像素的线性插值；[progress] 已经过 [smoothStep] 之类的曲线。 */
private fun lerp(from: Int, to: Int, progress: Float): Int =
    (from + (to - from) * progress).roundToInt()
