package com.qcmian.clipper.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The web targets (JS and Wasm) are single threaded and have no IO dispatcher; the event-loop
 * backed [Dispatchers.Default] is the closest equivalent.
 */
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
