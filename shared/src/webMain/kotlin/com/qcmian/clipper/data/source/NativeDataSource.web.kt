package com.qcmian.clipper.data.source

import web.window.window

/**
 * 浏览器不提供最前应用信息、全局热键，也没有无依赖的文字识别能力，
 * 因此这里只接上了「关于」对话框的链接。
 */
private class WebNativeDataSource : NativeDataSource {
    override fun openUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false

        return runCatching {
            // `window.open` 默认在新标签页打开，与 Maccy 链接的打开目标一致。
            window.open(url)
            true
        }.getOrDefault(false)
    }
}

actual fun createNativeDataSource(): NativeDataSource = WebNativeDataSource()
