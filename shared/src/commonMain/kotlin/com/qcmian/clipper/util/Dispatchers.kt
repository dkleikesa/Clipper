package com.qcmian.clipper.util

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for blocking work such as reading / writing the history and the preferences.
 *
 * Every platform except the web has a dedicated IO pool; the web targets have no threads and
 * fall back to [kotlinx.coroutines.Dispatchers.Default]. Keeping this behind an `expect`
 * declaration means the data layer never has to guess which dispatcher is safe to use.
 */
expect val ioDispatcher: CoroutineDispatcher
