package com.qcmian.clipper.core

import web.window.window

/**
 * Browsers expose no frontmost-application information, no global shortcuts and no
 * dependency-free text recognition, so only the About dialog links are wired up.
 */
private class WebNativeIntegration : NativeIntegration {
    override fun openUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false

        return runCatching {
            // `window.open` defaults to a new tab, the same target Maccy's links open in.
            window.open(url)
            true
        }.getOrDefault(false)
    }
}

actual fun createNativeIntegration(): NativeIntegration = WebNativeIntegration()
