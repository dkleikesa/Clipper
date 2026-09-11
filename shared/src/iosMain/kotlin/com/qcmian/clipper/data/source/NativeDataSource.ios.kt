package com.qcmian.clipper.data.source

import com.qcmian.clipper.ios.IosTextRecognition
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

private class IosNativeDataSource : NativeDataSource {
    override val supportsTextRecognition: Boolean get() = true

    override suspend fun recognizeText(imageBase64: String): String? =
        IosTextRecognition.recognize(imageBase64)

    /**
     * 用默认处理器打开「关于」对话框里的链接。只接受 `http(s)`，
     * 这样即便常量写错也绝不会把任意 scheme 交给其它应用。
     */
    override fun openUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val nsUrl = NSURL.URLWithString(url) ?: return false

        val application = UIApplication.sharedApplication
        if (!application.canOpenURL(nsUrl)) return false

        application.openURL(nsUrl, emptyMap<Any?, Any>(), null)
        return true
    }
}

actual fun createNativeDataSource(): NativeDataSource = IosNativeDataSource()
