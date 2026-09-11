package com.qcmian.clipper.macos

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * 建立在 JNA 之上的一个非常小的 Objective-C 运行时桥接。
 *
 * 只暴露本项目需要的那几种 `objc_msgSend` 形态。每次调用都是防御式的：
 * 当运行时或某个类缺失时，辅助函数返回 `null`，让调用方优雅降级而不是崩溃。
 */
internal object MacNative {
    private val runtime: NativeLibrary = runCatching {
        NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib")
    }.getOrElse { NativeLibrary.getProcess() }

    private val msgSend: Function = runtime.getFunction("objc_msgSend")
    private val getClass: Function = runtime.getFunction("objc_getClass")
    private val registerName: Function = runtime.getFunction("sel_registerName")

    /** 强制加载某个框架，使 [clazz] 能找到它的类。 */
    fun loadFramework(path: String): Boolean =
        runCatching { NativeLibrary.getInstance(path); true }.getOrDefault(false)

    fun clazz(name: String): Pointer? =
        runCatching { getClass.invokePointer(arrayOf(name)) }.getOrNull()

    fun selector(name: String): Pointer? =
        runCatching { registerName.invokePointer(arrayOf(name)) }.getOrNull()

    /** 返回对象指针的 `objc_msgSend`。 */
    fun send(receiver: Pointer?, name: String, vararg args: Any?): Pointer? {
        if (receiver == null) return null
        val selector = selector(name) ?: return null
        return runCatching { msgSend.invokePointer(arrayOf(receiver, selector, *args)) }.getOrNull()
    }

    /** 返回 `NSInteger` / `NSUInteger` 的 `objc_msgSend`。 */
    fun sendLong(receiver: Pointer?, name: String, vararg args: Any?): Long {
        if (receiver == null) return 0L
        val selector = selector(name) ?: return 0L
        return runCatching {
            msgSend.invoke(Long::class.java, arrayOf(receiver, selector, *args)) as Long
        }.getOrDefault(0L)
    }

    /** 返回 `BOOL` 的 `objc_msgSend`；在所有受支持的架构上它都是一个字节。 */
    fun sendBool(receiver: Pointer?, name: String, vararg args: Any?): Boolean {
        if (receiver == null) return false
        val selector = selector(name) ?: return false
        return runCatching {
            (msgSend.invoke(Byte::class.java, arrayOf(receiver, selector, *args)) as Byte).toInt() != 0
        }.getOrDefault(false)
    }

    /** 用 UTF-8 字节分配一个 `NSString`；不依赖 JNA 的隐式编码。 */
    fun nsString(value: String): Pointer? {
        val clazz = clazz("NSString") ?: return null
        val allocated = send(clazz, "alloc") ?: return null
        val bytes = value.encodeToByteArray()
        val buffer = Memory((bytes.size + 1).toLong())
        buffer.write(0, bytes, 0, bytes.size)
        buffer.setByte(bytes.size.toLong(), 0)
        return send(allocated, "initWithUTF8String:", buffer)
    }

    /** 通过 `UTF8String` 读取一个 `NSString`。 */
    fun string(pointer: Pointer?): String? {
        val utf8 = send(pointer, "UTF8String") ?: return null
        return runCatching { utf8.getString(0) }.getOrNull()
    }

    /** 把 `NSArray` 实体化为元素指针列表。 */
    fun array(pointer: Pointer?): List<Pointer> {
        if (pointer == null) return emptyList()
        val count = sendLong(pointer, "count")
        if (count <= 0) return emptyList()
        return (0 until count).mapNotNull { send(pointer, "objectAtIndex:", it) }
    }

    /** 用给定字符串填充并分配一个 `NSMutableArray`。 */
    fun stringArray(values: List<String>): Pointer? {
        val array = send(clazz("NSMutableArray"), "array") ?: return null
        values.forEach { value ->
            val string = nsString(value) ?: return@forEach
            send(array, "addObject:", string)
        }
        return array
    }
}
