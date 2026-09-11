package com.qcmian.clipper.core

import android.content.Context

/**
 * Holds the application context so the shared code can reach the Android clipboard and
 * preferences. Call [init] from the Android entry point before creating the UI.
 */
object ClipperAndroid {
    internal var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
