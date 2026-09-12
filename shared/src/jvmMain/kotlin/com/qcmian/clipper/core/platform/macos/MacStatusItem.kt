package com.qcmian.clipper.core.platform.macos

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.qcmian.clipper.core.data.source.isMacOs
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * macOS 的菜单栏图标，直接用 AppKit 的 `NSStatusItem`。
 *
 * 为什么不用 AWT 的托盘：AWT 在状态项上挂的是自定义 `NSView` 而不是标准 button，因此
 * 既拿不到系统绘制的按下高亮，尺寸也由它按图片自行推算（`isImageAutoSize`）。换成真正的
 * `NSStatusItem` 后：
 *
 * - `NSVariableStatusItemLength` 让 AppKit 自己算宽度，自带原生的左右内边距；
 * - 按下高亮由 AppKit 画，也就是系统那个胶囊，不必自己画；
 * - action 在鼠标抬起时派发（Maccy 同款），此时按钮的鼠标追踪已结束，程序化推上去的
 *   高亮不会被追踪循环清掉。
 *
 * **线程**：AppKit 要求状态项只能在主线程上创建（`NSWindow should only be instantiated on
 * the main thread!`）。注意 AWT 的 EDT **不是** AppKit 主线程，所以这里不能用
 * `SwingUtilities.invokeLater`，而是把所有 AppKit 工作收进一个动态建出来的 ObjC 方法里，
 * 再用 `performSelectorOnMainThread:` 派发过去。任一步失败都只让托盘不出现，绝不抛给调用方。
 */
object MacStatusItem {

    /** 动态建出来的 action 目标类名。 */
    private const val TARGET_CLASS_NAME = "ClipperStatusItemTarget"

    /** AppKit 主线程上会执行的选择器。 */
    private const val APPLY_SELECTOR = "apply:"

    /** 状态项按钮被按下时，AppKit 经 target/action 发出的选择器。 */
    private const val CLICK_SELECTOR = "onClick:"

    /** `NSVariableStatusItemLength`：宽度由 AppKit 按内容决定。 */
    private const val VARIABLE_LENGTH = -1.0

    /** 标准 `NSStatusItem` 的按钮类名；AWT 宿主下拿到的可能不是它。 */
    private const val BUTTON_CLASS = "NSStatusBarButton"

    /** 左 / 右键抬起时派发 action（与 Maccy 一致）：此时鼠标追踪已结束，推高亮不会被清掉。 */
    private val MOUSE_UP_MASK = (1L shl 2) or (1L shl 4)

    /** 栅格化的倍率：按 2x 出图，Retina 下才清晰。 */
    private const val RASTER_SCALE = 2

    private val appKitLoaded: Boolean = run {
        MacNative.loadFramework("/System/Library/Frameworks/Foundation.framework/Foundation")
        MacNative.loadFramework("/System/Library/Frameworks/AppKit.framework/AppKit")
    }

    /** 当前平台是否能用原生状态项；调用方据此决定要不要退回 AWT 托盘。 */
    val isSupported: Boolean by lazy {
        isMacOs && appKitLoaded && MacNative.clazz("NSStatusBar") != null
    }

    private var statusBar: Pointer? = null
    private var statusItem: Pointer? = null
    private var button: Pointer? = null

    /** 主线程派发用的目标对象与函数指针。 */
    private var applyTarget: Pointer? = null

    /**
     * 动作回调。保存在字段里是为了防止 JNA 的 `Callback` 被 GC——它的函数指针会被写进
     * 运行时建的类，回收后就变成野指针。
     */
    private val applyCallback: Callback = object : ApplyCallback {
        override fun apply(self: Pointer?, command: Pointer?, argument: Pointer?) {
            applyPending()
        }
    }

    private interface ApplyCallback : Callback {
        fun apply(self: Pointer?, command: Pointer?, argument: Pointer?)
    }

