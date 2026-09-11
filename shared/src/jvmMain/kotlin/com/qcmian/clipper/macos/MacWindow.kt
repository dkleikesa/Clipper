package com.qcmian.clipper.macos

import com.qcmian.clipper.core.ScreenRect
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Port of Maccy's `NSRunningApplication.windowFrame`: looks up the bounds of the main window
 * of the frontmost application through `CGWindowListCopyWindowInfo`.
 *
 * Every call is defensive and degrades to `null` when CoreGraphics or CoreFoundation is not
 * available, so the caller simply falls back to another popup position.
 */
object MacWindow {
    /** `kCGWindowListOptionOnScreenOnly`. */
    private const val WINDOW_LIST_ON_SCREEN_ONLY = 1

    /** `kCGWindowListExcludeDesktopElements`. */
    private const val WINDOW_LIST_EXCLUDE_DESKTOP_ELEMENTS = 16

    /** `kCFStringEncodingUTF8`. */
    private const val CF_STRING_ENCODING_UTF8 = 0x0800_0100

    /** `kCFNumberDoubleType`, `CFNumberGetValue` converts any CFNumber to it. */
    private const val CF_NUMBER_DOUBLE_TYPE = 13

    private val coreGraphics: NativeLibrary? = runCatching {
        NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
    }.getOrNull()

    private val coreFoundation: NativeLibrary? = runCatching {
        NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    }.getOrNull()

    private val windowListCopy: Function? = coreGraphics?.getFunction("CGWindowListCopyWindowInfo")
    private val arrayCount: Function? = coreFoundation?.getFunction("CFArrayGetCount")
    private val arrayValue: Function? = coreFoundation?.getFunction("CFArrayGetValueAtIndex")
    private val dictionaryGetValue: Function? = coreFoundation?.getFunction("CFDictionaryGetValue")
    private val stringCreate: Function? = coreFoundation?.getFunction("CFStringCreateWithCString")
    private val numberGetValue: Function? = coreFoundation?.getFunction("CFNumberGetValue")
    private val release: Function? = coreFoundation?.getFunction("CFRelease")

    /** Bounds of the first on-screen window owned by [pid], in screen points. */
    fun frontmostWindowRect(pid: Long): ScreenRect? {
        val listCopy = windowListCopy ?: return null
        if (pid <= 0) return null

        val options = WINDOW_LIST_ON_SCREEN_ONLY or WINDOW_LIST_EXCLUDE_DESKTOP_ELEMENTS
        val array = runCatching { listCopy.invokePointer(arrayOf(options, 0)) }.getOrNull() ?: return null

        try {
            val count = runCatching { arrayCount?.invokeLong(arrayOf(array)) ?: 0L }.getOrDefault(0L)
            val pidKey = cfString("kCGWindowOwnerPID") ?: return null
            val boundsKey = cfString("kCGWindowBounds") ?: return null
            val xKey = cfString("X") ?: return null
            val yKey = cfString("Y") ?: return null
            val widthKey = cfString("Width") ?: return null
            val heightKey = cfString("Height") ?: return null

            for (index in 0 until count) {
                val info = runCatching { arrayValue?.invokePointer(arrayOf(array, index)) }.getOrNull()
                    ?: continue
                if (cfNumber(dictionaryEntry(info, pidKey))?.toLong() != pid) continue

                val bounds = dictionaryEntry(info, boundsKey) ?: continue
                val x = cfNumber(dictionaryEntry(bounds, xKey)) ?: continue
                val y = cfNumber(dictionaryEntry(bounds, yKey)) ?: continue
                val width = cfNumber(dictionaryEntry(bounds, widthKey)) ?: continue
                val height = cfNumber(dictionaryEntry(bounds, heightKey)) ?: continue
                if (width < 2.0 || height < 2.0) continue

                return ScreenRect(x.toInt(), y.toInt(), width.toInt(), height.toInt())
            }
        } finally {
            runCatching { release?.invokeVoid(arrayOf(array)) }
        }

        return null
    }

    private fun cfString(value: String): Pointer? =
        runCatching {
            stringCreate?.invokePointer(arrayOf<Any?>(null, value, CF_STRING_ENCODING_UTF8))
        }.getOrNull()

    private fun dictionaryEntry(dictionary: Pointer?, key: Pointer?): Pointer? {
        if (dictionary == null || key == null) return null
        return runCatching { dictionaryGetValue?.invokePointer(arrayOf(dictionary, key)) }.getOrNull()
    }

    private fun cfNumber(number: Pointer?): Double? {
        if (number == null) return null
        val memory = Memory(8)
        val result = runCatching {
            numberGetValue?.invoke(Byte::class.java, arrayOf(number, CF_NUMBER_DOUBLE_TYPE, memory))
        }.getOrNull()
        val converted = (result as? Byte)?.toInt() ?: return null
        return if (converted != 0) memory.getDouble(0) else null
    }
}
