package com.qcmian.clipper.host

import com.qcmian.clipper.protocol.userHomeDirectory
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files

/** 单实例锁文件名；与数据库、socket 同在 `~/.clipper/` 下。 */
private const val LOCK_FILE_NAME = "clipper.lock"

/**
 * 进程级单实例闸门。
 *
 * **为什么 macOS 自带的去重不够**：系统只在 **LaunchServices** 那一层去重——Finder 双击、
 * Dock 点击、`open Clipper.app` 会命中已有实例。但 `open -n`、直接执行包内的
 * `Contents/MacOS/Clipper`、`./gradlew run`、被 launchd / 脚本拉起，全都绕开它，会实打实
 * 起出第二个进程。后果是具体的：两个实例抢同一份 SQLite（TRUNCATE 回滚日志是单写者模型，
 * 会撞 BUSY 甚至损坏）、抢注册同一套全局热键、多出一个托盘图标——而本应用是 `LSUIElement`，
 * 多出来的那个安静地赖在菜单栏里，用户很难察觉。
 *
 * **判据是文件锁，不是「文件在不在」**：`FileLock` 由内核在进程结束（含崩溃、被 kill）时
 * 自动释放，因此不会像 socket 文件那样留下需要额外判活的陈迹。它是内核态 advisory lock，
 * 只在同机、同一路径上互斥——正是要的粒度。
 *
 * 锁在 [HeldLock] 存活期间一直持有，它被本单例的 [held] 字段引用着，因而不会在进程运行期间
 * 被回收——**必须有人拿住它**，否则 channel 一旦被 GC 就等于放开了闸门。进程正常退出或崩溃
 * 时操作系统自动解锁，无需清理。
 */
object SingleInstance {

    @Volatile
    private var held: HeldLock? = null

    /**
     * 尝试成为本机唯一的实例。
     *
     * @return 拿到锁返回 `true`；已有实例持有锁返回 `false`。闸门**自身**不可用时也返回
     * `true`（放行）——见下方说明。
     */
    fun acquire(): Boolean {
        if (held != null) return true

        val file = File(userHomeDirectory(), ".clipper/$LOCK_FILE_NAME")
        var channel: FileChannel? = null
        try {
            Files.createDirectories(file.parentFile.toPath())
            val opened = RandomAccessFile(file, "rw").channel
            channel = opened
            val lock = opened.tryLock()
            if (lock == null) {
                opened.close()
                return false
            }
            held = HeldLock(opened, lock)
            return true
        } catch (e: Exception) {
            // 含 OverlappingFileLockException（同 JVM 重入）与目录只读、文件系统不支持锁等。
            // 「闸门自己坏了」不该升级成「应用打不开」：放行，退回可能多实例的旧状态，代价
            // 远小于把用户挡在门外。真正的多实例风险仍由「抢不到锁」这条主路径挡住。
            runCatching { channel?.close() }
            System.err.println("[clipper] 单实例锁不可用，按未加锁启动：${e.message}")
            return true
        }
    }

    /** 持有锁文件的所有权。字段只为把 channel / lock 钉在内存里，无需显式释放。 */
    private class HeldLock(
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : AutoCloseable {
        override fun close() {
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
    }
}
