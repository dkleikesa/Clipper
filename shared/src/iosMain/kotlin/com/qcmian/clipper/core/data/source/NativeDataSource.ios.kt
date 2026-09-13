package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.platform.ios.IosTextRecognition

private class IosNativeDataSource : NativeDataSource {
    override val supportsTextRecognition: Boolean get() = true

    override suspend fun recognizeText(image: ClipImage): String? =
        IosTextRecognition.recognize(image)
}

actual fun createNativeDataSource(): NativeDataSource = IosNativeDataSource()
