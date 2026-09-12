package com.qcmian.clipper.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Kotlin/Native 不暴露 `Dispatchers.IO`（在那里它是 internal 的），
 * 因此在 iOS 上文件 / 偏好设置的工作也由多线程的 [Dispatchers.Default] 线程池承担。
 */
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
