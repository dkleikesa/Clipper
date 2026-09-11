package com.qcmian.clipper

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.ui.ClipboardViewModel
import com.qcmian.clipper.ui.ClipperController
import com.qcmian.clipper.ui.HistoryScreen
import com.qcmian.clipper.ui.state.ClipboardUiAction
import com.qcmian.clipper.ui.theme.ClipperTheme

/**
 * Entry point shared by every target.
 *
 * It wires the dependency container, creates the state holder and forwards the host
 * callbacks; the actual UI lives in [HistoryScreen] and is a pure function of the state.
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
 * @param controller lets a host (the desktop tray) observe and drive the panel.
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
        val container = remember { AppContainer() }
        val viewModel = viewModel {
            ClipboardViewModel(
                repository = container.repository,
                captureClipboard = container.captureClipboard,
                selectClip = container.selectClip,
                togglePin = container.togglePin,
                updatePin = container.updatePin,
                updateTitle = container.updateTitle,
                updateContent = container.updateContent,
                deleteClip = container.deleteClip,
                clearHistory = container.clearHistory,
                updateSettings = container.updateSettings,
                availablePinsUseCase = container.availablePins,
                copySearchQuery = container.copySearchQuery,
                copyExtractedText = container.copyExtractedText,
                handleQuit = container.handleQuit,
                showQuit = onQuit != null,
                autoPreview = autoPreview,
            )
        }

        val state by viewModel.uiState.collectAsStateWithLifecycle()

        DisposableEffect(viewModel, onQuit) {
            viewModel.onRequestHideWindow = onRequestHideWindow
            viewModel.onQuitRequest = onQuit ?: {}
            onDispose {
                // Port of `AppDelegate.applicationWillTerminate`: flush the debounced writes
                // and apply the "clear history on quit" preference. Only hosts that can
                // actually quit opt in.
                if (onQuit != null) viewModel.onQuit() else container.repository.flush()
            }
        }

        // The host drives the panel through these counters (`PopupState.toggle/cycle`);
        // forward them as ordinary actions so the ViewModel stays the only state owner.
        val openRequests = controller?.openRequests ?: 0
        val cycleRequests = controller?.cycleRequests ?: 0
        val acceptRequests = controller?.acceptRequests ?: 0
        val hideRequests = controller?.hideRequests ?: 0

        LaunchedEffect(openRequests) {
            if (openRequests > 0) viewModel.onAction(ClipboardUiAction.Opened)
        }
        LaunchedEffect(cycleRequests) {
            if (cycleRequests > 0) viewModel.onAction(ClipboardUiAction.Cycle)
        }
        LaunchedEffect(acceptRequests) {
            if (acceptRequests > 0) viewModel.onAction(ClipboardUiAction.Accept)
        }
        LaunchedEffect(hideRequests) {
            if (hideRequests > 0) viewModel.onAction(ClipboardUiAction.Hidden)
        }

        // The tray menu calls into these slots.
        LaunchedEffect(controller, viewModel) {
            controller?.let { host ->
                host.togglePauseAction = { onlyNext ->
                    viewModel.onAction(ClipboardUiAction.TogglePause(onlyNext))
                }
                host.togglePreviewAction = { viewModel.onAction(ClipboardUiAction.TogglePreview) }
                host.clearSearchAction = { viewModel.onAction(ClipboardUiAction.ClearSearch) }
            }
        }

        // `AppState.menuIconText`: the most recent unpinned copy, shortened like Maccy does.
        val recentCopyText = remember(state.results, state.settings.showRecentCopyInMenuBar) {
            if (!state.settings.showRecentCopyInMenuBar) {
                ""
            } else {
                state.results.firstOrNull { it.item.isUnpinned }?.item?.previewableText
                    ?.take(100)
                    ?.trim()
                    ?.replace("\n", "")
                    ?.replace("\r", "")
                    ?.take(20)
                    .orEmpty()
            }
        }

        LaunchedEffect(controller, state.settings) { controller?.settings = state.settings }
        LaunchedEffect(controller, state.isModalOpen) { controller?.isModalOpen = state.isModalOpen }
        LaunchedEffect(controller, state.settings.ignoreEvents) {
            controller?.isPaused = state.settings.ignoreEvents
        }
        LaunchedEffect(controller, state.isStatusItemDisabled) {
            controller?.isStatusItemDisabled = state.isStatusItemDisabled
        }
        LaunchedEffect(controller, state.settings.menuIcon) { controller?.menuIcon = state.settings.menuIcon }
        LaunchedEffect(controller, state.previewOpen) { controller?.isPreviewOpen = state.previewOpen }
        LaunchedEffect(controller, recentCopyText) { controller?.recentCopyText = recentCopyText }

        HistoryScreen(
            state = state,
            onAction = viewModel::onAction,
            onPreviewOpenChange = onPreviewOpenChange,
            onPreferredHeightChange = onPreferredHeightChange,
            applicationIcon = viewModel::applicationIcon,
            applicationName = viewModel::applicationName,
            availablePins = viewModel::availablePins,
            previewOnLeft = previewOnLeft,
            onResetPosition = { controller?.resetPosition() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
