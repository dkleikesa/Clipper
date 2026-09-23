package com.qcmian.clipper.core.platform.macos

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * 用 CoreGraphics 合成一次「⌘ + 某个键」（默认 `V`）。
 *
 * 不用 [java.awt.Robot]：macOS 上它的 `keyEvent` 并不把修饰键写进事件，而是靠
 * `CGEnableEventStateCombining` 让合成事件与系统 HID 状态自动融合（见 JDK 的 `CRobot.m`，
 * 它读的 flags 只有一处修正——清掉 Fn 位）。也就是说，那个 `V` 算不算「⌘V」取决于发出它
 * 的那一刻系统是否已经登记了 ⌘，而 ⌘ 只是它的**前一个独立事件**。面板收起、目标应用被激活的
 * 这段时间里这个先后关系并不保险：⌘ 的按下可能落到别处、也可能还没生效，`V` 于是成了一次裸
 * 按键——这就是「偶尔只粘贴出一个 v」。
 *
 * 实现细节：
 * 1. 事件源用 `kCGEventSourceStateCombinedSessionState`；
 * 2. ⌘ 直接写在按下 / 抬起事件的 flags 上（不单独发修饰键事件），并额外带上
 *    `kCGEventFlagMaskNonCoalesced`——部分应用只认带「非合并」标记的合成事件；
 * 3. 投到 `kCGSessionEventTap` 而非 HID tap；
 * 4. 发送前给事件源设置抑制期过滤器，只放行鼠标与系统事件：合成的 ⌘V 落地后系统
 *    会短暂抑制本地事件，这样不会反过来触发本应用自己的热键 / 全局事件监听。
 *
 * 按下与抬起之间不留任何间隔。键位固定用物理 `V`（`kVK_ANSI_V`）：
 * 「Dvorak - QWERTY ⌘」这类按 ⌘ 临时切回 QWERTY 的布局天然正确。
 *
 * 面板是普通窗口，打开时会激活自己，隐藏时用 `NSRunningApplication.activate` 还焦点又只是
 * **异步**生效：若把事件投到 session tap，落在谁头上取决于投递瞬间谁在最前，激活没完成事件就
 * 被丢弃。因此只要知道目标 pid，就改用 `CGEventPostToPid` 直接投给那个进程，与前台时序彻底解耦。
 *
 * 所有调用都是防御式的：框架或符号缺失时 [available] 为 `false`，调用方退回 [java.awt.Robot]。
 */
object MacKeyboard {

    /** `kVK_ANSI_V`：字母 `V` 的键位码（物理位置，与键盘布局无关）。 */
    private const val KEY_CODE_V = 9

    /** `kVK_Return`：主键盘回车。 */
    private const val KEY_CODE_RETURN = 36

    /** `kCGEventFlagMaskCommand`。 */
    private const val FLAG_COMMAND = 1L shl 20

    /** `kCGEventFlagMaskNonCoalesced`：额外带上「非合并」标记（`0x000008`）。 */
    private const val FLAG_NON_COALESCED = 1L shl 3

    /** `kCGSessionEventTap`。 */
    private const val SESSION_EVENT_TAP = 1

    /** `kCGEventSourceStateCombinedSessionState`。 */
    private const val SOURCE_STATE_COMBINED_SESSION = 0

    /**
     * `kCGEventFilterMaskPermitLocalMouseEvents | kCGEventFilterMaskPermitSystemDefinedEvents`：
     * 抑制期内放行的事件（键盘仍被抑制）。
     */
    private const val FILTER_PERMIT_MOUSE_OR_SYSTEM = (1 shl 0) or (1 shl 2)

    /** `kCGEventSuppressionStateSuppressionInterval`。 */
    private const val SUPPRESSION_STATE_INTERVAL = 0

    private const val CORE_GRAPHICS_PATH =
        "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics"

    private const val CORE_FOUNDATION_PATH =
        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"

