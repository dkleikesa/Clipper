package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.pastes
import com.qcmian.clipper.core.domain.action.removesFormatting
import kotlinx.coroutines.delay

/** [SelectClipUseCase] 对一次激活做了什么。 */
enum class SelectResult {
    /** 不支持的修饰键组合；什么也没发生。 */
    IGNORED,

    /** 平台无法表示该内容；没有写入任何东西。 */
    UNSUPPORTED,

    /** 条目已放入剪贴板，面板应关闭。 */
    COPIED,

    /** 条目已放入剪贴板，并已安排一次粘贴按键。 */
    PASTING,
}

/**
 * 把某条记录写回系统剪贴板；当解析出的 [ClipAction]
 * 要求时，再把粘贴按键发送给此前聚焦的应用。
 *
 * 接收的是**条目 id**而不是条目本身：写回剪贴板需要真正的载荷，而载荷只有在这一刻才
 * 从存储里按 id 取出来（见 `ClipboardRepository.item`）。列表里流动的元数据不含它。
 *
 * 延迟粘贴用挂起函数里的普通 [delay] 表达，因此由调用方决定它运行在哪个作用域
 * （也就决定了生命周期）。
 */
class SelectClipUseCase(
    private val repository: ClipboardRepository,
    private val platform: ClipboardPlatform,
) {
    /**
     * @param onHidePanel 在合成粘贴之前调用，让面板先让开，使按键能到达此前聚焦的应用。
     */
    suspend operator fun invoke(
        itemId: String,
        action: ClipAction,
        onHidePanel: () -> Unit,
    ): SelectResult {
        // 不支持的修饰键组合什么都不做。
        if (action == ClipAction.UNKNOWN) return SelectResult.IGNORED

        // 条目可能在界面上停留期间被删掉；此时静默放弃，不要往剪贴板写一份空内容。
        val item = repository.item(itemId) ?: return SelectResult.IGNORED

        val settings = repository.settings.value
        val removeFormatting = action.removesFormatting(settings)
        if (!platform.writeClipboard(snapshotFor(item, removeFormatting))) {
            repository.setStatusMessage("This platform can't copy that kind of content")
            return SelectResult.UNSUPPORTED
        }

        // 所有分支都会关闭弹窗，复制也不例外。
        onHidePanel()

        if (!action.pastes(settings)) return SelectResult.COPIED

        delay(PASTE_DELAY_MILLIS)
        if (!platform.paste()) {
            repository.setStatusMessage("Pasting is not supported on this platform")
        }
        return SelectResult.PASTING
    }

    private fun snapshotFor(item: ClipItem, removeFormatting: Boolean): ClipboardSnapshot {
        if (!removeFormatting) {
            return ClipboardSnapshot(
                text = item.text ?: if (item.image == null && item.files.isEmpty()) item.previewableText else null,
                image = item.image,
                files = item.files,
                contents = item.contents,
            )
        }

        // 保留纯字符串*以及*文件 URL，
        // 这样「不带格式粘贴」仍然能粘贴文件。当条目没有任何字符串表示时，
        // 表现得与普通复制完全一致。
        if (item.text == null) {
            return ClipboardSnapshot(
                text = if (item.image == null && item.files.isEmpty()) item.previewableText else null,
                image = item.image,
                files = item.files,
            )
        }
        return ClipboardSnapshot(text = item.text, files = item.files)
    }

    private companion object {
        const val PASTE_DELAY_MILLIS = 160L
    }
}
