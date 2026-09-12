package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.platform.ios.IosTextRecognition

private class IosNativeDataSource : NativeDataSource {
    override val supportsTextRecognition: Boolean get() = true

    override suspend fun recognizeText(imageBase64: String): String? =
        IosTextRecognition.recognize(imageBase64)
}

actual fun createNativeDataSource(): NativeDataSource = IosNativeDataSource()
