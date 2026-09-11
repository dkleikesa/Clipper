package com.qcmian.clipper

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.qcmian.clipper.core.ClipboardRepository
import com.qcmian.clipper.core.createClipboardPlatform
import com.qcmian.clipper.core.createClipStorage
import com.qcmian.clipper.core.createNativeIntegration
import com.qcmian.clipper.ui.ClipperController
import com.qcmian.clipper.ui.HistoryPanel
import com.qcmian.clipper.ui.theme.ClipperTheme

/**
 * Entry point shared by every target.
 *
 * @param onRequestHideWindow lets the host hide its window before a "paste automatically"
 *   action is delivered, so the keystroke reaches the previously focused application.
 * @param onQuit the "退出" footer row. `null` hides the row on platforms that cannot quit.
 * @param onPreviewOpenChange reports the preview slideout state so a desktop host can widen
 *   its window the same way Maccy does.
 * @param autoPreview whether the preview may slide out on its own. Only hosts that can show
 *   it next to the list should enable it.
 * @param previewOnLeft mirrors `SlideoutController.computePlacement`: the preview slides out
 *   on the left when there is no room for it on the right of the popup.
 * @param onPreferredHeightChange reports the height the popup would like to have so a desktop
 *   host can hug its content the way Maccy's floating panel does.
 */
@Composable
fun App(
    onRequestHideWindow: () -> Unit = {},
    onQuit: (() -> Unit)? = null,
    onPreviewOpenChange: (Boolean) -> Unit = {},
    autoPreview: Boolean = false,
    previewOnLeft: Boolean = false,
    controller: ClipperController? = null,
    onPreferredHeightChange: (Dp) -> Unit = {},
) {
    ClipperTheme {
        val scope = rememberCoroutineScope()
        val repository = remember {
            ClipboardRepository(
                platform = createClipboardPlatform(),
                storage = createClipStorage(),
                scope = scope,
                native = createNativeIntegration(),
            )
        }

        DisposableEffect(repository) {
            repository.start()
            onDispose {
                repository.stop()
                // Port of `AppDelegate.applicationWillTerminate`: flush the debounced writes
                // and apply the "clear history on quit" preference. Only hosts that can
                // actually quit opt in.
                if (onQuit != null) repository.handleQuit() else repository.flush()
            }
        }

        LaunchedEffect(repository, onRequestHideWindow) {
            repository.onPasteRequest = { onRequestHideWindow() }
            // `History.select` closes the popup for a plain copy as well.
            repository.onSelectRequest = { onRequestHideWindow() }
        }

        HistoryPanel(
            repository = repository,
            onQuit = onQuit,
            onPreviewOpenChange = onPreviewOpenChange,
            onRequestHideWindow = onRequestHideWindow,
            autoPreview = autoPreview,
            previewOnLeft = previewOnLeft,
            controller = controller,
            onPreferredHeightChange = onPreferredHeightChange,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
