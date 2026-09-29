package com.qcmian.clipper.core.util

/**
 * 持有 [lock] 执行 [block]，返回它的结果。
 *
 * 位图缓存会被两条线程碰：解码在后台线程，查询在组合线程。`LinkedHashMap` 的并发读写会损坏
 * 内部结构（可能让后续的 `get` 死循环），因此访问必须串行化。Kotlin 的 `synchronized`
 * 只有 JVM 有，这里用 expect/actual 补一个最小可用的版本。
 *
 * 刻意不做成 `inline`：调用点在缓存读写上，一次 lambda 分配无关紧要，而 `inline` 的
 * expect/actual 在跨平台声明上有额外限制。
 */
internal expect fun <T> withLock(lock: Any, block: () -> T): T
