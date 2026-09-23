package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.pastes
import com.qcmian.clipper.core.domain.action.removesFormatting
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** [SelectClipUseCase] 对一次激活做了什么。 */
enum class SelectResult {
    /** 不支持的修饰键组合，或条目已经不存在；什么也没发生。 */
    IGNORED,

    /** 平台无法表示该内容；一条都没有写入。 */
    UNSUPPORTED,

    /** 条目已放入剪贴板，面板应关闭。 */
    COPIED,

    /** 条目已放入剪贴板，并已安排粘贴按键（多条时是逐条粘贴）。 */
    PASTING,
}

/**
 * 把若干条记录写回系统剪贴板；当解析出的 [ClipAction] 要求时，再把粘贴按键发送给此前聚焦的应用。
 *
 * **多条走两条不同的路**，与单选时的按键一一对应（见 `ClipAction`）：
 *
 * - **复制**（回车）：拼成**一段多行纯文本**写进剪贴板——每条取它的文本表示（正文 →
 *   从 HTML / RTF 解析出的文字 → 文件路径），换行连接。多余选复制的结果就是一段文本，
 *   不带文件、也不带富文本表示（理由见 `copyTarget`）。
 * - **粘贴**（`⌥` 回车）：逐条写、逐条合成 `⌘V`，把这一批按顺序送进目标应用
 *   （表格逐格、终端逐条、聊天逐发）。多条时每条之后还会补一个裸回车，让目标端腾出
 *   下一处落点——见 `AppSettings.pressReturnAfterPaste`。
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
     * @param itemIds 要写回的条目，**顺序即粘贴顺序**（由调用方按列表顺序给出）。
     * @param onHidePanel 在合成粘贴之前调用，让面板先让开，使按键能到达此前聚焦的应用。
     */
    suspend operator fun invoke(
        itemIds: List<String>,
        action: ClipAction,
        onHidePanel: () -> Unit,
    ): SelectResult {
        // 不支持的修饰键组合什么都不做。
        if (action == ClipAction.UNKNOWN || itemIds.isEmpty()) return SelectResult.IGNORED

        // 条目可能在界面上停留期间被删掉；此时静默跳过，不要往剪贴板写一份空内容。
        val items = itemIds.distinct().mapNotNull { repository.item(it) }
        if (items.isEmpty()) return SelectResult.IGNORED

        val settings = repository.settings.value
        val removeFormatting = action.removesFormatting(settings)
        val pasting = action.pastes(settings)

        // 写回内容，以及每一份内容代表的那几条条目：多条复制是**一份**合并快照，
        // 因此两者不是一一对应的，分开列。
        val batch = if (pasting) {
            items.map { Write(listOf(it.id), snapshotFor(it, removeFormatting)) }
        } else {
            listOf(copyTarget(items, removeFormatting))
        }

        // 面板只让开一次：后面每一条都是同一个目标应用、同一个焦点。
        // `onHidePanel` 也正是在这里把目标 pid 交给平台层（见 `MacKeyboard.pasteTargetPid`）。
        onHidePanel()

        // 要不要在每条之后补一个裸回车：只服务「逐条」这一种用法。
        //
        // 单条粘贴**绝不**补：那是最常用的操作，而 `↵` 在 Finder 这类应用里是重命名，
        // 多发一个按键就是破坏性的。多条时才有「要腾出下一处落点」这回事。
        val trailingReturn = pasting && batch.size > 1 && settings.pressReturnAfterPaste

        val written = ArrayList<String>(items.size)
        var firstWriteFailed = false
        try {
            // 整段写回都抑制捕获：这期间剪贴板的每一次变化都是本应用自己造成的。
            repository.withoutCapturing {
                for ((index, write) in batch.withIndex()) {
                    if (!platform.writeClipboard(write.snapshot)) {
                        // 一条都没写进去才算「平台不支持」；中途失败就保留已经粘出去的那些。
                        firstWriteFailed = written.isEmpty()
                        break
                    }
                    written += write.ids

                    // 复制只会有一份要写；需要逐条跑一遍的只有粘贴。
                    if (!pasting) break

                    // 第一条要等面板真的让开、焦点回到目标应用；之后每一条要等目标应用把
                    // 上一次的 ⌘V 读完——上一条还没被取走就换掉剪贴板，粘出来的会是下一条，
                    // 甚至什么都没有。
                    delay(if (index == 0) PANEL_HIDE_DELAY_MILLIS else PASTE_INTERVAL_MILLIS)
                    if (!platform.paste()) {
                        repository.setStatusMessage("Pasting is not supported on this platform")
                        break
                    }

                    // 补一个**裸回车**：多数目标端要有一次「提交 / 换行」才会腾出下一处落点
                    // （终端执行命令、聊天框发送、Excel 下移一格、编辑器另起一行），否则下一条
                    // 只会拼在同一个位置上——甚至把前一条盖掉。
                    if (trailingReturn) {
                        delay(PASTE_INTERVAL_MILLIS)
                        platform.pressReturn()
                    }
                }

                // 收尾前多等一个轮询周期：本应用自己写下的那几次「剪贴板变化」必须被监视器
                // **在抑制期内**读走（读走即丢弃）。否则它们会在抑制解除之后才被发现，
                // 那一条就被记成两次——既进 `recordBatchCopy`，又被当成一次新复制。
                //
                // 轮询的相位是任意的，因此等满一个间隔再加一点余量。
                if (written.isNotEmpty()) {
                    delay(repository.clipboardPollIntervalMillis.toLong() + CAPTURE_SETTLE_MARGIN_MILLIS)
                }
            }
        } finally {
            // 写回期间抑制了捕获，这里补记这次批量：写出去的每一条各算一次「被复制」，
            // 并且**只重排一次**列表（否则一次三连粘要触发三次全量重排）。
            //
            // `NonCancellable`：用户中途再发起一次激活会取消这里，但已经粘出去的那几条
            // 不能白粘——面板都关了，历史里却什么都没发生。
            if (written.isNotEmpty()) {
                withContext(NonCancellable) { repository.recordBatchCopy(written) }
            }
        }

        // 所有分支都会关闭弹窗，复制也不例外（`onHidePanel` 已在上文调用）。
        if (firstWriteFailed) {
            repository.setStatusMessage("This platform can't copy that kind of content")
            return SelectResult.UNSUPPORTED
        }
        return if (pasting) SelectResult.PASTING else SelectResult.COPIED
    }

    /**
     * 不粘贴时写进剪贴板的内容。
     *
     * - **一条**：原样写回，HTML / RTF 等全部表示都带上——「单条粘贴不丢格式」是这条路径的
     *   看家本领，不能被多选的需求牵连。
     * - **多条**：只产出**一段纯文本**，每条贡献它的文本表示（有正文用正文，没有就用从
     *   HTML / RTF 里解析出来的文字，文件条目用路径），按列表顺序换行连接，见 [mergeableText]。
     *   有一条拿不出文本表示（纯图片）时退回只写最后一条：把图丢掉、只留下它的 OCR 文字，
     *   比少复制几条更糟。
     *
     * 多条刻意**不**保留 `files` / `contents`：一份"文本 + 若干文件 + 一段 HTML"的混合快照，
     * 在不同目标端会粘出完全不同的东西（聊天框收到附件、编辑器收到文本），行为不可预期。
     * 纯文本则处处一致——代价是文件在 Finder 里不再落成文件。
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
     * 条目能贡献给「合并文本」的那一段；拿不出文本表示时为 `null`（纯图片条目）。
     *
     * 三档依次是：**正文** → 从 HTML / RTF 里解析出来的文字 → **文件路径**（文件条目本身
     * 没有正文，它的文本表示就是路径）。
     *
     * 与 `ClipItem.previewableText` 的区别是**不拿识别结果充数**：把一张图的 OCR 文字
     * 掺进合并结果、同时把图本身丢掉，谁也不想要。
     */
    private fun mergeableText(item: ClipItem): String? = when {
        item.text != null -> item.text
        // 只写了 HTML / RTF、没写纯文本的那些条目（见 `ClipItem.previewableText`）。
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

    /** 一次写回：要放进剪贴板的内容，以及它代表的那几条条目。 */
    private data class Write(val ids: List<String>, val snapshot: ClipboardSnapshot)

    private companion object {
        /** 多条复合成一段文本时的分隔符；换行粘进编辑器、表格都是「一条一行」。 */
        const val ITEM_SEPARATOR = "\n"

        /** 第一次粘贴前的等待：面板要让开、焦点要回到此前的应用。 */
        const val PANEL_HIDE_DELAY_MILLIS = 160L

        /** 连续粘贴之间相邻两条的间隔：给目标应用留出取走上一次粘贴内容的时间。 */
        const val PASTE_INTERVAL_MILLIS = 140L

        /** 收尾等待在轮询间隔之上多留的余量（相位不齐的那一点点）。 */
        const val CAPTURE_SETTLE_MARGIN_MILLIS = 80L
    }
}
