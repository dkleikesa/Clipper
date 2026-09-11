package com.qcmian.clipper.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Kotlin/Native does not expose `Dispatchers.IO` (it is internal there), so the multi threaded
 * [Dispatchers.Default] pool backs the file / preferences work on iOS as well.
 */
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
