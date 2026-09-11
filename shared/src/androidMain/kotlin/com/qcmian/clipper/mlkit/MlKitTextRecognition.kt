package com.qcmian.clipper.mlkit

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.qcmian.clipper.util.decodeBase64
import kotlinx.coroutines.tasks.await

/**
 * Port of `HistoryItem.performTextRecognition()` on Android, using ML Kit's on-device
 * Latin text recogniser.
 */
object MlKitTextRecognition {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(imageBase64: String): String? {
        val bytes = decodeBase64(imageBase64) ?: return null
        if (bytes.isEmpty()) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        return runCatching {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            result.text.trim().ifBlank { null }
        }.getOrNull()
    }
}
