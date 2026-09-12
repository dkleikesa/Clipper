package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.SourceApplication
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

        if (settings.ignoreEvents) {
            if (settings.ignoreOnlyNextEvent) {
                repository.setSettings(settings.copy(ignoreEvents = false, ignoreOnlyNextEvent = false))
            }
            return
        }

        // 对应 `Clipboard.shouldIgnore(_ types:)`：临时类型与用户列出的粘贴板类型
        // 永远不会进入历史。
        val ignoredTypes = settings.ignoredPasteboardTypes + AppSettings.TRANSIENT_PASTEBOARD_TYPES
        if (snapshot.types.any { it in ignoredTypes }) return

        // 被关闭的内容类型根本不会进入历史。
        val text = snapshot.text.takeIf { settings.saveText }
        val image = snapshot.imageBase64.takeIf { settings.saveImages }
        val files = snapshot.files.takeIf { settings.saveFiles }.orEmpty()
        if (text.isNullOrBlank() && image == null && files.isEmpty()) return

        if (!text.isNullOrBlank() && matchesIgnoredPattern(text, settings)) return

        // 对应 `Clipboard.shouldIgnore(_ sourceAppBundle:)`。
        val sourceApplication = platform.currentSourceApplication()
        if (sourceApplication != null && isIgnoredApplication(sourceApplication, settings)) return

        val items = repository.items.value
        // 墙钟精度只有毫秒，不足以保持快速连续复制之间的顺序。
        // 这里让它越过最新条目的时间戳，以保证严格的先后顺序。
        val now = maxOf(currentTimeMillis(), (items.maxOfOrNull { it.lastCopiedAt } ?: 0L) + 1L)
        val base = ClipItem(
            id = randomId(),
            text = text,
            imageBase64 = image,
            files = files,
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
            // 保留原条目的身份，只更新计数。
            candidate.copy(
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

        // 对应 `HistoryItem.generateTitle()`：图片的标题来自文字识别。
        // 识别放在自己的子协程里，以免阻塞下一份快照的处理。
        if (image != null && settings.recognizeText && platform.supportsTextRecognition) {
            scope.launch { recognizeImageText(merged.id, image) }
        }
    }

    /** 在后台运行 Vision / ML Kit，并把结果提升为条目标题。 */
    private suspend fun recognizeImageText(itemId: String, imageBase64: String) {
        val recognized = platform.recognizeText(imageBase64) ?: return
        val items = repository.items.value
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return

        val title = recognized
            .replace("\n", "\u23ce")
            .take(ClipItem.MAX_TITLE_LENGTH)
        if (title.isBlank()) return

        repository.setItems(items.toMutableList().also { it[index] = it[index].copy(title = title) })
    }

    private fun matchesIgnoredPattern(text: String, settings: AppSettings): Boolean =
        settings.ignoredRegexp.any { pattern ->
            runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
        }

    private fun isIgnoredApplication(
        application: SourceApplication,
        settings: AppSettings,
    ): Boolean {
        val keys = setOfNotNull(application.bundleId, application.name)
        val listed = settings.ignoredApps.any { it in keys }
        return if (settings.ignoreAllAppsExceptListed) !listed else listed
    }
}
