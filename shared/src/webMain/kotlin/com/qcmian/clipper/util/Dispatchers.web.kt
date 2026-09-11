package com.qcmian.clipper.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Web 目标（JS 与 Wasm）是单线程的，没有 IO 调度器；
 * 基于事件循环的 [Dispatchers.Default] 是最接近的等价物。
 */
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