    /**
     * 点击回调：把按下事件转发给 [clickHandler]。
     *
     * 两个选择器必须分开——`apply:` 同时被 `performSelectorOnMainThread` 用来推状态，
     * 如果点击也走它，安装 / 换图标时都会误触发一次「点击」。
     */
    private val clickCallback: Callback = object : ClickCallback {
        override fun onClick(self: Pointer?, command: Pointer?, argument: Pointer?) {
            clickHandler()
        }
    }

    private interface ClickCallback : Callback {
        fun onClick(self: Pointer?, command: Pointer?, argument: Pointer?)
    }

    /** 真正要执行的动作；安装/重复安装时替换它，回调本身不必重建。 */
    @Volatile
    private var clickHandler: () -> Unit = {}

    /** 待安装的 PNG 字节与逻辑尺寸；`null` 表示图标没变，不用重设。 */
    @Volatile
    private var pendingPng: ByteArray? = null

    @Volatile
    private var pendingPointSize: Double = 0.0

    /** 最近一次下发过的状态，用来跳过重复的 AppKit 调用。 */
    @Volatile
    private var highlighted: Boolean? = null

    @Volatile
    private var appearsDisabled: Boolean? = null

    @Volatile
    private var tooltip: String? = null

    @Volatile
    private var removed = false

    /**
     * 安装菜单栏图标。
     *
     * @param painter 图标；会在调用线程上栅格化一次（不涉及 AppKit）。
     * @param pointSize 图标在菜单栏里的边长（pt）。
     * @param onPrimaryClick 左右键点击（鼠标抬起）时调用。
     */
    fun install(painter: Painter, pointSize: Double, onPrimaryClick: () -> Unit) {
        clickHandler = onPrimaryClick
        if (!isSupported) return

        removed = false
        pendingPointSize = pointSize
        pendingPng = painter.toPngBytes((pointSize * RASTER_SCALE).toInt())
        scheduleApply()
    }

    /**
     * 面板显示期间保持按下高亮，也就是系统那个胶囊。
     *
     * [force] 用于绕过去重：托盘点击的回调里会**同步预推**翻转后的按下态（此时它可能与
     * 上次值相同，也可能投影尚未落地），必须立即生效，不等下一次投影。
     */
    fun setHighlighted(value: Boolean, force: Boolean = false) {
        if (!isSupported || (!force && highlighted == value)) return
        highlighted = value
        scheduleApply()
    }

    /** `NSStatusBarButton.appearsDisabled`：暂停记录时由系统把图标画灰。 */
    fun setDisabled(value: Boolean) {
        if (!isSupported || appearsDisabled == value) return
        appearsDisabled = value
        scheduleApply()
    }

    fun setTooltip(text: String?) {
        if (!isSupported || tooltip == text) return
        tooltip = text
        scheduleApply()
    }

    fun remove() {
        if (!isSupported) return
        removed = true
        scheduleApply()
    }

    /** 把「应用待定状态」这件事派发到 AppKit 主线程。 */
    private fun scheduleApply() {
        val target = ensureTarget() ?: return
        MacNative.send(
            target,
            "performSelectorOnMainThread:withObject:waitUntilDone:",
            MacNative.selector(APPLY_SELECTOR),
            null,
            0.toByte(),
        )
    }

    /** 确保动态类与目标对象已就绪；[APPLY_SELECTOR] 会在主线程上被调起。 */
    private fun ensureTarget(): Pointer? {
        applyTarget?.let { return it }

        val implementation = CallbackReference.getFunctionPointer(applyCallback) ?: return null
        val clickImplementation = CallbackReference.getFunctionPointer(clickCallback) ?: return null
        val targetClass = MacNative.clazz(TARGET_CLASS_NAME)
            ?: MacNative.allocateClass("NSObject", TARGET_CLASS_NAME)?.also {
                MacNative.addMethod(it, APPLY_SELECTOR, implementation, "v@:@")
                MacNative.addMethod(it, CLICK_SELECTOR, clickImplementation, "v@:@")
                MacNative.registerClass(it)
            }
            ?: return null

        val target = MacNative.send(MacNative.send(targetClass, "alloc"), "init")
        applyTarget = target
        return target
    }