    private const val HI_SERVICES_PATH =
        "/System/Library/Frameworks/ApplicationServices.framework/Frameworks/HIServices.framework/HIServices"

    /**
     * 系统框架在 dyld 共享缓存里，磁盘上没有实体文件，但按路径 `dlopen` 依旧命中缓存；
     * 万一失败就退回进程已加载的映像（`CoreFoundation` 等在任何 Cocoa 进程里都已就位）。
     */
    private val libraries: List<NativeLibrary> = buildList {
        listOf(CORE_GRAPHICS_PATH, CORE_FOUNDATION_PATH, HI_SERVICES_PATH).forEach { path ->
            runCatching { add(NativeLibrary.getInstance(path)) }
        }
        runCatching { add(NativeLibrary.getProcess()) }
    }

    private fun function(name: String): Function? = libraries
        .firstNotNullOfOrNull { library -> runCatching { library.getFunction(name) }.getOrNull() }

    private val createSource = function("CGEventSourceCreate")
    private val createKeyboardEvent = function("CGEventCreateKeyboardEvent")
    private val setEventFlags = function("CGEventSetFlags")
    private val postEvent = function("CGEventPost")
    private val postToPid = function("CGEventPostToPid")
    private val setLocalEventsFilter =
        function("CGEventSourceSetLocalEventsFilterDuringSuppressionState")
    private val axIsProcessTrusted = function("AXIsProcessTrusted")
    private val axIsProcessTrustedWithOptions = function("AXIsProcessTrustedWithOptions")
    private val release = function("CFRelease")

    /** 系统授权提示的节流：提示弹多了只会骚扰，10 秒内不重复请求。 */
    private const val PROMPT_THROTTLE_MILLIS = 10_000L

    @Volatile
    private var lastPromptAtMillis = 0L

    /** 辅助功能权限：未授予时系统会静默丢弃一切合成按键事件。符号缺失时按已授予处理。 */
    private fun accessibilityTrusted(): Boolean {
        val check = axIsProcessTrusted ?: return true
        return runCatching { check.invokeInt(EMPTY_ARGS) == 1 }.getOrDefault(true)
    }

    /**
     * 未授权时请求系统弹出标准的授权提示——
     * 「…想要控制这台电脑」+「打开系统设置」按钮，即
     * `AXIsProcessTrustedWithOptions(kAXTrustedCheckOptionPrompt: true)`。
     * 提示已节流。返回当前是否已授权。
     */
    fun promptAccessibility(): Boolean {
        if (accessibilityTrusted()) return true
        val now = System.currentTimeMillis()
        if (now - lastPromptAtMillis < PROMPT_THROTTLE_MILLIS) return false
        lastPromptAtMillis = now

        val prompt = axIsProcessTrustedWithOptions ?: return false
        runCatching {
            // `AXIsProcessTrustedWithOptions` 收 `CFDictionaryRef`；
            // `NSDictionary` 与它无桥桥接，直接用 msgSend 拼一个。
            val dict = MacNative.send(MacNative.clazz("NSMutableDictionary"), "dictionary")
            val key = MacNative.nsString("AXTrustedCheckOptionPrompt")
            val yes = MacNative.send(MacNative.clazz("NSNumber"), "numberWithBool:", 1)
            if (dict == null || key == null || yes == null) return@runCatching
            MacNative.send(dict, "setObject:forKey:", yes, key)
            prompt.invoke(arrayOf<Any?>(dict))
        }
        return false
    }

    /**
     * 下一次「粘贴」的直接投递目标：面板打开前最前应用（`DesktopShellViewModel` 在隐藏面板、
     * 还焦点之前写入）。大于 `0` 且 [postToPid] 可用时，⌘V 直接投给该进程；
     * 否则退回 session tap 广播，落在投递瞬间最前的应用上。
     */
    @Volatile
    var pasteTargetPid: Long = -1

