package com.qcmian.clipper

import android.app.Application
import com.qcmian.clipper.data.source.ClipperAndroid
import com.qcmian.clipper.di.AppContainer

/**
 * Owns the dependency graph for the whole process.
 *
 * It is created here — and not inside a composable — so that a configuration change never
 * rebuilds the repository / clipboard listener that the retained `ClipboardViewModel` is
 * bound to. Before this change the container was `remember`ed inside `App`, which meant
 * rotating the device created a second, unused graph reading the same storage.
 */
class ClipperApplication : Application() {
    val container: AppContainer by lazy { AppContainer() }

    override fun onCreate() {
        super.onCreate()
        // Required before the shared code can reach the Android clipboard and preferences.
        ClipperAndroid.init(this)
    }
}
