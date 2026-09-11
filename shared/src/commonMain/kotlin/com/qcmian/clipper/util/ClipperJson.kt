package com.qcmian.clipper.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 所有持久化载荷共用的 JSON 编解码器。 */
internal val ClipperJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal inline fun <reified T> decodeJsonOrNull(text: String?): T? =
    text?.let { runCatching { ClipperJson.decodeFromString<T>(it) }.getOrNull() }

internal inline fun <reified T> encodeJson(value: T): String = ClipperJson.encodeToString(value)
