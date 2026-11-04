package com.qcmian.logviewer.core.doc

import java.nio.charset.Charset

/** JVM GBK 解码（支持范围，流式零拷贝） */
actual fun decodeGbk(bytes: ByteArray, strict: Boolean, offset: Int, length: Int): String? = try {
    val s = String(bytes, offset, length, Charset.forName("GBK"))
    if (strict && s.contains('\uFFFD')) null else s
} catch (_: Exception) {
    null
}
