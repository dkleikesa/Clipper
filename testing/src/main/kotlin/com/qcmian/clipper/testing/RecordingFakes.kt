package com.qcmian.clipper.testing

import com.qcmian.clipper.core.data.source.ClipboardDataSource
import com.qcmian.clipper.core.data.source.NativeDataSource
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.SourceApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 只记账的剪贴板：**不会**碰开发机上真正的粘贴板。
 *
 * [emit] 模拟一次外部复制（用户 / 其它应用往剪贴板写入），这是 A 层「喂系统输入」的入口；
 * 写入与按键只累加计数，供「写回是否逐条进行」这类断言使用。
 *
 * 同时服务两处测试：`:shared` 的 A 层（`InProcessCluster`）与 `:cli` 的 B 层（`CliEndToEndTest`
 * 的 `TestCluster`）——两者都只用到 `writeSucceeds` / `written` 这类记账面。
 */
class RecordingClipboard : ClipboardDataSource {
    var writeSucceeds: Boolean = true
    var pasteSucceeds: Boolean = true
    var returnSucceeds: Boolean = true

    /** 每一次写回的快照，按发生顺序；单条场景取 [written]。 */
    val writes: MutableList<ClipboardSnapshot> = mutableListOf()
    val written: ClipboardSnapshot? get() = writes.lastOrNull()

    var pasteCount: Int = 0
        private set
    var returnCount: Int = 0
        private set

    private var onChange: ((ClipboardSnapshot) -> Unit)? = null

    override fun write(snapshot: ClipboardSnapshot): Boolean {
        if (!writeSucceeds) return false
        writes += snapshot
        return true
    }

    override fun start(onChange: (ClipboardSnapshot) -> Unit) {
        this.onChange = onChange
    }

    override fun stop() {
        onChange = null
    }

    override fun paste(): Boolean {
        pasteCount++
        return pasteSucceeds
    }

    override fun pressReturn(): Boolean {
        returnCount++
        return returnSucceeds
    }

    /** 模拟一次外部复制。监听尚未接上时静默丢弃——与真实的监听器一致。 */
    fun emit(snapshot: ClipboardSnapshot) {
        onChange?.invoke(snapshot)
    }

    /**
     * 监听接上了才返回。
     *
     * 仓库在 `start()` 的后半程才注册回调，而那个回调是快照唯一的入口：早于此发出的
     * [emit] 会静默丢失，测试于是「什么都没记录」却看不出原因。
     */
    suspend fun awaitListening(timeoutMillis: Long = 15_000L) {
        withTimeout(timeoutMillis) {
            while (onChange == null) delay(5L)
        }
    }
}

/**
 * 可配置的假原生能力：默认什么都不支持，与服务端测试里的空实现一致。
 *
 * 能力开关是**可变的**：`DefaultClipboardRepository` 在每次调用时现读它们（见
 * `currentSourceApplication` 的前置判断），因此测试可以中途打开某一项再喂快照。
 * 打开 [supportsTextRecognition] 并给 [recognizedText] 即可驱动「图片 → 标题」那条协程。
 */
class RecordingNative(
    override var supportsApplicationInfo: Boolean = false,
    override var supportsTextRecognition: Boolean = false,
) : NativeDataSource {
    var sourceApplication: SourceApplication? = null
    var recognizedText: String? = null

    /**
     * `NSEvent.modifierFlags` 的替身：此刻按着哪些修饰键。
     *
     * 全局热键的「按住循环」每个采样点读它一次（再与 `MacModifierMonitor` 取交集），
     * 单测因此靠改它来模拟「松掉一部分」与「整组键都松开」。
     */
    var modifierFlags: Int = 0

    override fun frontmostApplication(): SourceApplication? = sourceApplication

    override suspend fun recognizeText(image: ClipImage): String? = recognizedText

    override fun currentModifierFlags(): Int = modifierFlags
}
