// `Json` 与 `Cbor` 的若干配置项（`coerceInputValues` / `ignoreUnknownKeys`）仍是实验 API：
// 在这里一次性放行，免得每个配置块各写一遍。
@file:OptIn(ExperimentalSerializationApi::class)

package com.qcmian.clipper.core.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 文本载荷共用的 JSON 编解码器。
 *
 * 现在只剩偏好设置在用（`app_settings.payload`）：它是单独一行、需要人工查阅的值，
 * 可读性值这个钱。历史相关的列已经全部改成原生类型（见下）。
 *
 * [coerceInputValues] 让「枚举成员被删掉」不再是一颗炸弹：旧存档里写着已被删除的枚举值时，
 * 该字段退回属性默认值；否则整个 `decodeFromString` 会失败，[decodeJsonOrNull] 返回 `null`，
 * 偏好被整体重置为默认（用户的自定义快捷键、排序、尺寸全部丢失）。
 */
internal val ClipperJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

internal inline fun <reified T> decodeJsonOrNull(text: String?): T? =
    text?.let { runCatching { ClipperJson.decodeFromString<T>(it) }.getOrNull() }

internal inline fun <reified T> encodeJson(value: T): String = ClipperJson.encodeToString(value)

/**
 * 历史载荷里二进制结构的编解码器。
 *
 * 用于「附加表示」（HTML / RTF / PDF 等）：它们是若干 `(UTI, ByteArray?)`，要整体塞进一个
 * 数据库列。
 *
 * **为什么不是 JSON**：JSON 是文本格式，`ByteArray` 只能编成数字数组——一个字节平均要 3.6 个
 * 字符来表示（实测，连标点一起算），比 base64 还差得多。CBOR 是二进制的，原生支持字节数组，
 * 落盘即原样。
 */
internal val ClipperCbor = Cbor {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal inline fun <reified T> encodeCbor(value: T): ByteArray = ClipperCbor.encodeToByteArray(value)

internal inline fun <reified T> decodeCborOrNull(bytes: ByteArray?): T? =
    bytes?.let { runCatching { ClipperCbor.decodeFromByteArray<T>(it) }.getOrNull() }
