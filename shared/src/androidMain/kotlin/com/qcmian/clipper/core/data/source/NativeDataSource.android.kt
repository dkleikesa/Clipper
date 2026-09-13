package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.platform.mlkit.MlKitTextRecognition

private class AndroidNativeDataSource : NativeDataSource {
    override val supportsTextRecognition: Boolean get() = true

    override suspend fun recognizeText(image: ClipImage): String? =
        MlKitTextRecognition.recognize(image)
}

actual fun createNativeDataSource(): NativeDataSource = AndroidNativeDataSource()
