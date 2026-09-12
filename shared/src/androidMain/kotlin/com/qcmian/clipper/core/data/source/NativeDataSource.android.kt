package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.platform.mlkit.MlKitTextRecognition

private class AndroidNativeDataSource : NativeDataSource {
    override val supportsTextRecognition: Boolean get() = true

    override suspend fun recognizeText(imageBase64: String): String? =
        MlKitTextRecognition.recognize(imageBase64)
}

actual fun createNativeDataSource(): NativeDataSource = AndroidNativeDataSource()