    /** 合成按键所需的符号是否齐全。 */
    val available: Boolean
        get() = createKeyboardEvent != null && setEventFlags != null && postEvent != null

    /**
     * 合成一次 [keyCode] 的「⌘ + 该键」按下与抬起；成功投递返回 `true`。
     */
    fun sendCommandKey(keyCode: Int = KEY_CODE_V): Boolean = sendKey(keyCode, command = true)

    /**
     * 合成一次**不带领事键**的回车。
     *
     * 连续粘贴用它：目标端多半要有一次「提交 / 换行」才会腾出下一处落点（终端执行命令、
     * 聊天框发送、Excel 下移一格）。带 `⌘` 就完全是另一回事了，所以这里必须是不带修饰键的按键。
     */
    fun sendReturn(): Boolean = sendKey(KEY_CODE_RETURN, command = false)

    /**
     * 合成一次 [keyCode] 的按下与抬起；[command] 决定事件上写不写 `⌘`。成功投递返回 `true`。
     */
    fun sendKey(keyCode: Int, command: Boolean): Boolean {
        // 没授权时合成事件会被系统静默丢弃：与其让粘贴无声失败，不如当场弹授权提示。
        if (!accessibilityTrusted()) promptAccessibility()
        val create = createKeyboardEvent ?: return false
        val setFlags = setEventFlags ?: return false
        val post = postEvent ?: return false

        val source = createEventSource()
        val argument = source ?: Pointer.NULL
        // 抑制期内只放行鼠标与系统事件。这个符号拿不到就跳过——它只影响
        // 本应用自身在抑制期里收到什么，不影响事件向目标应用的投递。
        val filter = setLocalEventsFilter
        if (source != null && filter != null) {
            runCatching {
                filter.invoke(
                    arrayOf(source, FILTER_PERMIT_MOUSE_OR_SYSTEM, SUPPRESSION_STATE_INTERVAL)
                )
            }
        }

        val down = runCatching { create.invokePointer(arrayOf(argument, keyCode, 1)) }.getOrNull()
        val up = runCatching { create.invokePointer(arrayOf(argument, keyCode, 0)) }.getOrNull()
        if (down == null || up == null) {
            releaseAll(down, up, source)
            return false
        }

        // 不带 `⌘` 时只留「非合并」标记：部分应用只认带该标记的合成事件。
        val flags = (if (command) FLAG_COMMAND else 0L) or FLAG_NON_COALESCED
        val targetPid = pasteTargetPid
        val directPost = postToPid
        val posted = runCatching {
            setFlags.invoke(arrayOf(down, flags))
            setFlags.invoke(arrayOf(up, flags))
            if (targetPid > 0 && directPost != null) {
                // 直接投给目标进程：不依赖「激活是否已完成」「谁在最前」。
                directPost.invoke(arrayOf(targetPid.toInt(), down))
                directPost.invoke(arrayOf(targetPid.toInt(), up))
            } else {
                post.invoke(arrayOf(SESSION_EVENT_TAP, down))
                post.invoke(arrayOf(SESSION_EVENT_TAP, up))
            }
        }.isSuccess

        releaseAll(down, up, source)
        return posted
    }

    /** 事件源只影响新事件的初始字段与抑制行为（稍后 flags 会被显式覆盖）；拿不到就交给系统默认源。 */
    private fun createEventSource(): Pointer? {
        val create = createSource ?: return null
        return runCatching {
            create.invokePointer(arrayOf(SOURCE_STATE_COMBINED_SESSION))
        }.getOrNull()
    }

    private fun releaseAll(vararg pointers: Pointer?) {
        val cfRelease = release ?: return
        pointers.filterNotNull().forEach { pointer ->
            runCatching { cfRelease.invoke(arrayOf<Any?>(pointer)) }
        }
    }

    private val EMPTY_ARGS = emptyArray<Any?>()
}