    /** 在 AppKit 主线程上执行：按需创建状态项，并把待定的状态应用上去。 */
    private fun applyPending() {
        if (removed) {
            val bar = statusBar
            val item = statusItem
            val itemButton = button
            statusBar = null
            statusItem = null
            button = null
            MacNative.send(bar, "removeStatusItem:", item)
            MacNative.send(item, "release")
            MacNative.send(itemButton, "release")
            return
        }

        if (statusItem == null) {
            val bar = MacNative.send(MacNative.clazz("NSStatusBar"), "systemStatusBar") ?: return
            val item = MacNative.send(bar, "statusItemWithLength:", VARIABLE_LENGTH) ?: return
            val itemButton = MacNative.send(item, "button") ?: return
            // `statusItemWithLength:` / `button` 返回的都是 autorelease 对象，这轮 runloop 一结束
            // 就会被回收；JNA 的 Pointer 只是裸地址，不持有引用。不 retain 的话，下一次 apply 时
            // button 的内存大概率已被复用（实测复用成了 GPU 纹理对象），发消息直接崩。
            MacNative.send(item, "retain")
            MacNative.send(itemButton, "retain")
            statusBar = bar
            statusItem = item
            button = itemButton
            attachAction(itemButton)
        }

        val itemButton = button ?: return
        pendingPng?.let { png ->
            val image = makeImage(png, pendingPointSize)
            if (image != null) {
                MacNative.send(itemButton, "setImage:", image)
                MacNative.send(image, "release")
                pendingPng = null
            }
        }

        // AWT 宿主下 `button` 不是 NSStatusBarButton（实测是 NSImageView），系统的
        // 按下高亮 / 置灰方法不存在，直接发会崩——只在类匹配时才发。
        if (MacNative.className(itemButton) == BUTTON_CLASS) {
            highlighted?.let { MacNative.send(itemButton, "setHighlighted:", boolValue(it)) }
            appearsDisabled?.let { MacNative.send(itemButton, "setAppearsDisabled:", boolValue(it)) }
        }
        tooltip?.let {
            val text = MacNative.nsString(it)
            MacNative.send(itemButton, "setToolTip:", text)
            MacNative.send(text, "release")
        }
    }

    /** 把按钮的 target/action 指到 [CLICK_SELECTOR]，在鼠标抬起时触发。 */
    private fun attachAction(itemButton: Pointer) {
        val implementation = CallbackReference.getFunctionPointer(clickCallback) ?: return
        MacNative.send(itemButton, "setTarget:", applyTarget)
        MacNative.send(itemButton, "setAction:", MacNative.selector(CLICK_SELECTOR))
        MacNative.sendLong(itemButton, "sendActionOn:", MOUSE_UP_MASK)
    }

    /** 把 PNG 字节变成尺寸正确的 `NSImage`。 */
    private fun makeImage(png: ByteArray, pointSize: Double): Pointer? {
        val data = MacNative.data(png) ?: return null
        val imageClass = MacNative.clazz("NSImage") ?: return null
        val image = MacNative.send(MacNative.send(imageClass, "alloc"), "initWithData:", data) ?: return null
        // PNG 的像素数会被当成点数，所以显式改回逻辑尺寸，否则菜单栏里会大一圈。
        MacNative.setImageSize(image, pointSize, pointSize)
        return image
    }

    /** 把 [Painter] 画进一张 ARGB 位图再编码成 PNG。 */
    private fun Painter.toPngBytes(pixels: Int): ByteArray? = runCatching {
        val edge = pixels.toFloat()
        val bitmap = ImageBitmap(pixels, pixels)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(edge, edge),
        ) {
            with(this@toPngBytes) { draw(Size(edge, edge)) }
        }

        val source = bitmap.toPixelMap()
        val image = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels) {
            for (x in 0 until pixels) {
                image.setRGB(x, y, source[x, y].toArgb())
            }
        }
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }.getOrNull()

    /** JNA 按参数的实际类型选择 ABI，`BOOL` 是单字节，必须传 `Byte` 而不是 `Boolean`。 */
    private fun boolValue(value: Boolean): Byte = if (value) 1 else 0
}
