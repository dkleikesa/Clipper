package com.qcmian.clipper.macos

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * A very small Objective-C runtime bridge built on JNA.
 *
 * Only the handful of `objc_msgSend` shapes this project needs are exposed. Every call is
 * defensive: when the runtime or a class is missing the helper returns `null` so the caller
 * degrades gracefully instead of crashing.
 */
internal object MacNative {
    private val runtime: NativeLibrary = runCatching {
        NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib")
    }.getOrElse { NativeLibrary.getProcess() }

    private val msgSend: Function = runtime.getFunction("objc_msgSend")
    private val getClass: Function = runtime.getFunction("objc_getClass")
    private val registerName: Function = runtime.getFunction("sel_registerName")

    /** Forces a framework to load so [clazz] can find its classes. */
    fun loadFramework(path: String): Boolean =
        runCatching { NativeLibrary.getInstance(path); true }.getOrDefault(false)

    fun clazz(name: String): Pointer? =
        runCatching { getClass.invokePointer(arrayOf(name)) }.getOrNull()

    fun selector(name: String): Pointer? =
        runCatching { registerName.invokePointer(arrayOf(name)) }.getOrNull()

    /** `objc_msgSend` returning an object pointer. */
    fun send(receiver: Pointer?, name: String, vararg args: Any?): Pointer? {
        if (receiver == null) return null
        val selector = selector(name) ?: return null
        return runCatching { msgSend.invokePointer(arrayOf(receiver, selector, *args)) }.getOrNull()
    }

    /** `objc_msgSend` returning an `NSInteger` / `NSUInteger`. */
    fun sendLong(receiver: Pointer?, name: String, vararg args: Any?): Long {
        if (receiver == null) return 0L
        val selector = selector(name) ?: return 0L
        return runCatching {
            msgSend.invoke(Long::class.java, arrayOf(receiver, selector, *args)) as Long
        }.getOrDefault(0L)
    }

    /** `objc_msgSend` returning a `BOOL`, which is one byte on every supported architecture. */
    fun sendBool(receiver: Pointer?, name: String, vararg args: Any?): Boolean {
        if (receiver == null) return false
        val selector = selector(name) ?: return false
        return runCatching {
            (msgSend.invoke(Byte::class.java, arrayOf(receiver, selector, *args)) as Byte).toInt() != 0
        }.getOrDefault(false)
    }

    /** Allocates an `NSString` from UTF-8 bytes; JNA's implicit encoding is not relied upon. */
    fun nsString(value: String): Pointer? {
        val clazz = clazz("NSString") ?: return null
        val allocated = send(clazz, "alloc") ?: return null
        val bytes = value.encodeToByteArray()
        val buffer = Memory((bytes.size + 1).toLong())
        buffer.write(0, bytes, 0, bytes.size)
        buffer.setByte(bytes.size.toLong(), 0)
        return send(allocated, "initWithUTF8String:", buffer)
    }

    /** Reads an `NSString` through `UTF8String`. */
    fun string(pointer: Pointer?): String? {
        val utf8 = send(pointer, "UTF8String") ?: return null
        return runCatching { utf8.getString(0) }.getOrNull()
    }

    /** Materialises an `NSArray` into a list of element pointers. */
    fun array(pointer: Pointer?): List<Pointer> {
        if (pointer == null) return emptyList()
        val count = sendLong(pointer, "count")
        if (count <= 0) return emptyList()
        return (0 until count).mapNotNull { send(pointer, "objectAtIndex:", it) }
    }

    /** Allocates an `NSMutableArray` filled with the given strings. */
    fun stringArray(values: List<String>): Pointer? {
        val array = send(clazz("NSMutableArray"), "array") ?: return null
        values.forEach { value ->
            val string = nsString(value) ?: return@forEach
            send(array, "addObject:", string)
        }
        return array
    }
}
