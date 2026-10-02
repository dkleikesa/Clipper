package com.qcmian.clipper.desktop.domain

import com.qcmian.clipper.core.platform.macos.MacKeyboard
import com.qcmian.clipper.core.platform.macos.MacOutsideClickMonitor
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 面板表现层读取的那部分「外界」：此前最前的外部应用、「把焦点还回去」的动作，以及
 * 「面板外点击」事件。
 *
 * 抽成注入点（测试缝）的理由与 [WindowEnvironment] 相同：表现层真正反复出问题的是**时序**
 * ——面板刚显示后的失焦宽限期、托盘点击宽限期、以及「让位给设置窗口时该不该抢回焦点」——
 * 而这些判定全都要读系统时钟并触达 AppKit。直接读原生就没有单测可言；注入之后可以用一个
 * 可变时钟把边界跑满（见 `PanelPresentationControllerTest`）。
 *
 * 生产路径用 [MacPanelEnvironment]（照抄原调用），测试注入记录调用的替身。
 */
internal interface PanelEnvironment {

    /** 面板显示前处于最前的外部应用 pid；取不到、或最前的正是本应用时为 `-1`。 */
    fun frontmostExternalPid(): Long

    /**
     * 把焦点还给 [pid]，使随后合成的粘贴（⌘V）落到它上面。
     *
     * 抽成一个动作而不是暴露两个原语：调用方只关心「焦点要还回去」，而「怎么还」——
     * 写 `MacKeyboard.pasteTargetPid` 再用 `NSRunningApplication` 激活——是平台细节。
     */
    fun restoreFocus(pid: Long)

    /**
     * 把随后的 ⌘V 投给**本应用自己**——用户此前正在开发者工具窗口里编辑。
     *
     * 与 [restoreFocus] 分开而不是复用同一个「pid 参数」：那条路要做的是**激活另一个应用**，
     * 而这里本应用已经在前台，需要的是「别去动别人」。也不能顺手把自己再激活一次——
     * 那会把键盘焦点从开发窗口的输入框上挪开，粘进去的内容就不知道落到哪了。
     */
    fun targetSelfForPaste()

    /**
     * 安装「面板外点击」监视器，并返回其事件流（每次面板外点击发一个元素）。
     *
     * 装不上时返回一个不发射的流——那只是「点击别处收起」这一条能力静默缺失，其余收起路径
     * （失焦、Esc、托盘、热键）不受影响。
     */
    fun outsideClicks(): Flow<Unit>
}

/** 生产实现：走 AppKit。 */
internal object MacPanelEnvironment : PanelEnvironment {

    override fun frontmostExternalPid(): Long =
        runCatching { MacWorkspace.frontmostExternalPid() }.getOrDefault(-1L)

    override fun restoreFocus(pid: Long) {
        // `activate` 只是异步请求，⌘V 发出时目标应用未必已到前台；把 pid 交给 `MacKeyboard`，
        // 让它用 `CGEventPostToPid` 直接投递，绕开时序竞态。
        MacKeyboard.pasteTargetPid = pid
        runCatching { MacWorkspace.activate(pid) }
    }

    override fun targetSelfForPaste() {
        // 只改投递目标，不做任何激活：本应用已经在前台，开发窗口的输入框也已经握着键盘焦点。
        MacKeyboard.pasteTargetPid = MacWorkspace.ownPid
    }

    override fun outsideClicks(): Flow<Unit> {
        // 设置窗口、开发者工具窗口也是本应用自己的窗口：点在里面不算「点了别处」，否则面板一
        // 让位、用户去点那些窗口就会把面板（连带它）当成被点掉。
        MacOutsideClickMonitor.install(
            PANEL_WINDOW_TITLE,
            listOf(SETTINGS_WINDOW_TITLE, DEVTOOLS_WINDOW_TITLE),
        )
        // 监视器报的是计数（`StateFlow<Int>`），这里只取「发生了一次」这一件事。
        return MacOutsideClickMonitor.outsideClicks.map { }
    }
}
