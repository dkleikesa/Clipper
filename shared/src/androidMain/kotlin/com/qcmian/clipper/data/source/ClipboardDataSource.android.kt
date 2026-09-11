package com.qcmian.clipper.data.source

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.qcmian.clipper.domain.model.ClipboardSnapshot
import com.qcmian.clipper.util.encodeBase64

/**
 * Android clipboard support.
 *
 * Note that since Android 10 the `OnPrimaryClipChangedListener` is only invoked while the
 * application has focus, so copies made in other applications are picked up the next time
 * Clipper comes to the foreground.
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
        // The fingerprint is intentionally not refreshed so the change listener picks the
        // write up and the repository moves the selected item back to the top.
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

        // `coerceToText` turns an image URI into its string form, which is not a useful title.
        val text = if (imageBase64 != null && rawText == uri.toString()) null else rawText

        // `ClipDescription.getMimeTypes()` was removed in recent SDKs; walk the individual
        // entries instead.
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
