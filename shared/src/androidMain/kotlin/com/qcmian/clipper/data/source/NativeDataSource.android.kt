package com.qcmian.clipper.data.source

import android.content.Intent
import android.net.Uri
import com.qcmian.clipper.mlkit.MlKitTextRecognition

private class AndroidNativeDataSource : NativeDataSource {
    override val supportsTextRecognition: Boolean get() = true

    override suspend fun recognizeText(imageBase64: String): String? =
        MlKitTextRecognition.recognize(imageBase64)

    /**
     * 用默认浏览器打开「关于」对话框里的链接。只接受 `http(s)`，
     * 这样即便常量写错也绝不会把任意 scheme 交给其它应用。
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
