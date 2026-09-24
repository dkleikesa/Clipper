package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** [SelectClipUseCase] 对一次激活做了什么。 */
enum class SelectResult {
    /** 条目一条都不在了（界面上停留期间被删掉）。 */
    IGNORED,

    /** 平台表示不了该内容；一条都没有写入。 */
    UNSUPPORTED,

    /** 条目已放入剪贴板，面板应关闭。 */
    COPIED,

    /** 条目已放入剪贴板，并已安排粘贴按键（多条时是逐条粘贴）。 */
    PASTING,
}

/**
 * 把若干条记录写回系统剪贴板；[ClipAction] 要求时再合成粘贴按键发给此前聚焦的应用。
 *
 * 多条复制 → 拼成一段纯文本；多条粘贴 → 逐条写、逐条 `⌘V`，且每条之后补一个裸回车让目标端
 * 腾出下一处落点（见 [copyTarget] 与 `AppSettings.pressReturnAfterPaste`）。
 *
 * 收的是**条目 id**：写回需要真正的载荷，而载荷只在调用这一刻才按 id 从存储里取出来，
 * 列表里流动的元数据并不含它。
 */
class SelectClipUseCase(
    private val repository: ClipboardRepository,
    private val platform: ClipboardPlatform,
) {
    /**
     * @param itemIds 要写回的条目，**顺序即粘贴顺序**（由调用方按列表顺序给出）。
     * @param onHidePanel 在合成粘贴之前调用，让面板先让开，使按键能到达此前聚焦的应用。
     */
    suspend operator fun invoke(
        itemIds: List<String>,
        action: ClipAction,
        onHidePanel: () -> Unit,
    ): SelectResult {
        if (itemIds.isEmpty()) return SelectResult.IGNORED

        // 条目可能在界面上停留期间被删掉，静默跳过；不要往剪贴板写一份空内容。
        val items = itemIds.distinct().mapNotNull { repository.item(it) }
        if (items.isEmpty()) return SelectResult.IGNORED

        val settings = repository.settings.value
        val removeFormatting = action.stripsFormatting
        val pasting = action.pastes

        // 一次写回，以及它代表的那几条条目。多条复制是**一份**合并快照，两者不是一一对应。
        val batch = if (pasting) {
            items.map { Write(listOf(it.id), snapshotFor(it, removeFormatting)) }
        } else {
            listOf(copyTarget(items, removeFormatting))
        }

        // 面板只让开一次：后面每一条都是同一个目标应用、同一个焦点。目标 pid 也在这里交给
        // 平台层（见 `MacKeyboard.pasteTargetPid`）。
        onHidePanel()

        // 单条粘贴绝不补回车：那是最常用的操作，而 `↵` 在 Finder 这类应用里是重命名。
        val trailingReturn = pasting && batch.size > 1 && settings.pressReturnAfterPaste

        val written = ArrayList<String>(items.size)
        var firstWriteFailed = false
        try {
            // 这段期间剪贴板的每一次变化都是本应用自己造成的，全部抑制捕获。
            repository.withoutCapturing {
                for ((index, write) in batch.withIndex()) {
                    if (!platform.writeClipboard(write.snapshot)) {
                        // 一条都没写进去才算「平台不支持」；中途失败保留已经粘出去的那些。
                        firstWriteFailed = written.isEmpty()
                        break
                    }
                    written += write.ids

                    if (!pasting) break

                    // 第一条等面板让开、焦点回到目标应用；之后每条都要等目标应用把上一次的
                    // `⌘V` 读完，否则换掉剪贴板时上一条还没被取走。
                    delay(if (index == 0) PANEL_HIDE_DELAY_MILLIS else PASTE_INTERVAL_MILLIS)
                    if (!platform.paste()) {
                        repository.setStatusMessage("Pasting is not supported on this platform")
                        break
                    }

                    if (trailingReturn) {
                        delay(PASTE_INTERVAL_MILLIS)
                        platform.pressReturn()
                    }
                }

                // 自己写下的那几次剪贴板变化必须被监视器**在抑制期内**读走（读走即丢弃）；
                // 否则它们会在抑制解除后才被发现，那几条就被记两次。轮询相位任意，等满一个间隔。
                if (written.isNotEmpty()) {
                    delay(repository.clipboardPollIntervalMillis.toLong() + CAPTURE_SETTLE_MARGIN_MILLIS)
                }
            }
        } finally {
            // 补记这次批量（各算一次「被复制」，只重排一次列表）。用 `NonCancellable`：中途被取消
            // 时已经写出去的那几条不能白写。
            if (written.isNotEmpty()) {
                withContext(NonCancellable) { repository.recordBatchCopy(written) }
            }
        }

        if (firstWriteFailed) {
            repository.setStatusMessage("This platform can't copy that kind of content")
            return SelectResult.UNSUPPORTED
        }
        return if (pasting) SelectResult.PASTING else SelectResult.COPIED
    }

    /**
     * 不粘贴时写进剪贴板的内容。
     *
     * 一条原样写回（保住 HTML / RTF 等全部表示）；多条只产出**一段纯文本**，每条贡献它的文本
     * 表示后换行连接。多条刻意不保留 `files` / `contents`：混合快照在不同目标端会粘出完全
     * 不同的东西，纯文本则处处一致。有一条拿不出文本（纯图片）时退回只写最后一条。
     */
    private fun copyTarget(items: List<ClipItem>, removeFormatting: Boolean): Write {
        val last = items.last()
        if (items.size == 1) return Write(listOf(last.id), snapshotFor(last, removeFormatting))

        val texts = items.map { mergeableText(it) }
        if (texts.any { it == null }) return Write(listOf(last.id), snapshotFor(last, removeFormatting))

        return Write(
            ids = items.map { it.id },
            snapshot = ClipboardSnapshot(
                text = texts.filterNotNull().joinToString(separator = ITEM_SEPARATOR),
            ),
        )
    }

    /**
     * 条目能贡献的那段文本：**正文** → 从 HTML / RTF 解析出的文字 → **文件路径**。
     * 拿不出文本时为 `null`。
     *
     * 不拿图片识别结果充数：把 OCR 文字掺进结果、图本身却丢掉，不是用户要的。
     */
    private fun mergeableText(item: ClipItem): String? = when {
        item.text != null -> item.text
        // 只写了 HTML / RTF、没写纯文本的那些条目。
        item.contents.isNotEmpty() -> item.previewableText.takeIf { it.isNotEmpty() }
        item.files.isNotEmpty() -> item.files.joinToString(separator = ITEM_SEPARATOR)
        else -> null
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

        // 去格式也保留纯文本与文件 URL，这样仍然能粘贴文件；没有任何字符串表示时与普通复制一致。
        if (item.text == null) {
            return ClipboardSnapshot(
                text = if (item.image == null && item.files.isEmpty()) item.previewableText else null,
                image = item.image,
                files = item.files,
            )
        }
        return ClipboardSnapshot(text = item.text, files = item.files)
    }

    /** 一次写回：要放进剪贴板的内容，以及它代表的那几条条目。 */
    private data class Write(val ids: List<String>, val snapshot: ClipboardSnapshot)

    private companion object {
        /** 多条复合成一段文本时的分隔符。 */
        const val ITEM_SEPARATOR = "\n"

        /** 第一次粘贴前的等待：面板让开、焦点回到此前的应用。 */
        const val PANEL_HIDE_DELAY_MILLIS = 160L

        /** 相邻两条的间隔：给目标应用留出取走上一次粘贴内容的时间。 */
        const val PASTE_INTERVAL_MILLIS = 140L

        /** 收尾等待在轮询间隔之上多留的余量（相位不齐的那一点）。 */
        const val CAPTURE_SETTLE_MARGIN_MILLIS = 80L
    }
}
