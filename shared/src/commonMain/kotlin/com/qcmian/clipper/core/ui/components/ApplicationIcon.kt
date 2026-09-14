package com.qcmian.clipper.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按 bundle id 解析 base64 图标，且不阻塞组合过程。
 *
 * 平台侧的查找要遍历 `.app` 包（桌面端）或图标缓存，因此像历史列表以前那样直接在 composable
 * 里调用，会在每次重组时为每个可见行做一次文件 IO。这里把查找放到 [Dispatchers.IO] 上，
 * 并且只在 [bundleId] 变化时重跑，从而让滚动不落在 IO 路径上。
 */
@Composable
fun rememberApplicationIcon(
    load: (String?) -> String?,
    bundleId: String?,
): String? = rememberOffComposition(bundleId) { bundleId?.let(load) }

/**
 * 与 [rememberApplicationIcon] 同理，只是用于应用显示名，
 * 供偏好设置对话框的「忽略的应用」列表使用。
 */
@Composable
fun rememberApplicationName(
    load: (String) -> String?,
    bundleId: String,
): String? = rememberOffComposition(bundleId) { load(bundleId) }

/** 在 [Dispatchers.IO] 上执行一次阻塞式查找，并以 [key] 为键缓存结果。 */
@Composable
private fun <T> rememberOffComposition(key: Any?, load: () -> T?): T? {
    val value by produceState<T?>(initialValue = null, key) {
        value = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
    }
    return value
}
