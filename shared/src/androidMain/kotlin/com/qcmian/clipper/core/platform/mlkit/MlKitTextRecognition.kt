package com.qcmian.clipper.core.platform.mlkit

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.qcmian.clipper.core.util.decodeBase64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Android 上对应 `HistoryItem.performTextRecognition()` 的实现，
 * 使用 ML Kit 的设备端拉丁文字识别器。
 */
object MlKitTextRecognition {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(imageBase64: String): String? {
        val bytes = decodeBase64(imageBase64) ?: return null
        if (bytes.isEmpty()) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            result.text.trim().ifBlank { null }
        } catch (cancellation: CancellationException) {
            // `runCatching` 会吞掉取消异常，破坏结构化并发。
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }
}
