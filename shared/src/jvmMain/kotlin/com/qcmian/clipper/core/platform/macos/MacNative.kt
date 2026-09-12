package com.qcmian.clipper.core.platform.macos

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
    private val allocateClassPair: Function = runtime.getFunction("objc_allocateClassPair")
    private val registerClassPair: Function = runtime.getFunction("objc_registerClassPair")
    private val addMethodFunction: Function = runtime.getFunction("class_addMethod")
    private val objectGetClassName: Function = runtime.getFunction("object_getClassName")

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

    /**
     * `objc_allocateClassPair(NSObject, name, 0)`：运行时新建一个类，用来挂 target/action。
     *
     * 名字已被占用（重复安装）时返回 `null`，调用方可以退回 [clazz] 复用已有的类。
     */
    fun allocateClass(superclassName: String, name: String): Pointer? {
        val superclass = clazz(superclassName) ?: return null
        return runCatching { allocateClassPair.invokePointer(arrayOf(superclass, name, 0L)) }.getOrNull()
    }

    /** 为 [clazz] 添加一个方法；[types] 是 ObjC 类型编码，例如 `"v@:@"`。 */
    fun addMethod(clazz: Pointer?, name: String, implementation: Pointer?, types: String): Boolean {
        if (clazz == null || implementation == null) return false
        val selector = selector(name) ?: return false
        return runCatching {
            addMethodFunction.invokeInt(arrayOf(clazz, selector, implementation, types)) != 0
        }.getOrDefault(false)
    }

    /** `objc_registerClassPair`：注册后才能 `alloc` / `init`。 */
    fun registerClass(clazz: Pointer?) {
        if (clazz == null) return
        runCatching { registerClassPair.invoke(arrayOf(clazz)) }
    }

    /** `object_getClassName`：返回对象的真实类名，用于诊断 AWT 给了我们什么样的状态项。 */
    fun className(pointer: Pointer?): String? {
        if (pointer == null) return null
        return runCatching { objectGetClassName.invokePointer(arrayOf(pointer))?.getString(0) }.getOrNull()
    }

    /** 用字节数组建一个 `NSData`；`NSData` 会拷贝内容，因此临时缓冲可以随即释放。 */
    fun data(bytes: ByteArray): Pointer? {
        val clazz = clazz("NSData") ?: return null
        val allocated = send(clazz, "alloc") ?: return null
        val buffer = Memory(bytes.size.toLong())
        buffer.write(0, bytes, 0, bytes.size)
        return send(allocated, "initWithBytes:length:", buffer, bytes.size.toLong())
    }

    /**
     * 不经过结构体传参来设置 `NSImage.size`。
     *
     * `setSize:` 接收的是按值传递的 `NSSize`，JNA 传结构体按值的 ABI 跨架构不够可靠；改成
     * KVC + `NSValue.valueWithBytes:objCType:`，两个参数都是指针，与架构无关。
     */
    fun setImageSize(image: Pointer?, width: Double, height: Double) {
        val valueClass = clazz("NSValue") ?: return
        val size = Memory(16).also {
            it.setDouble(0, width)
            it.setDouble(8, height)
        }
        val encoding = Memory(16).also { it.setString(0, "{CGSize=dd}") }
        val value = send(valueClass, "valueWithBytes:objCType:", size, encoding) ?: return
        val key = nsString("size") ?: return
        send(image, "setValue:forKey:", value, key)
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
