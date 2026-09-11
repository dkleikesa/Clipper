package com.qcmian.clipper.data.source

import web.window.window

/**
 * Browsers expose no frontmost-application information, no global shortcuts and no
 * dependency-free text recognition, so only the About dialog links are wired up.
 */
private class WebNativeDataSource : NativeDataSource {
    override fun openUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false

        return runCatching {
            // `window.open` defaults to a new tab, the same target Maccy's links open in.
            window.open(url)
            true
        }.getOrDefault(false)
    }
}

actual fun createNativeDataSource(): NativeDataSource = WebNativeDataSource()
