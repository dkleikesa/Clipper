package com.qcmian.clipper.macos

import com.qcmian.clipper.util.encodeBase64
import java.io.File

/**
 * 直接从应用包中读取图标并转成 base64 PNG。
 *
 * Maccy 走的是 `NSWorkspace.icon(forFile:)`；这里读取 `.icns` 并从中取出内嵌的最大 PNG 块，
 * 是无依赖的等价方案，并且对绝大多数应用都有效。
 */
object MacAppIcon {
    private val pngChunkTypes = setOf("ic07", "ic08", "ic09", "ic10", "ic11", "ic12", "ic13", "ic14")

    fun iconBase64(bundleId: String): String? {
        val bundlePath = MacWorkspace.applicationPath(bundleId) ?: return null
        val bundle = File(bundlePath)
        val icns = findIcns(bundle) ?: return null
        val png = runCatching { largestPng(icns.readBytes()) }.getOrNull() ?: return null
        return encodeBase64(png)
    }

    private fun findIcns(bundle: File): File? {
        val resources = File(bundle, "Contents/Resources")
        if (!resources.isDirectory) return null

        val declared = runCatching {
            val plist = File(bundle, "Contents/Info.plist")
            if (!plist.isFile) null else {
                Regex("<key>CFBundleIconFile</key>\\s*<string>([^<]+)</string>")
                    .find(plist.readText())
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            }
        }.getOrNull()

        if (declared != null) {
            val name = if (declared.endsWith(".icns", ignoreCase = true)) declared else "$declared.icns"
            val candidate = File(resources, name)
            if (candidate.isFile) return candidate
        }

        // 二进制 plist 与没有 CFBundleIconFile 的应用，退回到取最大的图标。
        return resources.listFiles { file -> file.isFile && file.name.endsWith(".icns") }
            ?.maxByOrNull { it.length() }
    }

    /** 遍历 ICNS 容器，返回其中最大的 PNG 载荷。 */
    private fun largestPng(data: ByteArray): ByteArray? {
        if (data.size < 8) return null
        if (String(data, 0, 4, Charsets.US_ASCII) != "icns") return null

        var best: ByteArray? = null
        var offset = 8
        while (offset + 8 <= data.size) {
            val type = String(data, offset, 4, Charsets.US_ASCII)
            val length = readInt(data, offset + 4)
            if (length < 8 || offset + length > data.size) break

            if (type in pngChunkTypes) {
                val payload = data.copyOfRange(offset + 8, offset + length)
                if (isPng(payload) && (best == null || payload.size > best.size)) {
                    best = payload
                }
            }
            offset += length
        }
        return best
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}
