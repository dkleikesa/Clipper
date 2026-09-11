package com.qcmian.clipper.macos

import com.qcmian.clipper.util.encodeBase64
import java.io.File

/**
 * Reads an application icon straight out of its bundle and converts it to a base64 PNG.
 *
 * Maccy goes through `NSWorkspace.icon(forFile:)`; reading the `.icns` and pulling the
 * largest embedded PNG chunk out of it is the dependency free equivalent and works for the
 * overwhelming majority of applications.
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

        // Binary plists and apps without CFBundleIconFile fall back to the largest icon.
        return resources.listFiles { file -> file.isFile && file.name.endsWith(".icns") }
            ?.maxByOrNull { it.length() }
    }

    /** Walks the ICNS container and returns the biggest PNG payload it holds. */
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
