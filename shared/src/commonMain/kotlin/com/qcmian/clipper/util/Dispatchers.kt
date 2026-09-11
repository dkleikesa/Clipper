package com.qcmian.clipper.util

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 用于阻塞型工作的调度器，例如读写历史与偏好设置。
 *
 * 除 Web 外的每个平台都有专门的 IO 线程池；Web 目标没有线程，退回到
 * [kotlinx.coroutines.Dispatchers.Default]。把这件事实放在 `expect` 声明之后，
 * 数据层就永远不必猜测哪个调度器是可以安全使用的。
 */
expect val ioDispatcher: CoroutineDispatcher
