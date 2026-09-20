package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.removingUnsafeTitleScalars
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.util.currentTimeMillis
import com.qcmian.clipper.core.util.randomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 「记录一次新复制」的业务规则。 处理器：
 * 它决定一份快照能否进入历史、合并重复项，并请求对图片做文字识别。
 *
 * [run] 会一直挂起直到收集结束，因此生命周期由调用方的作用域掌管——状态持有者在
 * `viewModelScope` 中启动它——而不是让本用例自己持有作用域和一对 `start()` / `stop()`。
 */
class CaptureClipboardUseCase(
    private val repository: ClipboardRepository,
    private val platform: ClipboardPlatform,
) {
    /** 持续收集 [ClipboardRepository.snapshots]，直到调用方的作用域被取消。 */
    suspend fun run(): Unit = coroutineScope {
        val scope: CoroutineScope = this
        repository.snapshots.collect { snapshot -> capture(scope, snapshot) }
    }

    /** 决定一份新 [snapshot] 会变成什么并存入历史，或将其完全忽略。 */
    private fun capture(scope: CoroutineScope, snapshot: ClipboardSnapshot) {
        if (snapshot.isEmpty) return

        val settings = repository.settings.value

        if (settings.ignoreEvents) return

        // 对应 `Clipboard.shouldIgnore(_ types:)`：临时 / 机密 / 自动生成的内容永远不会进入
        // 历史——这是安全底线而不是设置项（见 `AppSettings.ALWAYS_IGNORED_PASTEBOARD_TYPES`）。
        if (snapshot.types.any { it in AppSettings.ALWAYS_IGNORED_PASTEBOARD_TYPES }) return

        // 内容类型不做过滤：文本 / 图片 / 文件 / 额外类型全部记录。
        val text = snapshot.text
        val image = snapshot.image
        val files = snapshot.files
        val contents = snapshot.contents
        if (text.isNullOrBlank() && image == null && files.isEmpty() && contents.isEmpty()) return

        // 对应 `NSWorkspace.frontmostApplication`：只用来标注条目来源（预览里的「应用:」一行），
        // 不参与任何过滤。
        val sourceApplication = platform.currentSourceApplication()

        val items = repository.items.value
        // 墙钟精度只有毫秒，不足以保持快速连续复制之间的顺序。
        // 这里让它越过最新条目的时间戳，以保证严格的先后顺序。
        val now = maxOf(currentTimeMillis(), (items.maxOfOrNull { it.lastCopiedAt } ?: 0L) + 1L)
        val base = ClipItem(
            id = randomId(),
            text = text,
            image = image,
            files = files,
            contents = contents,
            firstCopiedAt = now,
            lastCopiedAt = now,
            numberOfCopies = 1,
        )
        val candidate = base.copy(
            title = base.generateTitle(settings.showSpecialSymbols),
            application = sourceApplication,
        )

        val existing = items.firstOrNull { it.id != candidate.id && it.supersedes(candidate) }
        val merged = if (existing != null) {
            // 保留原条目的身份，只更新计数。省略 `id` 会让每次重复复制都换一个新身份，
            // 正在飞的识别协程按旧 id 就再也找不到自己的条目了。
            candidate.copy(
                id = existing.id,
                firstCopiedAt = existing.firstCopiedAt,
                numberOfCopies = existing.numberOfCopies + 1,
                pin = existing.pin,
                title = existing.title.ifBlank { candidate.title },
                application = existing.application ?: candidate.application,
            )
        } else {
            candidate
        }

        val updated = items.filterNot { it.id == existing?.id } + merged
        repository.setItems(updated)

        // 对应 `HistoryItem.generateTitle()`：图片的标题来自文字识别。识别放在自己的子协程里，
        // 以免阻塞下一份快照的处理。
        //
        // 只有「本来就没有文本表示」的图片才识别：条目带着 `text` / `files` 时，标题由它们的
        // 文本派生（见 `ClipItem.previewableText`），把识别结果写进去会把这部分文本从搜索里挤掉。
        //
        // `merged.title.isBlank()` 同时兼作去重：已经有标题（上一次的识别结果）就不再重跑
        // Vision——既省下一次识别，也不会把上一轮的结果覆盖回去。
        if (image != null &&
            text.isNullOrBlank() &&
            files.isEmpty() &&
            merged.title.isBlank() &&
            settings.recognizeText &&
            platform.supportsTextRecognition
        ) {
            scope.launch { recognizeImageText(merged.id, image) }
        }
    }

    /** 在后台运行 Vision / ML Kit，并把结果提升为条目标题。 */
    private suspend fun recognizeImageText(itemId: String, image: ClipImage) {
        val recognized = platform.recognizeText(image) ?: return
        // 空白判定必须发生在格式化之前：格式化会把换行换成 `⏎`，那之后 `isBlank()` 就再也
        // 认不出「只有空白」的识别结果，垃圾标题会连同工具栏按钮一起冒出来。
        if (recognized.isBlank()) return

        val items = repository.items.value
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return

        // 标题存的是识别**原文**，不是列表显示用的单行串：这个字段同时是「复制图片文字」
        // 与搜索的数据源，换成 `⏎` / `·` 会把真换行一起复制出去（见 `copyExtractedText`）。
        // 需要单行显示的地方（历史列表、置顶项行）在渲染时自行压平。
        val title = recognized
            .take(MAX_RECOGNIZED_TEXT_LENGTH)
            .removingUnsafeTitleScalars()
            .trim()
        if (title.isBlank()) return

        repository.setItems(items.toMutableList().also { it[index] = it[index].copy(title = title) })
    }

    private companion object {
        /**
         * 识别原文的长度上限，与 `ClipSearch` 模糊匹配读入的长度一致。
         *
         * 不再套用标题的 [`ClipItem.MAX_TITLE_LENGTH`]：那个上限是按「列表里的一行」定的，
         * 而这里的正文是要整段复制出去的。
         */
        const val MAX_RECOGNIZED_TEXT_LENGTH = 5_000
    }
}
