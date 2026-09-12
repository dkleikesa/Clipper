package com.qcmian.clipper

import android.app.Application
import com.qcmian.clipper.core.data.source.ClipperAndroid
import com.qcmian.clipper.di.AppContainer

/**
 * 为整个进程持有依赖图。
 *
 * 它在这里创建——而不是在 composable 内部——因此配置变更绝不会重建被保留的
 * `ClipboardViewModel` 所绑定的仓库 / 剪贴板监听器。在这次修改之前，容器是在 `App` 里
 * `remember` 的，这意味着旋转设备会创建第二张读取同一份存储、却从未被使用的依赖图。
 */
class ClipperApplication : Application() {
    val container: AppContainer by lazy { AppContainer() }

    override fun onCreate() {
        super.onCreate()
        // 共享代码访问 Android 剪贴板与偏好设置之前必须先调用它。
        ClipperAndroid.init(this)
    }
}
