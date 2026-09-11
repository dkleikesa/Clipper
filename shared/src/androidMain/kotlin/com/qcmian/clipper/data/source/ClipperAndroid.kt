package com.qcmian.clipper.data.source

import android.content.Context

/**
 * 持有 Application context，使共享代码能访问 Android 的剪贴板与偏好设置。
 * 在创建界面之前，从 Android 入口调用 [init]。
 */
object ClipperAndroid {
    internal var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
