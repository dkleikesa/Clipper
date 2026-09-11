package com.qcmian.clipper

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.qcmian.clipper.core.ClipboardRepository
import com.qcmian.clipper.core.createClipboardPlatform
import com.qcmian.clipper.core.createClipStorage
import com.qcmian.clipper.ui.HistoryPanel
import com.qcmian.clipper.ui.theme.ClipperTheme

/**
 * Entry point shared by every target.
 *
 * [onRequestHideWindow] lets the host hide its window before a "paste automatically" action
 * is delivered, so the keystroke reaches the application that was focused before.
 */
@Composable
fun App(onRequestHideWindow: () -> Unit = {}) {
    ClipperTheme {
        val scope = rememberCoroutineScope()
        val repository = remember {
            ClipboardRepository(
                platform = createClipboardPlatform(),
                storage = createClipStorage(),
                scope = scope,
            )
        }

        DisposableEffect(repository) {
            repository.start()
            onDispose { repository.stop() }
        }

        LaunchedEffect(repository, onRequestHideWindow) {
            repository.onPasteRequest = { onRequestHideWindow() }
        }

        HistoryPanel(repository = repository, modifier = Modifier.fillMaxSize())
    }
}
