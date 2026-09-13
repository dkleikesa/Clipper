package com.qcmian.clipper.core.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 所有持久化载荷共用的 JSON 编解码器。
 *
 * [coerceInputValues] 让「枚举成员被删掉」不再是一颗炸弹：旧存档里写着已被删除的枚举值时，
 * 该字段退回属性默认值；否则整个 `decodeFromString` 会失败，[decodeJsonOrNull] 返回 `null`，
 * 偏好被整体重置为默认（用户的自定义快捷键、排序、尺寸全部丢失）。
 */
@OptIn(ExperimentalSerializationApi::class)
internal val ClipperJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

internal inline fun <reified T> decodeJsonOrNull(text: String?): T? =
    text?.let { runCatching { ClipperJson.decodeFromString<T>(it) }.getOrNull() }

internal inline fun <reified T> encodeJson(value: T): String = ClipperJson.encodeToString(value)
