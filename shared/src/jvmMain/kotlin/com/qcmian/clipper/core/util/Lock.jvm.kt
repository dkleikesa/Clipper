package com.qcmian.clipper.core.util

internal actual fun <T> withLock(lock: Any, block: () -> T): T = synchronized(lock, block)
