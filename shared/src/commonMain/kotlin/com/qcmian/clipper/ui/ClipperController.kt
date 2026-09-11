package com.qcmian.clipper.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qcmian.clipper.settings.AppSettings
import com.qcmian.clipper.settings.MenuIcon

/**
 * Lets a host (the desktop tray, for example) observe and drive the panel the same way
 * Maccy's menu bar icon does: it shows whether capture is paused, which icon to draw, the
 * most recent copy, and the live preferences so the window can be sized and positioned.
 */
class ClipperController {
    /** `Defaults[.ignoreEvents]`. */
    var isPaused by mutableStateOf(false)
        internal set

    /**
     * `AppDelegate.isStatusItemDisabled`: the tray icon is dimmed while capture is paused
     * *or* while every content kind is turned off in the storage preferences.
     */
    var isStatusItemDisabled by mutableStateOf(false)
        internal set

    /** Whether the preview slideout is currently open. */
    var isPreviewOpen by mutableStateOf(false)
        internal set

    /** `Defaults[.menuIcon]`. */
    var menuIcon by mutableStateOf(MenuIcon.MACCY)
        internal set

    /** `AppState.menuIconText`, shown when `showRecentCopyInMenuBar` is enabled. */
    var recentCopyText by mutableStateOf("")
        internal set

    /** The current preferences, so a host can honour window size, screen and shortcuts. */
    var settings by mutableStateOf(AppSettings())
        internal set

    /**
     * `true` while a dialog (preferences, about, clear confirmation) is on screen. Port of
     * `FloatingPanel.resignKey`, which does not close the panel while an alert is up.
     */
    var isModalOpen by mutableStateOf(false)
        internal set

    /**
     * Incremented when the global hot key is pressed while the panel is already open. The
     * panel reacts by highlighting the next item, which is Maccy's `PopupState.cycle`.
     */
    var cycleRequests by mutableStateOf(0)
        internal set

    /** Incremented when the panel is opened through the global hot key. */
    var openRequests by mutableStateOf(0)
        internal set

    /**
     * Incremented when the global hot key is released in cycle mode. Port of
     * `Popup.handleFlagsChanged`, which accepts the highlighted item on release.
     */
    var acceptRequests by mutableStateOf(0)
        internal set

    /** Incremented when the host hides the panel, so the preview closes together with it. */
    var hideRequests by mutableStateOf(0)
        internal set

    internal var togglePauseAction: (Boolean) -> Unit = {}
    internal var togglePreviewAction: () -> Unit = {}
    internal var clearSearchAction: () -> Unit = {}

    /** Set by the host so the "reset popup position" button can clear its remembered spot. */
    var resetPositionAction: () -> Unit = {}

    /**
     * Port of clicking the status item with ⌥ held; [onlyNext] mirrors the ⇧⌥ combination,
     * which pauses capture for a single copy.
     */
    fun togglePause(onlyNext: Boolean = false) = togglePauseAction(onlyNext)

    fun togglePreview() = togglePreviewAction()

    /** Port of `Popup.handleKeyDown` in `.cycle` state: move to the next history item. */
    fun requestCycle() {
        cycleRequests++
    }

    /** Port of `Popup.handleFirstKeyDown` when the panel is closed. */
    fun requestOpen() {
        openRequests++
    }

    /** Port of `Popup.handleFlagsChanged`: accept the highlighted item on modifier release. */
    fun requestAccept() {
        acceptRequests++
    }

    /** Port of `FloatingPanel.close()`: closing the popup also closes the preview slideout. */
    fun requestHide() {
        hideRequests++
    }

    /** Port of `ListHeaderView`'s "clear the search when the popup loses focus". */
    fun clearSearch() = clearSearchAction()

    /** Port of the "reset" button next to `PopupPosition.lastPosition`. */
    fun resetPosition() = resetPositionAction()
}
