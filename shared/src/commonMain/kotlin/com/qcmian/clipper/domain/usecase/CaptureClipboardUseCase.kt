package com.qcmian.clipper.domain.usecase

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.ClipboardSnapshot
import com.qcmian.clipper.domain.model.SourceApplication
import com.qcmian.clipper.domain.repository.ClipboardPlatform
import com.qcmian.clipper.domain.repository.ClipboardRepository
import com.qcmian.clipper.domain.model.AppSettings
import com.qcmian.clipper.util.currentTimeMillis
import com.qcmian.clipper.util.randomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The "record a new copy" business rule. Port of Maccy's `Clipboard.pasteboardChanged`
 * handler: it decides whether a snapshot may enter the history, merges duplicates and asks
 * for text recognition on images.
 *
 * [run] suspends for as long as it collects, so the caller's scope owns the lifecycle — the
 * state holder starts it in `viewModelScope` — instead of this use case keeping a scope and a
 * `start()` / `stop()` pair of its own.
 */
class CaptureClipboardUseCase(
    private val repository: ClipboardRepository,
    private val platform: ClipboardPlatform,
) {
    /** Collects [ClipboardRepository.snapshots] until the calling scope is cancelled. */
    suspend fun run(): Unit = coroutineScope {
        val scope: CoroutineScope = this
        repository.snapshots.collect { snapshot -> capture(scope, snapshot) }
    }

    /** Decides what a new [snapshot] becomes and stores it, or ignores it entirely. */
    private fun capture(scope: CoroutineScope, snapshot: ClipboardSnapshot) {
        if (snapshot.isEmpty) return

        val settings = repository.settings.value

        if (settings.ignoreEvents) {
            if (settings.ignoreOnlyNextEvent) {
                repository.setSettings(settings.copy(ignoreEvents = false, ignoreOnlyNextEvent = false))
            }
            return
        }

        // Port of `Clipboard.shouldIgnore(_ types:)`: transient and user listed pasteboard
        // types never reach the history.
        val ignoredTypes = settings.ignoredPasteboardTypes + AppSettings.TRANSIENT_PASTEBOARD_TYPES
        if (snapshot.types.any { it in ignoredTypes }) return

        // Maccy filters the pasteboard through `enabledPasteboardTypes`, so disabled content
        // kinds never reach the history at all.
        val text = snapshot.text.takeIf { settings.saveText }
        val image = snapshot.imageBase64.takeIf { settings.saveImages }
        val files = snapshot.files.takeIf { settings.saveFiles }.orEmpty()
        if (text.isNullOrBlank() && image == null && files.isEmpty()) return

        if (!text.isNullOrBlank() && matchesIgnoredPattern(text, settings)) return

        // Port of `Clipboard.shouldIgnore(_ sourceAppBundle:)`.
        val sourceApplication = platform.currentSourceApplication()
        if (sourceApplication != null && isIgnoredApplication(sourceApplication, settings)) return

        val items = repository.items.value
        // Wall clock resolution is a millisecond, which is not enough to keep the order of
        // copies made in quick succession (Maccy relies on sub-millisecond `Date`). Bumping
        // past the newest item guarantees a strict ordering.
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
            // Keep the identity of the original entry and only bump the counters.
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

        // Port of `HistoryItem.generateTitle()`: images get their title from text recognition.
        // Recognition runs on its own child coroutine so the next snapshot is not held up by it.
        if (image != null && settings.recognizeText && platform.supportsTextRecognition) {
            scope.launch { recognizeImageText(merged.id, image) }
        }
    }

    /** Runs Vision / ML Kit in the background and promotes the result to the item title. */
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
