package com.qcmian.clipper.core.data.source

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.util.encodeBase64

/**
 * Android 剪贴板支持。
 *
 * 注意：从 Android 10 起，`OnPrimaryClipChangedListener` 只在应用处于前台时才会被调用，
 * 因此其它应用里的复制会等 Clipper 下次回到前台时才补采。
 */
private class AndroidClipboardDataSource(private val context: Context) : ClipboardDataSource {
    private val manager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var listener: ((ClipboardSnapshot) -> Unit)? = null
    private var lastFingerprint: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { notifyChange() }

    override val supportsImages: Boolean get() = true
    override val supportsFiles: Boolean get() = true

    override fun write(snapshot: ClipboardSnapshot): Boolean {
        val clip = when {
            !snapshot.text.isNullOrEmpty() -> ClipData.newPlainText(LABEL, snapshot.text)
            snapshot.files.isNotEmpty() -> ClipData.newRawUri(LABEL, Uri.parse(snapshot.files.first()))
            else -> return false
        }
        // 这里刻意不刷新指纹，好让变更监听器捕获到这次写入，
        // 从而让仓库把选中的条目重新排到最前面。
        return runCatching { manager.setPrimaryClip(clip) }.isSuccess
    }

    override fun clear() {
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(LABEL, ""))
        }
        lastFingerprint = null
    }

    override fun start(onChange: (ClipboardSnapshot) -> Unit) {
        listener = onChange
        lastFingerprint = fingerprint(readSnapshot())
        runCatching { manager.addPrimaryClipChangedListener(clipListener) }
    }

    override fun stop() {
        runCatching { manager.removePrimaryClipChangedListener(clipListener) }
        listener = null
    }

    override fun paste(): Boolean = false

    private fun notifyChange() {
        val snapshot = readSnapshot()
        if (snapshot.isEmpty) return
        val current = fingerprint(snapshot)
        if (current == lastFingerprint) return
        lastFingerprint = current
        listener?.invoke(snapshot)
    }

    private fun readSnapshot(): ClipboardSnapshot {
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return ClipboardSnapshot()
        if (clip.itemCount == 0) return ClipboardSnapshot()

        val item = clip.getItemAt(0)
        val rawText = runCatching { item.coerceToText(context)?.toString() }.getOrNull()
        val uri = item.uri

        var imageBase64: String? = null
        var files = emptyList<String>()

        if (uri != null) {
            when (uri.scheme) {
                "file" -> files = listOfNotNull(uri.path)
                "content" -> {
                    val mimeType = runCatching { context.contentResolver.getType(uri) }
                        .getOrNull()
                        .orEmpty()
                    if (mimeType.startsWith("image/")) {
                        imageBase64 = runCatching {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }.getOrNull()?.let { encodeBase64(it) }
                    }
                }
            }
        }

        // `coerceToText` 会把图片 URI 变成它的字符串形式，那不是一个有用的标题。
        val text = if (imageBase64 != null && rawText == uri.toString()) null else rawText

        // `ClipDescription.getMimeTypes()` 在较新的 SDK 中已被移除，
        // 因此改为逐个遍历各个条目。
        val description = clip.description
        val types = (0 until description.mimeTypeCount).map { description.getMimeType(it) }
        return ClipboardSnapshot(text = text, imageBase64 = imageBase64, files = files, types = types)
    }

    private companion object {
        const val LABEL = "Clipper"

        fun fingerprint(snapshot: ClipboardSnapshot): String = buildString {
            append(snapshot.text.orEmpty())
            append('\u0000')
            append(snapshot.imageBase64?.length ?: 0)
            append('\u0000')
            append(snapshot.files.joinToString("\u0001"))
        }
    }
}

actual fun createClipboardDataSource(): ClipboardDataSource =
    ClipperAndroid.appContext?.let { AndroidClipboardDataSource(it) } ?: FallbackClipboardDataSource()
