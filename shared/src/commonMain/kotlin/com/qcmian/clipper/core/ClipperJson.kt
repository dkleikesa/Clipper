package com.qcmian.clipper.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The JSON codec used for every persisted payload. */
internal val ClipperJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal inline fun <reified T> decodeJsonOrNull(text: String?): T? =
    text?.let { runCatching { ClipperJson.decodeFromString<T>(it) }.getOrNull() }

internal inline fun <reified T> encodeJson(value: T): String = ClipperJson.encodeToString(value)
