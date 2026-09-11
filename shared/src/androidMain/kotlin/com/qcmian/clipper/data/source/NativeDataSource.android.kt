package com.qcmian.clipper.data.source

import android.content.Intent
import android.net.Uri
import com.qcmian.clipper.mlkit.MlKitTextRecognition

private class AndroidNativeDataSource : NativeDataSource {
    override val supportsTextRecognition: Boolean get() = true

    override suspend fun recognizeText(imageBase64: String): String? =
        MlKitTextRecognition.recognize(imageBase64)

    /**
     * Opens the About dialog links with the default browser. Only `http(s)` is accepted so a
     * malformed constant can never hand an arbitrary scheme to another application.
     */
    override fun openUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val context = ClipperAndroid.appContext ?: return false

        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)
    }
}

actual fun createNativeDataSource(): NativeDataSource = AndroidNativeDataSource()
