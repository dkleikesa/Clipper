package com.qcmian.clipper.ios

import com.qcmian.clipper.data.source.toNSData
import com.qcmian.clipper.util.decodeBase64
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelFast

/**
 * Port of `HistoryItem.performTextRecognition()` on top of the Vision framework, which is
 * available on every supported iOS version.
 */
object IosTextRecognition {
    @OptIn(ExperimentalForeignApi::class)
    fun recognize(imageBase64: String): String? {
        val bytes = decodeBase64(imageBase64) ?: return null
        if (bytes.isEmpty()) return null

        val request = VNRecognizeTextRequest().apply {
            recognitionLevel = VNRequestTextRecognitionLevelFast
        }
        val handler = VNImageRequestHandler(data = bytes.toNSData(), options = emptyMap<Any?, Any>())

        val performed = runCatching { handler.performRequests(listOf(request), null) }.getOrDefault(false)
        if (!performed) return null

        val observations = request.results.orEmpty().filterIsInstance<VNRecognizedTextObservation>()
        val lines = observations.mapNotNull { observation ->
            val candidate = observation.topCandidates(1u).firstOrNull() as? VNRecognizedText
            candidate?.string?.trim()
        }.filter { it.isNotEmpty() }

        return lines.joinToString("\n").ifBlank { null }
    }
}
