package com.qcmian.clipper.core

import com.qcmian.clipper.ios.IosTextRecognition
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

private class IosNativeIntegration : NativeIntegration {
    override val supportsTextRecognition: Boolean get() = true

    override suspend fun recognizeText(imageBase64: String): String? =
        IosTextRecognition.recognize(imageBase64)

    /**
     * Opens the About dialog links with the default handler. Only `http(s)` is accepted so a
     * malformed constant can never hand an arbitrary scheme to another application.
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

actual fun createNativeIntegration(): NativeIntegration = IosNativeIntegration()
