package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.contentKeyOf
import com.qcmian.clipper.core.domain.model.toMeta
import com.qcmian.clipper.core.domain.model.toStoredTitle
import com.qcmian.clipper.core.domain.model.toPayload
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
 * 与单表时代的关键差别：**去重不再扫描整份历史**。一次复制的内容由
 * [contentKeyOf] 归纳成一个摘要，重复复制被下推成一次 `WHERE contentKey = ?` 等值查询；
 * 命中时只改统计列，连载荷都不重写。这是「历史不再全量驻留内存」的前提。
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
    private suspend fun capture(scope: CoroutineScope, snapshot: ClipboardSnapshot) {
        if (snapshot.isEmpty) return

        // 自己写回剪贴板造成的快照不算新复制：那几条由激活路径统一记账（见 `recordBatchCopy`）。
        if (repository.isWritingClipboard.value) return

        val settings = repository.settings.value

        if (settings.ignoreEvents) return

        // 临时 / 机密 / 自动生成的内容永远不会进入
        // 历史——这是安全底线而不是设置项（见 `AppSettings.ALWAYS_IGNORED_PASTEBOARD_TYPES`）。
        if (snapshot.types.any { it in AppSettings.ALWAYS_IGNORED_PASTEBOARD_TYPES }) return

        // 内容类型不做过滤：文本 / 图片 / 文件 / 额外类型全部记录。
        val text = snapshot.text
        val image = snapshot.image
        val files = snapshot.files
        val contents = snapshot.contents
        if (text.isNullOrBlank() && image == null && files.isEmpty() && contents.isEmpty()) return

        // 只用来标注条目来源（预览里的「应用:」一行），
        // 不参与任何过滤。
        val sourceApplication = platform.currentSourceApplication()

        // 墙钟精度只有毫秒，不足以保持快速连续复制之间的顺序。这里让它越过历史里最大的
        // 时间戳，以保证严格的先后顺序（这也是唯一一处需要读全表的统计）。
        val now = maxOf(currentTimeMillis(), repository.latestLastCopiedAt() + 1L)

        val contentKey = contentKeyOf(text, image, files, contents)
        val existingId = repository.findByContentKey(contentKey)

        if (existingId != null) {
            // 保留原条目的身份，只更新计数。省略 id 会让每次重复复制都换一个新身份，
            // 正在飞的识别协程按旧 id 就再也找不到自己的条目了。
            val existing = repository.meta(existingId)
            repository.updateStats(
                id = existingId,
                numberOfCopies = (existing?.numberOfCopies ?: 0) + 1,
                lastCopiedAt = now,
            )
            // 上一次识别没能产出标题（标题仍为空）时再试一次：Vision 偶发失败不该让这张图
            // 永远搜不到。
            if (
                existing != null &&
                shouldRecognize(
                    image = image,
                    text = text,
                    files = files,
                    // 走到这里说明这一条还没有标题；而附加表示能提出文字的话标题早就有值了，
                    // 所以不必再判一次——没有标题就意味着可提取的文字也没有。
                    hasRichText = false,
                    currentTitle = existing.title,
                    settings = settings,
                )
            ) {
                scope.launch { recognizeImageText(existingId, image!!) }
            }
            return
        }

        val base = ClipItem(
            id = randomId(),
            text = text,
            image = image,
            files = files,
            contents = contents,
            firstCopiedAt = now,
            lastCopiedAt = now,
            numberOfCopies = 1,
            application = sourceApplication,
        )
        repository.insert(base.toMeta(), base.toPayload())

        // 图片的标题来自文字识别。识别放在自己的子协程里，
        // 以免阻塞下一份快照的处理。
        //
        // 只有「本来就没有可读文本」的图片才识别：条目带着 `text` / `files` / 可提取文字的
        // 附加表示时，标题由它们派生（见 `ClipItem.previewableText`），把识别结果写进去
        // 只会把这部分文字从标题里挤掉。
        if (
            shouldRecognize(
                image = image,
                text = text,
                files = files,
                hasRichText = base.hasReadableText,
                currentTitle = "",
                settings = settings,
            )
        ) {
            scope.launch { recognizeImageText(base.id, image!!) }
        }
    }

    /**
     * 是否该为这张图片跑一次文字识别。
     *
     * 判据是「这条本来就没有可读的文本表示」——[text]、[files] 与 [hasRichText] 三样都空。
     * 任何一种存在时，标题都由它们派生（见 `ClipItem.previewableText`），识别结果写进去
     * 只会把这部分文字从标题里挤掉。
     *
     * [currentTitle] 为空是必要条件：已经有标题（上一次的识别结果）就不再重跑 Vision——
     * 既省下一次识别，也不会把上一轮的结果覆盖回去。
     */
    private fun shouldRecognize(
        image: ClipImage?,
        text: String?,
        files: List<String>,
        hasRichText: Boolean,
        currentTitle: String,
        settings: AppSettings,
    ): Boolean = image != null &&
        text.isNullOrBlank() &&
        files.isEmpty() &&
        !hasRichText &&
        currentTitle.isBlank() &&
        settings.recognizeText &&
        platform.supportsTextRecognition

    /** 在后台运行 Vision / ML Kit，并把结果拆成「完整原文 + 前一段标题」两处存下来。 */
    private suspend fun recognizeImageText(itemId: String, image: ClipImage) {
        val recognized = platform.recognizeText(image) ?: return
        // 空白判定必须发生在格式化之前：格式化会把换行换成 `⏎`，那之后 `isBlank()` 就再也
        // 认不出「只有空白」的识别结果，垃圾标题会连同工具栏按钮一起冒出来。
        if (recognized.isBlank()) return

        // 标题是给列表看的一行：过滤不安全标量、按行宽截断，并压掉首尾空白。
        val title = recognized.toStoredTitle().trim()
        if (title.isBlank()) return

        // 条目可能在识别期间被删掉（那两条 `UPDATE` 都会安全地作用在 0 行上），也可能已经
        // 因为重复复制而有了别的标题；两种情况都不该把这里的旧结果写回去。
        val current = repository.meta(itemId) ?: return
        if (current.hasRecognizedText) return

        // 完整原文进载荷（「复制图片文字」与预览读它），标题进元数据（列表与搜索读它）。
        //
        // 原文**不设长度上限**：它比同一张图的原图 BLOB 小两个数量级（几十 KB 对几 MB），
        // 又待在载荷里、不占常驻内存；单独掐它，只会把最需要完整保存的滚动长截图砍掉。
        repository.updateRecognizedText(id = itemId, fullText = recognized, title = title)
    }
}
