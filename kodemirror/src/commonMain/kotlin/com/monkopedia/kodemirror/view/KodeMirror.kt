/*
 * Copyright 2026 Jason Monk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Originally based on CodeMirror 6 by Marijn Haverbeke, licensed under MIT.
 * See NOTICE file for details.
 */
package com.monkopedia.kodemirror.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.monkopedia.kodemirror.language.unfoldFoldAt
import com.monkopedia.kodemirror.state.ChangeSpec
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.EditorSelection
import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.EditorStateConfig
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.SelectionSpec
import com.monkopedia.kodemirror.state.Transaction
import com.monkopedia.kodemirror.state.TransactionSpec
import com.monkopedia.kodemirror.state.asDoc
import com.monkopedia.kodemirror.state.asInsert
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The main editor composable.
 *
 * Wires up the [EditorSession] with plugin hosting, layout caching, and
 * input handling, then renders the editor content.
 *
 * ## Height contract
 *
 * The editor fills the height it is given. Callers should place it under a
 * **bounded** vertical constraint — e.g. a parent that constrains height, or
 * `Modifier.height(...)` / `Modifier.heightIn(max = ...)`. Under a bounded
 * height the inner line list scrolls and caret reveal (scroll-into-view) keeps
 * the caret on screen, matching CodeMirror 6, where the editor scroller is
 * given a height (often a `max-height`).
 *
 * If the editor is placed under an **unbounded** vertical constraint — e.g. a
 * parent with `Modifier.verticalScroll(...)`, `wrapContentHeight()`, or inside
 * another vertically scrolling container — there is no bounded height to fill.
 * In that case the editor grows to its full content height (the whole document
 * is laid out and the outer container scrolls). It will NOT collapse to zero
 * height. Note that under unbounded constraints the editor's own
 * scroll-into-view cannot keep the caret visible (there is nothing to scroll
 * within the editor); the surrounding scroll container governs visibility.
 *
 * @param session  The [EditorSession] to display.
 * @param modifier Modifier applied to the outermost container.
 */
@Composable
fun KodeMirror(session: EditorSession, modifier: Modifier = Modifier) {
    val impl = session as EditorSessionImpl
    val state by session::state

    // Attach the coroutine scope to the session BEFORE constructing the plugin
    // host. syncToState() builds the view plugins, and some plugins (e.g.
    // HoverTooltipPlugin) read session.coroutineScope eagerly in their
    // initializer; if the scope isn't attached yet that read throws
    // "EditorSession is not attached to a KodeMirror composable" (#92).
    val compositionScope = rememberCoroutineScope()
    impl.backingCoroutineScope = compositionScope

    // Publish the host on the session before its plugins are built. Bringing a
    // plugin up can dispatch a transaction — the parse worker resuming a parse
    // that was parked while the view was detached does exactly that, and the
    // linter dispatches its first diagnostics from its constructor — and
    // EditorSessionImpl.dispatchTransaction reaches plugins through
    // session.pluginHost. Assigning that field only further down, after the
    // plugins had already been built, meant such a transaction updated no
    // plugins at all: the tree got re-parsed and the highlighter was never
    // told (#284).
    val pluginHost = remember(session) {
        ViewPluginHost(session).also {
            impl.pluginHost = it
            it.syncToState(state, null)
        }
    }
    val lineLayoutCache = remember(session) { LineLayoutCache() }
    // Deferred layouts: onGloballyPositioned fires bottom-up (children before
    // parent), so editorCoordinates is null on the first layout pass. We store
    // the child coordinates here and populate the cache once the parent fires.
    val pendingLineLayouts = remember(session) {
        mutableMapOf<Int, PendingLineLayout>()
    }
    // TextLayoutResult per line, populated from onTextLayout (always reliable).
    // Used as a fallback for tap positioning when lineLayoutCache is incomplete.
    val textLayoutResults = remember(session) {
        mutableMapOf<Int, TextLayoutResult>()
    }

    val clipboardManager = LocalClipboardManager.current

    // Wire up session internals eagerly (during composition, not in a side
    // effect) so that TooltipLayer and the hover pointerInput block can access
    // pluginHost on the very first frame.  DisposableEffect still handles
    // cleanup on dispose; the repeated assignments on recomposition are
    // harmless because remember() always returns the same instances.
    impl.pluginHost = pluginHost
    impl.lineLayoutCache = lineLayoutCache
    impl.clipboardManager = clipboardManager

    DisposableEffect(session) {
        onDispose {
            pluginHost.destroy()
            lineLayoutCache.clear()
            impl.pluginHost = null
            impl.lineLayoutCache = null
            impl.backingCoroutineScope = null
            impl.clipboardManager = null
        }
    }

    // Derive rendering data from current state
    val theme = state.facet(editorTheme)
    val contentStyle = state.facet(editorContentStyle).let { style ->
        if (style.color == Color.Unspecified) style.copy(color = theme.foreground) else style
    }
    val allPanels = buildList {
        state.facet(showPanel)?.let { add(it) }
        addAll(state.facet(showPanels))
    }
    val topPanels = allPanels.filter { it.top }
    val bottomPanels = allPanels.filter { !it.top }
    val hasGutters = state.facet(gutters).isNotEmpty()
    // Line-wrapping mode. Default (false) matches CodeMirror 6: long lines do
    // not wrap and the content scrolls horizontally. When the `lineWrapping`
    // extension is present, lines soft-wrap onto multiple visual rows instead.
    val wrapLines = state.facet(lineWrappingFacet)
    val viewport = Viewport(0, state.doc.length)
    // Compute columnItems when state changes. Decorations (both from
    // facets and plugins) are derived from state, so `state` is a
    // sufficient remember key. Avoid using list concatenation results
    // as keys — the `+` operator creates a new reference every
    // composition, defeating memoization.
    val columnItems = remember(state) {
        val extensionDecos = state.facet(decorations)
        val pluginDecos = pluginHost.collectDecorations()
        buildColumnItems(state, viewport, extensionDecos + pluginDecos)
    }

    // Key the scroll states to `session` like every sibling per-session state
    // above (pluginHost/lineLayoutCache/pendingLineLayouts/textLayoutResults).
    // If they used rememberLazyListState()/rememberScrollState() they would
    // survive a session swap, carrying the previous document's
    // firstVisibleItemIndex/offset and scroll range into the new document and
    // wedging scrolling across swaps between different-length docs (#166).
    val lazyState = remember(session) { LazyListState() }
    // Shared horizontal scroll state for the content area. In the default
    // no-wrap mode this lets long lines extend past the viewport and remain
    // reachable; all lines share one state so they scroll together while the
    // gutter (rendered outside the scroll region) stays fixed.
    val horizontalScrollState = remember(session) { ScrollState(0) }

    // <本仓库补丁> 光标闪烁。上游画的是**静态**光标，这里给它一个明灭节奏。
    //
    // 往下传的是**取值函数**（`caretVisible`）而不是 Boolean：传 Boolean 的话，每半秒翻转一次
    // 就要重组所有可见行；取值函数在绘制里才被调用，翻转只让这些行**重绘**。
    // 键里带 `state.selection`：光标一动就重新计时——先亮满半周期再开始闪，与系统输入框一致
    // （打字时光标是常亮的，停手后才闪）。
    var caretOn by remember(session) { mutableStateOf(true) }
    val caretVisible = remember(session) { { caretOn } }
    LaunchedEffect(session, state.selection) {
        while (true) {
            caretOn = true
            delay(CaretBlinkHalfPeriodMillis)
            caretOn = false
            delay(CaretBlinkHalfPeriodMillis)
        }
    }

    val currentColumnItems = rememberUpdatedState(columnItems)

    // Track Alt key state for rectangular selection
    var altPressed by remember { mutableStateOf(false) }

    // Track editor layout coordinates for position mapping
    var editorCoordinates: LayoutCoordinates? by remember { mutableStateOf(null) }

    // <本仓库补丁> 上一次**点击**的抬手时刻（`uptimeMillis`；0 = 还没点过）。
    //
    // 双击的间隔按系统语义量的是「上一击抬手 → 这一击按下」（见 `ViewConfiguration`
    // .doubleTapTimeoutMillis 的注释），而抬手那个事件在 `awaitTouchSlopOrCancellation` 里被丢掉了
    // ——未超过 slop 时它直接 return null。所以另起一路旁听来记（下面那个 observer）。
    // 用长整型数组而不是 `mutableStateOf`：它只在指针事件里读写、不进组合。
    val lastTapUpMillis = remember { longArrayOf(0L) }

    // Focus management
    val focusRequester = remember { FocusRequester() }
    // Requesting Compose focus is not enough to raise a soft keyboard once the
    // hidden field already holds focus: with no focus *change* there is no new
    // startInput, so the platform is never asked to show the IME. Compose's own
    // BasicTextField calls show() explicitly for that case
    // (CoreTextField.requestFocusAndShowKeyboardIfNeeded); the editor's tap
    // handler below does the same (#303).
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-focus the hidden text field when the editor first appears
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Track whether the platform callback already handled this keydown.
    // The document-level listener fires in capture phase BEFORE Skiko
    // processes the event. If the callback handled the key, onPreviewKeyEvent
    // should skip to avoid double-handling.
    val keyProcessedByCallback = remember { booleanArrayOf(false) }

    // The text a key-event path (document-level handler / onPreviewKeyEvent)
    // already entered for the keystroke in flight, whose echo through the hidden
    // text field must therefore be dropped. Declared here, rather than inside
    // EditorContent, so the document-level key handler below can update it.
    //
    // It holds the *text* rather than a bare flag so its lifetime is bounded by
    // the keystroke that armed it on every target. A boolean armed by the key
    // paths and cleared only by the wasmJs-only document handler latched on for
    // good on JVM/Android/native, where that handler is a no-op — one Backspace
    // and the editor never accepted text again (#294).
    val pendingEcho = remember { arrayOfNulls<String>(1) }

    // Register a platform-level key handler that receives ALL keydown events.
    // This is the primary input path on wasmJs because Playwright (and some
    // headless environments) dispatch key events to BODY rather than the
    // canvas in the shadow DOM, so Skiko never generates Compose KeyEvents.
    // For real users (events targeting the canvas), both this callback AND
    // onPreviewKeyEvent fire — the flag prevents double-handling.
    DisposableEffect(session) {
        val token = platformRegisterKeyHandler handler@{ key, ctrl, alt, meta, shift ->
            // Clear the flags at the start of each key event.
            // keyProcessedByCallback is set true only if we handle this key.
            keyProcessedByCallback[0] = false
            pendingEcho[0] = null
            val handled = handleRawKeyEvent(session, key, ctrl, alt, meta, shift)
            if (handled) {
                keyProcessedByCallback[0] = true
                // A plain printable char entered here still triggers an
                // onValueChange echo on the hidden field even when onPreviewKeyEvent
                // does NOT fire on the live wasmJs build — so arm echo suppression
                // directly rather than relying on onPreviewKeyEvent to bridge it (#109).
                if (key.length == 1 && !ctrl && !alt && !meta && !key[0].isISOControl()) {
                    pendingEcho[0] = key
                }
            }
            handled
        }
        onDispose {
            platformUnregisterKeyHandler(token)
        }
    }

    val density = LocalDensity.current
    val lineHeightDp = with(density) { contentStyle.lineHeight.toDp() }

    // Compute gutter width based on digit count + padding (5dp + 3dp)
    val configs = state.facet(gutters)
    val gutterWidthDp = if (hasGutters) {
        val maxDigits = state.doc.lines.toString().length
        val charWidthDp = with(density) {
            (contentStyle.fontSize.toPx() * 0.65f).toDp()
        }
        val lineNumberWidth = charWidthDp * maxDigits +
            theme.layout.gutterStartPadding + theme.layout.gutterEndPadding
        val extraGutterWidth =
            theme.layout.customGutterWidth *
                configs.count { it.type != GutterType.LineNumbers && it.lineMarker != null }
        lineNumberWidth + extraGutterWidth
    } else {
        0.dp
    }

    // Content height in px (top padding + items + bottom padding)
    val contentHeightPx = with(density) {
        (
            theme.layout.contentTopPadding + lineHeightDp * columnItems.size +
                theme.layout.contentBottomPadding
            ).toPx()
    }

    // Honor transaction.scrollIntoView: when a dispatched transaction requests
    // it, scroll the line list so the primary selection head becomes visible.
    // This drives caret-reveal on cursor moves (#33) and the search-jump reveal
    // for vim `n`/`N` and similar selection jumps (#58). Without this, an
    // off-screen target lands invisibly because the LazyColumn never scrolls.
    //
    // In no-wrap mode this also scrolls HORIZONTALLY so the caret stays in view
    // when it moves past the right or left content edge (#69), the horizontal
    // analog of the vertical reveal above.
    //
    // Observe via snapshotFlow (outside composition) rather than keying a
    // LaunchedEffect directly on the request value: running a scroll while the
    // LazyColumn is still mid-(sub)composition can trip the Compose runtime.
    val contentStartPaddingPx = with(density) { 6.dp.toPx() }
    val gutterWidthPx = with(density) { gutterWidthDp.toPx() }
    val contentTopPaddingPx = with(density) {
        theme.layout.contentTopPadding.toPx()
    }
    LaunchedEffect(session) {
        snapshotFlow { impl.scrollRequest }
            .collect { request ->
                if (request == null) return@collect
                try {
                    val items = currentColumnItems.value
                    val targetIndex = items.indexOfFirst { item ->
                        when (item) {
                            is ColumnItem.TextLine ->
                                request.target >= item.from.value &&
                                    request.target <= item.to.value
                            is ColumnItem.BlockWidgetItem -> false
                        }
                    }
                    if (targetIndex >= 0) {
                        scrollItemIntoView(lazyState, targetIndex)

                        // Horizontal reveal (no-wrap mode only). Wrapped lines
                        // never overflow horizontally, so there is nothing to
                        // scroll.
                        if (!wrapLines) {
                            val line = items[targetIndex] as? ColumnItem.TextLine
                            val layout = line?.let {
                                textLayoutResults[it.lineNumber.value]
                            }
                            val coordsWidth = editorCoordinates?.size?.width
                            if (line != null &&
                                layout != null &&
                                coordsWidth != null
                            ) {
                                // Map the document offset within the line to the
                                // expanded-text offset (tab expansion), exactly
                                // as the cursor is drawn, then read its x in
                                // line-local pixels (0 = line start, before the
                                // 6.dp padding).
                                val offsetInLine =
                                    (request.target - line.from.value)
                                        .coerceIn(
                                            0,
                                            line.to.value - line.from.value
                                        )
                                val mappedOffset = mapTabOffset(
                                    offsetInLine,
                                    line.tabOffsetMap
                                )
                                // Clamp to the LAYOUT's text length, not the doc
                                // line length: `layout` is a cached
                                // TextLayoutResult that lags the doc by a frame
                                // during typing and mapTabOffset can expand the
                                // offset, so mappedOffset can exceed the
                                // layout's text — getCursorRect would throw
                                // "offset out of bounds" (#152).
                                val layoutLen = layout.layoutInput.text.length
                                val cursorX = layout
                                    .getCursorRect(
                                        mappedOffset.coerceIn(0, layoutLen)
                                    )
                                    .left

                                // Content viewport width = editor width minus
                                // the gutter and the 6.dp content start padding.
                                // This mirrors posFromVisibleItems'
                                // contentStartPx so the forward (offset->x) and
                                // inverse (x->offset) maps agree.
                                val gutter =
                                    if (hasGutters) gutterWidthPx else 0f
                                val viewportPx =
                                    coordsWidth - gutter - contentStartPaddingPx
                                if (viewportPx > 0f) {
                                    val scroll =
                                        horizontalScrollState.value.toFloat()
                                    val marginPx = 24f
                                    val target = when {
                                        cursorX < scroll + marginPx ->
                                            cursorX - marginPx
                                        cursorX > scroll + viewportPx - marginPx ->
                                            cursorX - viewportPx + marginPx
                                        else -> null
                                    }
                                    if (target != null) {
                                        val clamped = target.coerceIn(
                                            0f,
                                            horizontalScrollState.maxValue
                                                .toFloat()
                                        )
                                        horizontalScrollState.scrollTo(
                                            clamped.roundToInt()
                                        )
                                    }
                                }
                            }
                        }
                    }
                    impl.consumeScrollRequest(request)
                } catch (t: Throwable) {
                    // A single failed reveal must never permanently kill the
                    // collector. If the body throws, the LaunchedEffect would
                    // complete exceptionally and is not relaunched (key
                    // `session` is unchanged), leaving scroll-into-view dead for
                    // the rest of the session (#155). Swallow the failure, still
                    // consume the request so a persistently-bad one isn't
                    // retried forever, and keep collecting — failing one UI
                    // reveal for a frame is correct; dying permanently is not.
                    // Rethrow CancellationException so structured cancellation
                    // (effect disposal) still tears the coroutine down.
                    if (t is CancellationException) throw t
                    impl.consumeScrollRequest(request)
                }
            }
    }

    // Mirror the horizontal scroll offset into the session for test
    // observability (parallels lastFirstVisibleItem / lastVisibleItemCount).
    LaunchedEffect(session) {
        snapshotFlow { horizontalScrollState.value }
            .collect { impl.lastHorizontalScrollPx = it }
    }

    // The single hit-test entry point for all pointer interactions (tap, drag,
    // hover, drop-cursor). It resolves a document position from the LIVE
    // LazyColumn layout (posFromVisibleItems) so it tracks scrolling, and only
    // falls back to the cache-based posAtCoords when there are no visible text
    // lines at all (empty doc / first frame). This is the #165 fix: the cache's
    // line positions go stale after a scroll-without-recomposition, so routing
    // every hit-test through the live layout keeps clicks/drags/hover on the
    // glyph under the pointer instead of its pre-scroll position.
    val resolvePos: (Offset) -> Int? = { offset ->
        val pos = posFromVisibleItems(
            offset,
            lazyState,
            columnItems,
            textLayoutResults,
            hasGutters,
            gutterWidthDp,
            density,
            if (wrapLines) 0 else horizontalScrollState.value,
            contentTopPaddingPx
        ) ?: session.posAtCoords(offset.x, offset.y)
        // Drop — never clamp — a result the current document cannot contain.
        // Pointer events are delivered before the frame's composition, so a
        // document swapped in from outside a gesture (setDoc, an LSP edit) can
        // land between the event and the recomposition that re-derives the
        // layout from it. Everything above is then self-consistent but
        // describes the PREVIOUS document, and there is no honest mapping from
        // such a position into the new one. Coercing it into range would place
        // the caret somewhere the user did not click and say nothing about it;
        // returning null makes the gesture a no-op, so the click is simply lost
        // and the next one — against a settled layout — lands correctly.
        pos?.takeIf { it in 0..session.state.doc.length }
    }

    // The two gesture coroutines below are keyed on `session`, and a session
    // keeps its identity when its document changes — so their key never fires
    // and the handler lambda they were started with is the one they keep. Held
    // directly, `resolvePos` would be whichever composition's copy happened to
    // be current when the first pointer event started them, closed over that
    // composition's `columnItems` — the OLD document's line spans. Clicking
    // after the document was replaced with a shorter one then resolved through
    // a line start past the new document's end and the dispatch threw
    // "Selection points outside of document" (#313). Reading the latest
    // resolver keeps the gesture coroutines stable and their hit-testing
    // current, the same treatment `currentColumnItems` gets above.
    val currentResolvePos by rememberUpdatedState(resolvePos)

    CompositionLocalProvider(
        LocalEditorTheme provides theme,
        LocalContentTextStyle provides contentStyle,
        LocalEditorSession provides session
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .boundUnconstrainedHeight(contentHeightPx)
        ) {
            for (panel in topPanels) {
                Box(Modifier.fillMaxWidth().background(theme.panelBackground)) {
                    panel.content(this)
                }
                Box(
                    Modifier.fillMaxWidth().height(theme.layout.panelBorderWidth)
                        .background(theme.panelBorderColor)
                )
            }
            Box(
                modifier = Modifier
                    .testTag("KodeMirror")
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        editorCoordinates = coords
                        // Process pending line layouts synchronously.
                        // onGloballyPositioned fires bottom-up: children
                        // stored their data in pendingLineLayouts because
                        // editorCoordinates was null when they fired. Now
                        // that the parent has coordinates, drain the map.
                        if (pendingLineLayouts.isNotEmpty()) {
                            val pending = pendingLineLayouts.toMap()
                            pendingLineLayouts.clear()
                            for ((_, p) in pending) {
                                if (p.coords.isAttached) {
                                    val pos = coords.localPositionOf(
                                        p.coords,
                                        Offset.Zero
                                    )
                                    lineLayoutCache.store(
                                        p.lineNumber,
                                        p.lineFrom,
                                        pos.y,
                                        pos.x,
                                        p.result
                                    )
                                }
                            }
                        }
                    }
                    .drawEditorBackground(
                        theme = theme,
                        hasGutters = hasGutters,
                        gutterWidthDp = gutterWidthDp,
                        contentHeightPx = contentHeightPx
                    )
                    .onPreviewKeyEvent { event ->
                        altPressed = event.isAltPressed
                        false
                    }
                    .pointerInput(session) {
                        // <本仓库补丁> 旁听每一次**抬手**，只记「刚才是点击还是拖拽/长按」。
                        //
                        // 只看不消费：同一节点上的每个 handler 都会收到同一份事件，这里不改变任何
                        // 别人的行为。判定「是不是点击」用的是与手势那侧同一条 slop——而且是**过程中
                        // 的最大位移**，不是抬手时的位置：拖出去再拖回原位松手同样不算点击（手势那侧
                        // 一旦超过 slop 就已经改判成拖拽了）。拖选、长按选完再抬手也都不该成为双击
                        // 窗口的起点，系统那套也是这么定的。
                        awaitPointerEventScope {
                            var downPosition: Offset? = null
                            var maxExcursion = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    if (change.changedToDownIgnoreConsumed()) {
                                        downPosition = change.position
                                        maxExcursion = 0f
                                    } else if (change.changedToUpIgnoreConsumed()) {
                                        val from = downPosition
                                        if (from != null &&
                                            maxExcursion <= viewConfiguration.touchSlop
                                        ) {
                                            // 时间取这个 up change 自己的（`PointerEvent` 上没有
                                            // `uptimeMillis`），与手势那侧读的 `down.uptimeMillis`
                                            // 是同一个时钟。
                                            lastTapUpMillis[0] = change.uptimeMillis
                                        }
                                        downPosition = null
                                    } else if (change.pressed) {
                                        val from = downPosition
                                        if (from != null) {
                                            maxExcursion = maxOf(
                                                maxExcursion,
                                                (change.position - from).getDistance()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(session) {
                        // <本仓库补丁> 连击计数（双击选词 / 三击选行）。它要跨手势存活，所以声明
                        // 在 `awaitEachGesture` 之外、同一次 `pointerInput` 调用里。
                        var clickCount = 0
                        var lastClickPosition = Offset.Zero
                        // Unified tap/drag handler: always request focus on
                        // pointer down BEFORE deciding if it's a tap or drag.
                        // This fixes the race condition (issue #2) where
                        // separate tap/drag coroutines competed for the DOWN
                        // event and focus could be skipped.
                        awaitEachGesture {
                            // Consume the down, as detectTapAndPress does for a
                            // text field. On wasmJs that is what makes Compose
                            // call preventDefault() on the browser event;
                            // without it the default action moves DOM focus to
                            // the tabindex=0 <canvas> and blurs the backing
                            // <textarea>, the only element a soft keyboard is
                            // raised for (#303). Children (line-list scroll,
                            // gutter clicks) see the Main pass first, so this
                            // does not take the gesture away from them.
                            val down = awaitFirstDown().also { it.consume() }
                            // Focus immediately on any pointer down, and ask for
                            // the keyboard even when focus does not change —
                            // otherwise one the user dismissed never comes back.
                            focusRequester.requestFocus()
                            keyboardController?.show()

                            val downPosition = down.position
                            var isDrag = false
                            var dragStart = downPosition
                            var dragCurrent = downPosition

                            // A touch drag is not ours to take. The line list
                            // (LazyColumn) and the line content
                            // (horizontalScroll) are children of this node, so
                            // they see the Main pass first and `scrollable`
                            // claims any drag whose pointer is not a mouse —
                            // which is exactly why a mouse drag selects here and
                            // a finger drag did not (#311). Scrolling with a
                            // finger must keep working, so instead of taking the
                            // drag back, wait: a press held in place past the
                            // long-press timeout starts a selection and the
                            // moves after it are consumed in the Initial pass,
                            // ahead of the scroll containers. That is CodeMirror
                            // 6's mobile behaviour too — a plain drag scrolls,
                            // a long press begins a selection.
                            val hold = if (down.type != PointerType.Mouse) {
                                awaitTouchLongPress(
                                    down.id,
                                    downPosition,
                                    viewConfiguration.touchSlop,
                                    viewConfiguration.longPressTimeoutMillis
                                )
                            } else {
                                // A mouse never waits: `scrollable` ignores
                                // mouse drags, so this one was always ours.
                                TouchHoldOutcome.Moved
                            }

                            if (hold == TouchHoldOutcome.LongPress) {
                                // Anchor the selection where the finger has been
                                // resting, then extend it for as long as the
                                // finger moves.
                                handleDrag(
                                    session,
                                    downPosition,
                                    downPosition,
                                    currentResolvePos
                                )
                                session.plugin(dropCursorViewPlugin)
                                    ?.moveTo(currentResolvePos(downPosition))
                                while (true) {
                                    val event = awaitPointerEvent(
                                        PointerEventPass.Initial
                                    )
                                    val change = event.changes.firstOrNull {
                                        it.id == down.id
                                    } ?: break
                                    // Consuming in the Initial pass is what
                                    // keeps the scroll containers out: they read
                                    // the Main pass and skip a change that is
                                    // already consumed.
                                    change.consume()
                                    if (change.changedToUpIgnoreConsumed()) break
                                    dragCurrent = change.position
                                    handleDrag(
                                        session,
                                        downPosition,
                                        dragCurrent,
                                        currentResolvePos
                                    )
                                    session.plugin(dropCursorViewPlugin)
                                        ?.moveTo(currentResolvePos(dragCurrent))
                                }
                                session.plugin(dropCursorViewPlugin)
                                    ?.moveTo(null)
                                return@awaitEachGesture
                            }

                            if (hold == TouchHoldOutcome.Released) {
                                // Lifted before the timeout: the pointer is
                                // already up, so there is no slop to wait for.
                                val pos = currentResolvePos(downPosition)
                                    ?: return@awaitEachGesture
                                session.dispatch(
                                    TransactionSpec(
                                        selection = SelectionSpec
                                            .CursorSpec(DocPos(pos))
                                    )
                                )
                                return@awaitEachGesture
                            }

                            // Try to detect if this becomes a drag
                            val slopChange = awaitTouchSlopOrCancellation(
                                down.id
                            ) { change, overSlop ->
                                change.consume()
                                isDrag = true
                                dragCurrent = change.position
                            }

                            if (slopChange != null && isDrag) {
                                // It's a drag — handle drag selection
                                dragStart = downPosition
                                if (altPressed) {
                                    handleRectangularDrag(
                                        session,
                                        dragStart,
                                        dragCurrent,
                                        currentResolvePos
                                    )
                                } else {
                                    handleDrag(
                                        session,
                                        dragStart,
                                        dragCurrent,
                                        currentResolvePos
                                    )
                                }
                                session.plugin(dropCursorViewPlugin)
                                    ?.moveTo(currentResolvePos(dragCurrent))

                                drag(slopChange.id) { change ->
                                    change.consume()
                                    dragCurrent = change.position
                                    if (altPressed) {
                                        handleRectangularDrag(
                                            session,
                                            dragStart,
                                            dragCurrent,
                                            currentResolvePos
                                        )
                                    } else {
                                        handleDrag(
                                            session,
                                            dragStart,
                                            dragCurrent,
                                            currentResolvePos
                                        )
                                    }
                                    session.plugin(dropCursorViewPlugin)
                                        ?.moveTo(currentResolvePos(dragCurrent))
                                }
                                // Drag ended
                                session.plugin(dropCursorViewPlugin)
                                    ?.moveTo(null)
                            } else if (!isDrag) {
                                // Pointer released without exceeding slop —
                                // treat as a tap for cursor positioning.
                                val pos = currentResolvePos(downPosition)
                                    ?: return@awaitEachGesture
                                // <本仓库补丁> 连击判定与**系统同一套**：间隔量的是「上一击抬手 →
                                // 这一击按下」（抬手时刻由上面那路旁听记下，见 `lastTapUpMillis`），
                                // 带一个最小间隔防「极快两下」，落点相距不超过 slop。到三击为止，
                                // 第四下重新从单击开始。窗口取 [TapRepeatIntervalMillis]。
                                val sinceUp = down.uptimeMillis - lastTapUpMillis[0]
                                val repeated = lastTapUpMillis[0] != 0L &&
                                    sinceUp >= viewConfiguration.doubleTapMinTimeMillis &&
                                    sinceUp <= TapRepeatIntervalMillis &&
                                    (downPosition - lastClickPosition).getDistance() <=
                                    viewConfiguration.touchSlop
                                clickCount = if (repeated && clickCount < MaxClickCount) {
                                    clickCount + 1
                                } else {
                                    1
                                }
                                lastClickPosition = downPosition
                                dispatchTapSelection(session, pos, clickCount)
                            } else {
                                // Drag cancelled
                                session.plugin(dropCursorViewPlugin)
                                    ?.moveTo(null)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        // Hold the editor's focus for the length of a press.
                        //
                        // Compose on Android takes focus away on a mouse or
                        // touchpad press: `AndroidComposeView.dispatchTouchEvent`
                        // applies `AutoClearFocusBehavior.CursorBased` — the
                        // platform default — AFTER the gesture handlers above
                        // have run, clearing focus whenever a cursor press lands
                        // outside the bounds of the focused node. The editor's
                        // focused node is the 1-dp hidden input, so no press in
                        // the editor ever lands inside it and every mouse click
                        // blanked the focus the gesture had just requested. The
                        // click still placed the caret correctly; it was the key
                        // after it that went nowhere (#259). A finger press is
                        // not a cursor press and never tripped this, which is the
                        // whole of the mouse/touch split the two suites saw.
                        //
                        // That clear is not forced, and a captured focus target
                        // refuses a non-forced clear, so capturing across the
                        // press vetoes it. Taken on the Final pass, which runs
                        // after every Main-pass handler in the tree and therefore
                        // after the gesture above has requested focus, and
                        // released as soon as the last pointer lifts so nothing
                        // else is ever kept from taking focus.
                        var focusCaptured = false
                        try {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(
                                        PointerEventPass.Final
                                    )
                                    val pressed = event.changes.any { it.pressed }
                                    if (pressed) {
                                        if (!focusCaptured) {
                                            focusCaptured =
                                                focusRequester.captureFocus()
                                        }
                                    } else if (focusCaptured) {
                                        focusRequester.freeFocus()
                                        focusCaptured = false
                                    }
                                }
                            }
                        } finally {
                            // Only reached when this node detaches, where the
                            // focus target's own detach forcibly clears the
                            // capture anyway; freeing through a requester whose
                            // node has already gone throws, so it is guarded.
                            if (focusCaptured) {
                                runCatching { focusRequester.freeFocus() }
                            }
                        }
                    }
                    .pointerInput(session) {
                        awaitPointerEventScope {
                            var lastHoverDocPos: Int? = -1
                            while (true) {
                                val event = awaitPointerEvent(
                                    PointerEventPass.Main
                                )
                                val pos = event.changes
                                    .firstOrNull()?.position
                                if (pos != null) {
                                    val docPos = currentResolvePos(pos)
                                    if (docPos != lastHoverDocPos) {
                                        lastHoverDocPos = docPos
                                        val hoverTooltips =
                                            impl.pluginHost
                                                ?.collectHoverPlugins()
                                                ?: emptyList()
                                        for (plugin in hoverTooltips) {
                                            plugin.updateHoverAtPos(docPos)
                                        }
                                    }
                                }
                            }
                        }
                    }
            ) {
                // <本仓库补丁> 正文右端让出竖向滚动条那一列：滚动条叠在正文之上（与横向那条同一
                // 个路数），不让出来的话每一行折行末尾的字都会被滑块压住。宽度是常量，因此**无论
                // 此刻有没有滚动条**都让——否则内容一长过一屏，正文就横向重排一下。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = ScrollbarThickness)
                        // <本仓库补丁> 正文给回 I 形光标。上游一处都没设，鼠标移到正文上仍是箭头，
                        // 看不出「这里能点字」。只加在这个 Box 上：两条滚动条是它的**兄弟节点**，
                        // 各自的箭头形状不受影响（横向那条另见 `HorizontalScrollbar`）。
                        .pointerHoverIcon(PointerIcon.Text)
                ) {
                    EditorContent(
                        lazyState = lazyState,
                        focusRequester = focusRequester,
                        columnItems = columnItems,
                        hasGutters = hasGutters,
                        gutterWidthDp = gutterWidthDp,
                        lineHeightDp = lineHeightDp,
                        wrapLines = wrapLines,
                        horizontalScrollState = horizontalScrollState,
                        lineLayoutCache = lineLayoutCache,
                        pendingLineLayouts = pendingLineLayouts,
                        editorCoordinates = editorCoordinates,
                        textLayoutResults = textLayoutResults,
                        keyProcessedByCallback = keyProcessedByCallback,
                        pendingEcho = pendingEcho,
                        // <本仓库补丁> 光标闪烁的取值函数（见上面 `caretOn`）。
                        caretVisible = caretVisible
                    )
                }
                // Visible horizontal scrollbar for no-wrap mode (#65). Only
                // shown when there is actual horizontal overflow and line
                // wrapping is off — otherwise the long-line content clips at the
                // right edge with no affordance on the Skiko canvas.
                if (!wrapLines && horizontalScrollState.maxValue > 0) {
                    HorizontalScrollbar(
                        scrollState = horizontalScrollState,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(start = if (hasGutters) gutterWidthDp else 0.dp)
                    )
                }
                // <本仓库补丁> Vertical scrollbar. Upstream draws the horizontal
                // one only (see above): the vertical axis was left to the wheel and
                // caret-reveal, which scroll fine but leave no affordance at all.
                // See `VerticalScrollbar` below.
                if (lazyState.canScrollForward || lazyState.canScrollBackward) {
                    VerticalScrollbar(
                        lazyState = lazyState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            // 横向条压在底边上，竖向这条止于它之上，免得两条撞在右下角。
                            .padding(
                                bottom = if (!wrapLines && horizontalScrollState.maxValue > 0) {
                                    ScrollbarThickness
                                } else {
                                    0.dp
                                }
                            )
                    )
                }
            }
            for (panel in bottomPanels) {
                Box(
                    Modifier.fillMaxWidth().height(theme.layout.panelBorderWidth)
                        .background(theme.panelBorderColor)
                )
                Box(Modifier.fillMaxWidth().background(theme.panelBackground)) {
                    panel.content(this)
                }
            }
        }
    }
}

/**
 * The text [event] enters as ordinary typing, or null when it enters none.
 *
 * Modified presses (Ctrl+A, Alt+X, ...) are shortcuts rather than text, and
 * control and special keys (Backspace, Enter, arrows) carry no insertable
 * character. Used both to insert a character the keymap left alone and to
 * recognise that same character coming back as an input echo.
 */
private fun typedText(event: KeyEvent): String? {
    if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return null
    val char = keyEventLayoutKey(event) ?: keyEventCharacter(event)?.toString() ?: return null
    return char.takeIf { it.length == 1 && !it[0].isISOControl() }
}

/** Inner content of the editor: hidden text field, line list, and tooltip layer. */
@Composable
private fun EditorContent(
    lazyState: LazyListState,
    focusRequester: FocusRequester,
    columnItems: List<ColumnItem>,
    hasGutters: Boolean,
    gutterWidthDp: Dp,
    lineHeightDp: Dp,
    wrapLines: Boolean,
    horizontalScrollState: ScrollState,
    lineLayoutCache: LineLayoutCache,
    pendingLineLayouts: MutableMap<Int, PendingLineLayout>,
    editorCoordinates: LayoutCoordinates?,
    textLayoutResults: MutableMap<Int, TextLayoutResult>,
    keyProcessedByCallback: BooleanArray,
    pendingEcho: Array<String?>,
    /** <本仓库补丁> 光标此刻该不该画（闪烁）。只在绘制里调用，见 [drawSelectionOverlay]。 */
    caretVisible: () -> Boolean
) {
    val session = LocalEditorSession.current
    val impl = session as EditorSessionImpl
    val state by session::state
    val theme = LocalEditorTheme.current
    val contentStyle = LocalContentTextStyle.current
    // Shared widest-line width: every no-wrap line is measured to this min so
    // all per-line horizontalScroll modifiers share one consistent scroll
    // range. Without it, short lines (laid out last) clobber the shared
    // ScrollState.maxValue to 0, breaking scrolling and the scrollbar (#67).
    val maxLineWidthPx = remember { mutableStateOf(0) }
    // Laid-out width of the per-line viewport (the weight(1f) box). In no-wrap
    // mode the content box scrolls horizontally, so it is measured with an
    // infinite max width and cannot learn the viewport width itself. We capture
    // the viewport's actual width here so a short line's content (and thus its
    // line-decoration background highlight) can fill the full viewport width
    // rather than stopping at the text width (#85). The viewport width is
    // weight-determined (independent of content width), so feeding it back into
    // the content min-width is loop-safe.
    val viewportWidthPx = remember { mutableStateOf(0) }
    val density = LocalDensity.current

    // Hidden text field for receiving IME/text input and key events
    var hiddenTextValue by remember {
        mutableStateOf(TextFieldValue(""))
    }
    // pendingEcho (hoisted to the caller) holds the text a key path already
    // entered for the keystroke in flight. On wasmJs, returning true from
    // onPreviewKeyEvent does NOT call preventDefault() on the DOM event, so the
    // browser still generates an input event that triggers onValueChange with
    // that same text; dropping it is what keeps the character from doubling.
    BasicTextField(
        value = hiddenTextValue,
        // <本仓库补丁> 只读时这个隐藏输入框不再收音。
        //
        // `onValueChange` 里本来就有 `if (!session.editable) return` 的兜底，但那是**收到之后
        // 才丢**：输入法候选、系统输入反馈都还在。这里直接从输入源头关掉。
        //
        // 焦点与插入光标**故意留着**：只读框照样要能点、能拖选、能复制——选区与光标由本仓库
        // 自己画（见 `SelectionDrawing`），`readOnly` 只挡改写，挡不到它们。
        readOnly = !session.editable,
        cursorBrush = SolidColor(Color.Transparent),
        onValueChange = { newValue ->
            // <本仓库补丁> 输入法组字（拼音 / 假名等待选）期间，不要把隐藏框里的文本当成
            // 已确认的正文提交。见 README「本仓库补丁」第 10 处。
            //
            // 上游每收到一次 onValueChange 就「把文本插进文档 + 清空隐藏框」，这在组字中是错的
            // 两个方向：拼音字母被当成正文逐字插进文档，而清空又把平台那一侧的组字状态一起抹掉
            // ——候选窗因此根本走不完，中文 / 日文打不出来（拉丁字母没有组字过程，所以从前看不出
            // 问题）。
            //
            // 判据是 `TextFieldValue.composition`：非空即「输入法正在组字」。这时把隐藏框的完整
            // 值（含组字区间）原样留着，让候选窗继续；等组字结束（composition 为空）那一次再
            // 一次性插入。组字期间的按键另由下面 `onPreviewKeyEvent` 的补丁让开。
            if (newValue.composition != null) {
                hiddenTextValue = newValue
                return@BasicTextField
            }
            if (newValue.text == pendingEcho[0]) {
                // Matching echoes stay suppressed for as long as they keep
                // arriving: one keystroke can produce more than one on wasmJs —
                // the browser input event, plus a re-fire when a completion popup
                // recomposes — and both must be dropped, otherwise the trigger
                // character is re-inserted (doubled) and the spurious doc-change
                // closes the just-opened completion popup (#109).
                hiddenTextValue = TextFieldValue("")
                return@BasicTextField
            }
            // Any other text is genuine input, so the keystroke's echo is over.
            // An empty value inserts nothing either way, and disarming on one
            // would let a second echo of the same keystroke through (#109).
            if (newValue.text.isNotEmpty()) {
                pendingEcho[0] = null
            }
            // Block text input when editor is read-only
            if (!session.editable) {
                hiddenTextValue = TextFieldValue("")
                return@BasicTextField
            }
            // Check if a vim-like extension wants to suppress text input.
            val shouldSuppress = session.state.facet(inputSuppressor)
                .any { it.invoke() }
            if (shouldSuppress) {
                hiddenTextValue = TextFieldValue("")
                return@BasicTextField
            }
            // Filter control characters (Tab, etc.) that leak through
            // when their key events aren't consumed by the keymap.
            // Preserve newlines — they are valid input (e.g. paste).
            val inserted = newValue.text.filter {
                it == '\n' || !it.isISOControl()
            }
            if (inserted.isNotEmpty()) {
                val sel = session.state.selection.main
                val from = sel.from
                val to = sel.to
                val newCursor = DocPos(from.value + inserted.length)
                session.dispatch(
                    TransactionSpec(
                        changes = ChangeSpec.Single(
                            from = from,
                            to = to,
                            insert = inserted.asInsert()
                        ),
                        selection = SelectionSpec.CursorSpec(newCursor),
                        userEvent = "input.type"
                    )
                )
            }
            // Reset to empty for next input
            hiddenTextValue = TextFieldValue("")
        },
        modifier = Modifier
            .testTag("KodeMirror_input")
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                impl.hasFocus = focusState.isFocused
            }
            .onPreviewKeyEvent { event ->
                // <本仓库补丁> 输入法组字期间的按键属于候选窗，这一路整个让开，交给平台输入法
                // （与上面 `onValueChange` 的补丁是一件事的两面，见 README「本仓库补丁」第 10 处）。
                //
                // 组字中会用到回车（确认候选）、空格 / 数字（选词）、退格（删拼音）——这些在本
                // 编辑器的默认键位里都有绑定（换行 / 删除），不让开就会被抢去改文档：回车既确认
                // 了候选又插了一个换行、退格删的是正文而不是拼音，候选窗当场崩掉。
                //
                // 让开（返回 `false`）而不是吞掉：这个隐藏框本身就是 `BasicTextField`，交给它按
                // 普通文本框那套组字逻辑处理即可，本编辑器不该在这时插手。
                if (hiddenTextValue.composition != null) return@onPreviewKeyEvent false
                // If the document-level callback already handled this
                // keydown, skip to avoid double-handling.
                if (event.type == KeyEventType.KeyDown &&
                    keyProcessedByCallback[0]
                ) {
                    keyProcessedByCallback[0] = false
                    // The document-level handler already recorded this keydown's
                    // echo (or cleared it); don't clobber it.
                    return@onPreviewKeyEvent true
                }
                // Every keydown starts a fresh keystroke, so nothing is left to
                // echo from the previous one. This runs on every target, which is
                // what stops the suppression outliving its keystroke (#294).
                if (event.type == KeyEventType.KeyDown) {
                    pendingEcho[0] = null
                }
                val consumed = handleKeyEvent(session, event)
                if (consumed) {
                    // The keymap took the key, so any text the platform would
                    // still enter for it is a duplicate.
                    pendingEcho[0] = typedText(event)
                } else if (event.type == KeyEventType.KeyDown && session.editable) {
                    // When the keymap doesn't consume a printable character,
                    // insert it directly. On wasmJs with canvas focus, the
                    // browser doesn't generate a text input event on the
                    // BasicTextField's backing element, so onValueChange
                    // won't fire. This bridges the gap.
                    // Don't insert for modified keys (Ctrl+A, Alt+X, etc.)
                    // — those are shortcuts, not text input.
                    val char = typedText(event)
                    if (char != null) {
                        val sel = session.state.selection.main
                        val newCursor = DocPos(sel.from.value + char.length)
                        session.dispatch(
                            TransactionSpec(
                                changes = ChangeSpec.Single(
                                    from = sel.from,
                                    to = sel.to,
                                    insert = char.asInsert()
                                ),
                                selection = SelectionSpec.CursorSpec(newCursor),
                                userEvent = "input.type"
                            )
                        )
                        pendingEcho[0] = char
                        return@onPreviewKeyEvent true
                    }
                }
                consumed
            }
    )

    LazyColumn(
        state = lazyState,
        contentPadding = PaddingValues(
            top = theme.layout.contentTopPadding,
            bottom = theme.layout.contentBottomPadding
        )
    ) {
        items(
            items = columnItems,
            key = { item ->
                when (item) {
                    is ColumnItem.TextLine -> "line-${item.lineNumber}"
                    is ColumnItem.BlockWidgetItem -> "widget-${item.from}-${item.type}"
                }
            }
        ) { item ->
            when (item) {
                is ColumnItem.TextLine -> {
                    val capturedLineNum = item.lineNumber
                    val capturedFrom = item.from
                    var textLayout by remember {
                        mutableStateOf<TextLayoutResult?>(null)
                    }

                    // In no-wrap mode (CM6 default) each line occupies a single
                    // visual row of fixed height. In wrap mode the line may grow
                    // to multiple rows, so only pin a minimum height.
                    val lineModifier: Modifier = if (wrapLines) {
                        Modifier.fillMaxWidth().heightIn(min = lineHeightDp)
                    } else {
                        Modifier.fillMaxWidth().height(lineHeightDp)
                    }
                    var contentExtraModifier: Modifier = Modifier
                        .padding(start = 6.dp, end = 2.dp)
                    var hasLineBackground = false
                    for (deco in item.lineDecorations) {
                        val bg = deco.spec.style?.background
                        if (bg != null && bg != Color.Unspecified) {
                            contentExtraModifier = contentExtraModifier.background(bg)
                            hasLineBackground = true
                        }
                    }
                    // Test hook (#85): record the laid-out width of a
                    // background-highlighted line's content box so the
                    // regression test can assert the highlight fills the
                    // viewport in no-wrap mode rather than stopping at the text.
                    if (hasLineBackground && !wrapLines) {
                        contentExtraModifier = contentExtraModifier.onSizeChanged {
                            impl.lastActiveLineContentWidthPx = it.width
                        }
                    }
                    Row(
                        modifier = lineModifier,
                        // On a wrapped (multi-row) line, top-align so the gutter
                        // line number sits beside the first visual row rather
                        // than being centered across all rows.
                        verticalAlignment = if (wrapLines) {
                            Alignment.Top
                        } else {
                            Alignment.CenterVertically
                        }
                    ) {
                        if (hasGutters) {
                            GutterView(
                                session = session,
                                lineNumber = item.lineNumber.value,
                                modifier = Modifier.width(gutterWidthDp)
                            )
                        }
                        // Viewport box: fills the remaining width and clips to
                        // it. In no-wrap mode it scrolls horizontally so long
                        // lines stay reachable; the gutter (outside this box)
                        // stays fixed. In wrap mode there is no horizontal
                        // scroll — lines wrap to fit the width instead.
                        var viewportModifier: Modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                        if (!wrapLines) {
                            // Capture the viewport's actual visible width from
                            // the weight(1f) box (before the horizontalScroll,
                            // which would otherwise measure the child with an
                            // infinite max width). Used as an additional content
                            // min-width so highlights fill the viewport (#85).
                            viewportModifier = viewportModifier
                                .onSizeChanged { viewportWidthPx.value = it.width }
                                .horizontalScroll(horizontalScrollState)
                        }
                        Box(modifier = viewportModifier) {
                            // Content box holds the actual text at its natural
                            // width (no-wrap) or constrained to the viewport
                            // width (wrap). Selection overlay and layout capture
                            // live here so the recorded left offset already
                            // accounts for the horizontal scroll position,
                            // letting click->offset resolve across the full line.
                            var contentModifier: Modifier = Modifier
                                .fillMaxHeight()
                            contentModifier = if (wrapLines) {
                                contentModifier.fillMaxWidth()
                            } else {
                                // Min width = the wider of the widest line and
                                // the viewport. The first keeps long lines at
                                // their full natural width (uniform scroll range,
                                // #68); the second makes a short line's content
                                // (and its line-decoration background) fill the
                                // viewport instead of stopping at the text (#85).
                                val minW = with(density) {
                                    maxOf(
                                        maxLineWidthPx.value,
                                        viewportWidthPx.value
                                    ).toDp()
                                }
                                contentModifier
                                    .wrapContentWidth(
                                        align = Alignment.Start,
                                        unbounded = true
                                    )
                                    .widthIn(min = minW)
                            }
                            // <本仓库补丁> 正文里那个 `…`（折叠起来的块）：光标移上去变手型，点一下展开。
                            //
                            // `…` 是 `DecorationApplication` 注进正文的一个**字符**（替换类装饰带的
                            // widget 从来没被画出来，见 `FoldWidget`），没有节点可以挂 `clickable` 或
                            // `pointerHoverIcon`——只能自己接手势、按排版结果判断指针在不在它身上。
                            //
                            // 于是「看得出能点」与「点得到」共用同一个命中判定（[foldPlaceholderHit]）：
                            // 各写一份迟早会漂成「显示手型但点不动」或者反过来。
                            //
                            // 与编辑器根节点那套「按下即落光标」的分工：那一套在 Main 阶段**子节点
                            // 优先**，这两个手势挂在正文这一层、是它的子节点，命中就消费掉（这一次
                            // 点击不当成「在正文上点了一下」），没命中就放它过去。
                            val foldModifier = foldPlaceholderModifier(
                                key = item.from,
                                placeholder = item.foldPlaceholderOffset,
                                layout = { textLayout },
                                onClick = { placeholder ->
                                    // 先换成文档偏移再解折叠区间：区间会随编辑平移，而 `item` 里的
                                    // `from/to` 是排版那一刻的旧值，拿它去查会落空。
                                    unfoldFoldAt(
                                        session,
                                        item.from.value +
                                            unmapTabOffset(placeholder, item.tabOffsetMap)
                                    )
                                },
                            )
                            Box(
                                modifier = contentModifier
                                    .then(contentExtraModifier)
                                    .then(foldModifier)
                                    .drawSelectionOverlay(
                                        state,
                                        item.from.value,
                                        item.to.value,
                                        theme,
                                        textLayout,
                                        item.tabOffsetMap,
                                        // <本仓库补丁> 光标明灭。取值函数（不是 Boolean）：
                                        // 半秒翻转只让这些行重绘，不重组。
                                        caretVisible
                                    )
                                    .onGloballyPositioned { contentCoords ->
                                        val layout = textLayout
                                            ?: return@onGloballyPositioned
                                        val editorCoords = editorCoordinates
                                        if (editorCoords != null &&
                                            editorCoords.isAttached
                                        ) {
                                            val pos = editorCoords.localPositionOf(
                                                contentCoords,
                                                Offset.Zero
                                            )
                                            lineLayoutCache.store(
                                                capturedLineNum.value,
                                                capturedFrom.value,
                                                pos.y,
                                                pos.x,
                                                layout
                                            )
                                            pendingLineLayouts
                                                .remove(capturedLineNum.value)
                                        } else {
                                            pendingLineLayouts[capturedLineNum.value] =
                                                PendingLineLayout(
                                                    capturedLineNum.value,
                                                    capturedFrom.value,
                                                    contentCoords,
                                                    layout
                                                )
                                        }
                                    }
                            ) {
                                BasicText(
                                    text = item.content,
                                    style = contentStyle,
                                    softWrap = wrapLines,
                                    onTextLayout = { result: TextLayoutResult ->
                                        textLayout = result
                                        textLayoutResults[capturedLineNum.value] =
                                            result
                                        if (!wrapLines &&
                                            result.size.width > maxLineWidthPx.value
                                        ) {
                                            maxLineWidthPx.value = result.size.width
                                        }
                                    }
                                )
                                for (widget in item.inlineWidgets) {
                                    widget.spec.widget.Content()
                                }
                            }
                        }
                    }
                }

                is ColumnItem.BlockWidgetItem -> {
                    item.widget.spec.widget.Content()
                }
            }
        }
    }

    // Sync viewport/height tracking for ViewUpdate flags.
    // Evict stale cache entries for scrolled-off lines.
    // IMPORTANT: lazyState.layoutInfo must NOT be read during
    // composition — it produces a new object every layout pass,
    // creating an infinite recomposition/layout cycle. Use
    // snapshotFlow to observe it outside of composition.
    val firstVisible = lazyState.firstVisibleItemIndex
    LaunchedEffect(firstVisible) {
        impl.lastFirstVisibleItem = firstVisible
    }
    LaunchedEffect(session) {
        snapshotFlow {
            lazyState.layoutInfo.visibleItemsInfo.let { items ->
                items.firstOrNull()?.index to items.size
            }
        }.collect { (startIdx, count) ->
            try {
                val startIndex = startIdx ?: 0
                impl.lastFirstVisibleItem = startIndex
                impl.lastVisibleItemCount = count
                val visibleLineNumbers = columnItems
                    .drop(startIndex)
                    .take(count.coerceAtLeast(1))
                    .filterIsInstance<ColumnItem.TextLine>()
                    .map { it.lineNumber.value }
                    .toSet()
                if (visibleLineNumbers.isNotEmpty()) {
                    lineLayoutCache.evict(visibleLineNumbers)
                }
            } catch (t: Throwable) {
                // Defense-in-depth (#155): this session-scoped collector drives
                // viewport tracking + stale-line cache eviction. A throw here
                // would complete the LaunchedEffect exceptionally and it would
                // not relaunch (key `session`), permanently breaking viewport
                // reporting and cache eviction for the session. Rethrow
                // CancellationException so disposal still cancels the coroutine.
                if (t is CancellationException) throw t
            }
        }
    }

    // Tooltip layer
    TooltipLayer(session = session)
}

/** <本仓库补丁> 光标闪烁的半周期：一明一灭共 1 秒，与 Compose 原生输入框同节奏。 */
private const val CaretBlinkHalfPeriodMillis = 500L

/** <本仓库补丁> 连击上限：单击 / 双击 / 三击三档，第四下重新从单击开始。 */
private const val MaxClickCount = 3

/**
 * <本仓库补丁> 连击的间隔窗口。
 *
 * 系统那个值是 300ms（桌面 skiko 的 `ViewConfiguration.doubleTapTimeoutMillis` 是个常量，不读
 * 系统设置），本项目按需求放宽到 500ms——三击要连着落在窗口里，宽一点更跟手。
 *
 * 量法（上一击抬手 → 这一击按下）、最小间隔（`doubleTapMinTimeMillis`，40ms）与落点容差
 * （slop）都照系统那套，见手势里那一处。
 */
private const val TapRepeatIntervalMillis = 500L

/**
 * <本仓库补丁> 点折叠占位符 `…` 时，命中区域左右各放宽多少。
 *
 * `…` 在屏幕上只有几个像素宽（一个省略号字符），严格按字形判定基本点不中。左右各让出这么多，
 * 点起来才是「点在它上面」。高度方向不让：行高本来就只有十几像素，再放宽就会吃掉上下相邻的信息。
 */
private val FoldPlaceholderTapSlop = 4.dp

/**
 * <本仓库补丁> 正文里那个 `…` 的手势层：光标停在它身上时变手型，点它时回调出去。
 *
 * 两件事共用同一个命中判定（[foldPlaceholderHit]），因为它们必须回答同一个问题「指针在不在
 * `…` 上」——各写一份迟早会漂成「显示手型却点不动」。
 *
 * 光标是**自己算出来再设**的，不是靠 `clickable` 那种「有节点的地方自动变手型」：`…` 只是正文里的
 * 一个字符，没有节点。命中与否存进状态，`pointerHoverIcon` 随之切换——指针不动时不会反复写进
 * 状态（值没变，Compose 不会重组）。
 *
 * @param key 让手势在换行 / 换会话时重建（它的值本身不参与判定，命中判定用的是最新的 [layout]）。
 * @param placeholder `…` 在这一行渲染文本里的偏移；`null`（不是折叠行）时不挂任何手势。
 * @param layout 取当前排版结果；正文还没排过版时为 `null`。
 * @param onClick 命中之后要做什么，参数就是 [placeholder]。
 */
@Composable
private fun foldPlaceholderModifier(
    key: Any?,
    placeholder: Int?,
    layout: () -> TextLayoutResult?,
    onClick: (Int) -> Unit,
): Modifier {
    if (placeholder == null) return Modifier
    val slop = with(LocalDensity.current) { FoldPlaceholderTapSlop.toPx() }
    var hovering by remember(key) { mutableStateOf(false) }
    return Modifier
        .pointerHoverIcon(if (hovering) PointerIcon.Hand else PointerIcon.Text)
        .pointerInput(key, placeholder) {
            awaitPointerEventScope {
                while (true) {
                    // 只看不消费：这一路只为了算光标形状，别的（正文落光标、拖选）照旧。
                    val position = awaitPointerEvent(PointerEventPass.Initial)
                        .changes.firstOrNull()?.position
                        ?: continue
                    hovering = foldPlaceholderHit(position, layout(), placeholder, slop)
                }
            }
        }
        .pointerInput(key, placeholder) {
            detectTapGestures { tap ->
                if (foldPlaceholderHit(tap, layout(), placeholder, slop)) onClick(placeholder)
            }
        }
}

/**
 * <本仓库补丁> 指针是不是落在折叠占位符 `…` 上。
 *
 * 点击坐标与排版坐标是同一个原点：正文那一层的 `Box` 就是文字所在的那一层，那点内边距挂在它
 * **里面**（`contentExtraModifier`），不会把两者错开。
 */
private fun foldPlaceholderHit(
    position: Offset,
    layout: TextLayoutResult?,
    placeholder: Int,
    slop: Float,
): Boolean {
    val box: Rect = layout?.getBoundingBox(placeholder) ?: return false
    return position.x >= box.left - slop &&
        position.x <= box.right + slop &&
        position.y >= box.top &&
        position.y <= box.bottom
}

/**
 * <本仓库补丁> 一次点击该落成什么选区：单击落光标，双击选中该处的词，三击选中该行。
 *
 * 上游只落光标（`selectWord` / `selectLine` 是键位命令，要先落光标再扩展，**两步**）。这里在
 * **一个事务**里直接算出最终选区：分两步会占掉两次撤销，用户按一下撤销只退掉一半。
 *
 * 取词用上游的 `EditorState.wordAt`；取行到**换行之前**为止，与平台（原生框的段落选择）一致——
 * 平台算段落末尾给的就是换行符的下标（`findParagraphEnd`），光标因此落在**本行行尾**，不会甩到
 * 下一行首。上游那个键盘命令 `selectLine` 反而带上换行（「整行拿去粘贴」的语义），三击不沿用它。
 * 取不到词时退回光标，与系统输入框一致。
 *
 * 注意 `wordAt` 有一处**不对称**（上游如此，浏览器里的 CodeMirror 6 也一样）：它按 `pos` **左侧**
 * 的字符类别向前扩、按 `pos` **处**的字符类别向后扩。于是「双击落在词的右侧空白上」选中的是**左边
 * 那个词**，只有在两侧都不是词字符的位置（如 `=`、连续空白中间）才取不到词。想改就得改 `wordAt`，
 * 那会连带影响 `selectNextOccurrence` 等命令，因此这里照抄上游，只由 `TapSelectionTest` 钉住。
 *
 * `internal` 是为了让 `TapSelectionTest` 直接喂位置与连击数（手势那一层喂不进真实节奏）。
 */
internal fun dispatchTapSelection(session: EditorSession, pos: Int, clickCount: Int) {
    val state = session.state
    val range = when (clickCount) {
        2 -> state.wordAt(DocPos(pos))?.let { EditorSelection.single(it.from, it.to) }
        3 -> {
            val line = state.doc.lineAt(DocPos(pos))
            // 到**换行之前**为止（不是 `line.to + 1`）：整行被选中时，光标只能落在选区两端之一，
            // 而平台那个落点是段落末尾。带上换行的话，光标就画到下一行首去了。
            // `single` 的 head 就是光标那一端，所以这里 head = `line.to`。
            //
            // 用 `single` 而不是 `range`：后者造的是 `SelectionRange`（上游 `selectLine` 里
            // 还要再套一层 `EditorSelection.create`），这里直接要一个单区间选区。
            EditorSelection.single(line.from, line.to)
        }
        else -> null
    }
    if (range == null) {
        // 单击：与上游那条逐字相同（连 `userEvent` 都没有），保持既有行为不变。
        session.dispatch(
            TransactionSpec(
                selection = SelectionSpec.CursorSpec(DocPos(pos))
            )
        )
        return
    }
    session.dispatch(
        TransactionSpec(
            selection = SelectionSpec.EditorSelectionSpec(range),
            userEvent = "select"
        )
    )
}

/**
 * <本仓库补丁> 竖向滚动条，**照下面 [HorizontalScrollbar] 的样子画**。
 *
 * 上游只画了横向那条：纵向它交给滚轮与光标自动滚动——功能上确实能滚，但**一点提示都没有**，
 * 本仓库把 KodeMirror 当默认实现之后，这一点就直接表现为「滚动条不见了」。
 *
 * 与横向那条：粗细（[ScrollbarThickness]）、滑块下限（[ScrollbarMinThumbLength]）、「一条极淡的
 * 轨道 + 圆角滑块」、纯拖拽——全部一致。三处**不得不不同**：
 *
 *  1. 横向滚的是 `ScrollState`（`value` / `maxValue` 直接就能算），竖向那个行列表只报告「第一个
 *     可见项 + 项内偏移」，内容总高要自己算：本编辑器的每一行等高，所以「可见行平均高 × 总行数」
 *     是精确值，而不是估算。
 *  2. `layoutInfo` **不能在组合里读**——它每次布局都产生一个新对象，在组合里读会每帧重组一次
 *     （上游为此刻意改用 `snapshotFlow`）。因此这里的几何在**绘制**里现算、在手势回调里现算，
 *     都不进组合；组合里只读 `canScrollForward / canScrollBackward` 这两位（只在越过边界时翻转）。
 *  3. **每帧最多落一次位**（见下面 `pending` 那段）。这是这条滚动条唯一真正棘手之处，也踩过坑：
 *     横向那条每次落位只是改 `ScrollState` 里的一个浮点数（正文重新放置一下），而竖向每次落位都
 *     要让 `LazyListState` **重测一屏**。指针事件比帧密得多（高回报率鼠标 / 触控板能到 100Hz 以上），
 *     若每个事件都落一次位，一帧里就会重测好几遍——手感比不写这条滚动条还差。滚轮之所以顺，正是
 *     因为它的**事件率本身低于帧率**，一帧顶多落一次。
 *
 * 拖拽**不能用 `Modifier.draggable`**（横向那条能用，是因为它拖的是 `ScrollState`）：它只给
 * 增量，而按增量去 `scrollBy` 时，行列表的滚动被下一次调用取消，那一段位移就永远丢了——指针
 * 事件比帧密得多，于是滑块一路落后于手指。它还要先越过触摸阈值才开始动。这里改成与
 * `ScrollbarTrack` 同一套：按下即锚定，之后每个事件都从**按下那一刻**重算一个绝对目标。
 */
@Composable
private fun VerticalScrollbar(lazyState: LazyListState, modifier: Modifier = Modifier) {
    val theme = LocalEditorTheme.current
    val thumbColor = theme.foreground.copy(alpha = 0.35f)
    val trackColor = theme.foreground.copy(alpha = 0.08f)
    val minThumbPx = with(LocalDensity.current) { ScrollbarMinThumbLength.toPx() }

    // 轨道长度只能量出来，而拖拽那条换算要用它。放尺寸回调里更新：它不进 `layoutInfo`，
    // 因此不会带出「每帧重组」那类问题。
    var trackLengthPx by remember { mutableStateOf(0f) }

    // 手指要求滚到的内容像素；`null` = 这一帧没有新目标。
    //
    // 手势只写这一个数（一次赋值，完全不碰行列表），落位挪到**帧上**做：`snapshotFlow` 的发射
    // 天然按快照应用（≈ 每帧）合并，且只发最后一个值——于是无论一帧里来了多少个指针事件，行列表
    // 每帧只重测一次。这是本函数上方第 3 点的落地，也是「手感能否达到滚轮档」的唯一关键。
    var pending by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(lazyState) {
        snapshotFlow { pending }.collect { target ->
            if (target == null) return@collect
            pending = null
            scrollToContentOffset(lazyState, target)
        }
    }

    Box(
        modifier = modifier
            .width(ScrollbarThickness)
            .onSizeChanged { trackLengthPx = it.height.toFloat() }
            // 指针落在滚动条上时给回默认箭头：正文那一层是文本光标（I 形），不覆盖的话鼠标移到
            // 这里仍然显示 I 形，看着像还能选字。
            .pointerHoverIcon(PointerIcon.Default)
            .pointerInput(lazyState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drag = verticalDragAnchor(lazyState, down.position.y, trackLengthPx, minThumbPx)
                        // 没得滚（内容刚好放得下）：不接管这次手势，也别吃掉它。
                        ?: return@awaitEachGesture
                    down.consume()
                    // 按下即生效：抓住滑块时等于原地不动，按在轨道空白处则是把滑块中心对到手指
                    // ——与原生那条滚动条同一个手感。这里只写数、不落位，落位交给上面那个收集器。
                    pending = drag.anchor
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        // 从**按下那一刻**重算绝对目标，而不是把增量累加进当前偏移：中途到达边界
                        // 被截掉一部分时也不会丢位移，滑块因此始终与手指对齐。
                        pending = drag.anchor +
                            (change.position.y - down.position.y) * drag.ratio
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 轨道：与横向一样，一条几乎看不见的底，滑块滑到哪里都看得出轨道在哪。
            drawRect(color = trackColor)
            val thumb = verticalThumbOf(lazyState, size.height, minThumbPx) ?: return@Canvas
            val inset = ScrollbarThumbInset.toPx()
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(inset, thumb.start),
                size = Size((size.width - inset * 2f).coerceAtLeast(0f), thumb.length),
                cornerRadius = CornerRadius(ScrollbarCornerRadius.toPx())
            )
        }
    }
}

/** 两条滚动条**共用**的粗细与滑块下限。放在一处，横向那条也读它，免得两条漂开。 */
private val ScrollbarThickness = 10.dp
private val ScrollbarMinThumbLength = 24.dp

/** 滑块相对轨道的内缩，两条同量：画出来就是「10dp 轨道 + 8dp 滑块」。 */
private val ScrollbarThumbInset = 1.dp

/** 滑块圆角，两条同一取值。 */
private val ScrollbarCornerRadius = 4.dp

/** 滑块在轨道上的位置与长度（像素，沿轨道方向）。 */
private class ScrollbarThumb(val start: Float, val length: Float)

/**
 * 行列表的一次读数：行高、视口高、内容总高、已滚像素。
 *
 * **只在绘制 / 手势里取**（它读 `layoutInfo`）。做成一次读全，是为了让同一次换算里的几个数
 * 出自**同一份**快照：分别去读会各自读到一个新对象，算出来的滑块与落点就可能不是一套。
 */
private class VerticalMetrics(
    val itemHeight: Float,
    val viewport: Float,
    val content: Float,
    val scrolled: Float,
    val itemCount: Int,
) {
    /** 内容比视口多出来的像素；不大于 0 就是放得下。 */
    val scrollable: Float get() = content - viewport
}

/**
 * 取一次读数。每一行等高，因此「可见行平均高 × 总行数」就是内容总高——**不是估算**。
 *
 * 视口取 `viewportEndOffset - viewportStartOffset`（不含列表内边距，与首尾判定同量纲）。
 * 尚未布局（没有可见项）或取不到有效值时给 `null`。
 */
private fun verticalMetrics(state: LazyListState): VerticalMetrics? {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty() || info.totalItemsCount <= 0) return null
    val itemHeight = visible.sumOf { it.size }.toFloat() / visible.size
    if (itemHeight <= 0f) return null
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (viewport <= 0f) return null
    return VerticalMetrics(
        itemHeight = itemHeight,
        viewport = viewport,
        content = itemHeight * info.totalItemsCount,
        scrolled = state.firstVisibleItemIndex * itemHeight + state.firstVisibleItemScrollOffset,
        itemCount = info.totalItemsCount,
    )
}

/** 滑块几何；没有可滚空间时给 `null`（这时不该画滑块）。**只在绘制 / 手势里调用**。 */
private fun verticalThumbOf(
    state: LazyListState,
    trackLength: Float,
    minThumbPx: Float
): ScrollbarThumb? {
    if (trackLength <= 0f) return null
    val metrics = verticalMetrics(state) ?: return null
    if (metrics.scrollable <= 0f) return null

    val thumbLength = (trackLength * metrics.viewport / metrics.content)
        .coerceIn(minThumbPx.coerceAtMost(trackLength), trackLength)
    // 行高取平均、真实布局可能有几像素出入，首尾改用 `LazyListState` 的精确判定兜住，
    // 这样滚到头时滑块一定贴住轨道两端。
    val fraction = when {
        !state.canScrollBackward -> 0f
        !state.canScrollForward -> 1f
        else -> (metrics.scrolled / metrics.scrollable).coerceIn(0f, 1f)
    }
    return ScrollbarThumb(start = fraction * (trackLength - thumbLength), length = thumbLength)
}

/**
 * 一次拖拽的锚点：把「手指在轨道上的位置」换成「内容该滚到哪」的那条直线。
 *
 *     content(y) = anchor + (y - downY) * ratio
 *
 * [ratio] 是「内容像素 / 轨道像素」，即滑块走完全程对应内容滚完全程；`anchor` 由**按下那一刻**
 * 的滑块位置与手指位置定出：抓住滑块就保持抓住时的相对位置（按下不动），按在轨道空白处则把滑块
 * 中心对到手指。
 *
 * 关键在于它是**绝对**映射：每个事件都从按下点重算目标，不把增量累加进「当前偏移」。行列表的
 * 滚动会被下一次调用取消，累加式一旦遇到取消就丢掉那一段位移，滑块会一路落后于手指。
 */
private class VerticalDragAnchor(val anchor: Float, val ratio: Float)

/** 定出拖拽锚点；没有可滚空间时给 `null`（这时不该接管这次手势）。 */
private fun verticalDragAnchor(
    state: LazyListState,
    downY: Float,
    trackLength: Float,
    minThumbPx: Float
): VerticalDragAnchor? {
    val thumb = verticalThumbOf(state, trackLength, minThumbPx) ?: return null
    val metrics = verticalMetrics(state) ?: return null
    val travel = trackLength - thumb.length
    if (travel <= 0f || metrics.scrollable <= 0f) return null

    val ratio = metrics.scrollable / travel
    val grab = if (downY in thumb.start..(thumb.start + thumb.length)) {
        downY - thumb.start
    } else {
        thumb.length / 2f
    }
    // `(downY - grab) * ratio` 就是「滑块起点这一刻对应的内容偏移」：抓住滑块时它等于当前已滚
    // 像素（按下因此不动），按在空白处则是把滑块中心对到手指之后的位置。
    return VerticalDragAnchor(anchor = (downY - grab) * ratio, ratio = ratio)
}

/**
 * 把内容滚到 [offset] 像素处（夹在范围内）。
 *
 * **一次调用就是一帧的开销**：`scrollToItem` 会让行列表重测一屏。所以它只应由 [VerticalScrollbar]
 * 那个按帧合并的收集器调用，**绝不能在指针事件里逐次调用**（踩过这个坑：把落位改成
 * `dispatchRawDelta` 后每个事件同步落一次，一帧里重测好几遍，比原来还卡）。
 *
 * 用 `scrollToItem` 而不是 `dispatchRawDelta`：后者的滚动范围受限于上游已经铺开的窗口，超出的
 * 部分会被少消费掉——而拖动本来就是一跳几百像素，正落在它的短板上。`scrollToItem` 接受任意行号，
 * 跳多远都一步到位。目标仍是**绝对**的（由 [verticalDragAnchor] 从按下点算出），所以哪怕某一帧
 * 被跳过、或到边界被夹住，滑块也不会漂移。
 */
private suspend fun scrollToContentOffset(state: LazyListState, offset: Float) {
    val metrics = verticalMetrics(state) ?: return
    val clamped = offset.coerceIn(0f, metrics.scrollable.coerceAtLeast(0f))
    val index = (clamped / metrics.itemHeight).toInt().coerceIn(0, metrics.itemCount - 1)
    val itemOffset = (clamped - index * metrics.itemHeight).roundToInt()
    state.scrollToItem(index, itemOffset)
}

/**
 * A custom horizontal scrollbar built entirely from commonMain Compose
 * primitives so it compiles on every target (jvm, android, wasmJs, native).
 *
 * The desktop/skiko `HorizontalScrollbar` is NOT usable here because the
 * androidMain source set depends on jvmMain, and that API does not exist on
 * Android. This draws a thin track with a draggable thumb instead.
 *
 * Thumb sizing: the visible fraction of the content is
 * `viewportPx / (viewportPx + scrollState.maxValue)`, so the thumb width is
 * `trackWidth * viewportFraction`. The thumb offset maps the scroll position
 * onto the remaining track travel: `(value / maxValue) * (trackWidth - thumb)`.
 *
 * Dragging maps thumb-track pixels back to content pixels by the inverse ratio
 * `(trackWidth - thumb)` of travel ↔ `maxValue` of scroll, so a full thumb
 * sweep scrolls the full content.
 *
 * 轨道粗细 / 滑块内缩 / 圆角与竖向那条**共用** [ScrollbarThickness] / [ScrollbarThumbInset] /
 * [ScrollbarCornerRadius]：两条看起来才是「同一条滚动条换了个方向」。
 */
@Composable
private fun HorizontalScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val theme = LocalEditorTheme.current
    val scope = rememberCoroutineScope()
    val thumbColor = theme.foreground.copy(alpha = 0.35f)
    val trackColor = theme.foreground.copy(alpha = 0.08f)

    var trackWidthPx by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
            .testTag("KodeMirror_hscroll")
            .height(ScrollbarThickness)
            .background(trackColor)
            // <本仓库补丁> 滚动条上是箭头光标（I 形只在正文里）。正文那一层现在设了 I 形，这条
            // 本是它的兄弟、不受影响，写在这里是为了「两条滚动条形状一致」这件事有个明确出处
            // （竖向那条见 `VerticalScrollbar`）。
            .pointerHoverIcon(PointerIcon.Default)
            .onSizeChanged { trackWidthPx = it.width }
    ) {
        val maxValue = scrollState.maxValue
        val trackWidth = trackWidthPx.toFloat()
        if (trackWidth > 0f && maxValue > 0) {
            // Visible fraction of the content -> thumb length as a fraction of
            // the track. The total content width in px is viewport + maxValue.
            val viewportFraction = trackWidth / (trackWidth + maxValue)
            val minThumbPx = with(LocalDensity.current) { ScrollbarMinThumbLength.toPx() }
            val thumbWidthPx = (trackWidth * viewportFraction)
                .coerceIn(minThumbPx.coerceAtMost(trackWidth), trackWidth)
            val travel = trackWidth - thumbWidthPx
            val thumbOffsetPx = if (maxValue > 0 && travel > 0f) {
                (scrollState.value.toFloat() / maxValue) * travel
            } else {
                0f
            }
            val density = LocalDensity.current
            val thumbWidthDp = with(density) { thumbWidthPx.toDp() }

            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
                    .width(thumbWidthDp)
                    // 铺满整条轨道再内缩 [ScrollbarThumbInset]：画出来 8dp、上下各留 1dp，
                    // 与竖向那条的滑块同一厚度、同样居中（这里原来是 `height(8.dp)` + 内缩 1dp，
                    // 画出来只有 6dp 且贴在轨道顶部——两条宽度就是这么看岔的）。
                    .fillMaxHeight()
                    .padding(vertical = ScrollbarThumbInset)
                    .clip(RoundedCornerShape(ScrollbarCornerRadius))
                    .background(thumbColor)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { deltaPx ->
                            if (travel > 0f) {
                                // Map thumb-track pixels back to content
                                // pixels: full travel == full maxValue scroll.
                                val scrollDelta = deltaPx * (maxValue / travel)
                                scope.launch {
                                    scrollState.scrollBy(scrollDelta)
                                }
                            }
                        }
                    )
            )
        }
    }
}

/**
 * Guard the editor [Column] against an unbounded (`Infinity`) incoming
 * `maxHeight` (#33).
 *
 * The editor fills the height it is given. Under a bounded height this is a
 * no-op — the original constraints pass through unchanged and the inner line
 * list scrolls. But under an unbounded vertical constraint (a parent with
 * `Modifier.verticalScroll(...)`, `wrapContentHeight()`, or another scrolling
 * container) two things break:
 *  - `Column` `weight(1f)` distributes the *remaining* (infinite) space, which
 *    collapses the content Box — and the editor — to zero height; and
 *  - the inner `LazyColumn` throws, because a vertically-scrollable component
 *    may not be measured with an infinite `maxHeight`.
 *
 * To degrade gracefully we replace an infinite `maxHeight` with the document's
 * natural content height ([contentHeightPx], top/bottom padding + all lines).
 * The editor then grows to fit the whole document and the surrounding scroll
 * container scrolls it, instead of collapsing or crashing.
 */
private fun Modifier.boundUnconstrainedHeight(contentHeightPx: Float): Modifier =
    layout { measurable, constraints ->
        val effective = if (constraints.maxHeight == Constraints.Infinity) {
            constraints.copy(
                maxHeight = contentHeightPx.roundToInt().coerceAtLeast(0)
            )
        } else {
            constraints
        }
        val placeable = measurable.measure(effective)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }

/** Draw editor and gutter backgrounds behind content. */
private fun Modifier.drawEditorBackground(
    theme: EditorTheme,
    hasGutters: Boolean,
    gutterWidthDp: Dp,
    contentHeightPx: Float
): Modifier = drawWithContent {
    drawRect(theme.background)
    if (hasGutters) {
        val w = gutterWidthDp.toPx()
        val contentH = contentHeightPx.coerceAtMost(size.height)
        drawRect(
            color = theme.gutterBackground,
            topLeft = Offset.Zero,
            size = Size(w, contentH)
        )
        val bc = theme.gutterBorderColor
        if (bc != Color.Transparent) {
            drawLine(
                color = bc,
                start = Offset(w - 0.5f, 0f),
                end = Offset(w - 0.5f, contentH),
                strokeWidth = 1f
            )
        }
    }
    drawContent()
}

/**
 * Create and remember an [EditorSession] with the given document text and extensions.
 *
 * **Initial-value-only parameters:** [doc] and [extensions] are used only when the session
 * is first created. Subsequent recompositions with different values have no effect — the
 * session retains the state from its initial creation, including cursor position, selection,
 * and undo history.
 *
 * This matches CodeMirror 6's model, where an [EditorState] (and the view that owns it) is
 * created once and then mutated exclusively through transactions. To update the document or
 * reconfigure extensions after creation, dispatch a transaction via [EditorSession.dispatch]:
 *
 * ```kotlin
 * // Update document content
 * session.dispatch(TransactionSpec(changes = ChangeSpec.Single(0, session.state.doc.length, newDoc.asInsert())))
 *
 * // Reconfigure extensions
 * session.dispatch(TransactionSpec(effects = listOf(StateEffect.reconfigure(newExtensions))))
 * ```
 *
 * @param doc        Initial document text. Ignored after first composition.
 * @param extensions Initial set of extensions. Ignored after first composition.
 * @param onUpdate   Callback invoked for every transaction dispatched to the session.
 */
@Composable
fun rememberEditorSession(
    doc: String = "",
    extensions: Extension? = null,
    onUpdate: (Transaction) -> Unit = {}
): EditorSession = remember {
    val config = EditorStateConfig(
        doc = doc.asDoc(),
        extensions = extensions
    )
    EditorSession(EditorState.create(config), onUpdate)
}

/**
 * Create and remember an [EditorSession] from an [EditorStateConfig].
 *
 * **Initial-value-only parameter:** [config] is used only when the session is first created.
 * Subsequent recompositions with a different [config] have no effect — the session retains
 * the state from its initial creation, including cursor position, selection, and undo history.
 *
 * This matches CodeMirror 6's model, where an [EditorState] (and the view that owns it) is
 * created once and then mutated exclusively through transactions. To update the document or
 * reconfigure extensions after creation, dispatch a transaction via [EditorSession.dispatch].
 *
 * @param config   Initial editor state configuration. Ignored after first composition.
 * @param onUpdate Callback invoked for every transaction dispatched to the session.
 */
@Composable
fun rememberEditorSession(
    config: EditorStateConfig,
    onUpdate: (Transaction) -> Unit = {}
): EditorSession = remember { EditorSession(EditorState.create(config), onUpdate) }

/**
 * Scroll [lazyState] the minimal amount needed to bring [targetIndex] fully
 * into the visible viewport.
 *
 * Mirrors CodeMirror 6's `scrollIntoView` "nearest" behavior: if the item is
 * already fully visible, do nothing; if it is above the viewport, align it to
 * the top; if it is below the viewport, align it to the bottom. Items larger
 * than the viewport are aligned to the top so their start is visible.
 */
internal suspend fun scrollItemIntoView(lazyState: LazyListState, targetIndex: Int) {
    val layoutInfo = lazyState.layoutInfo
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        // Nothing laid out yet (e.g. first frame). Best-effort: jump to the item.
        lazyState.scrollToItem(targetIndex)
        return
    }
    // Bounds of the usable viewport (inside content padding).
    val viewportStart = layoutInfo.viewportStartOffset
    val viewportEnd = layoutInfo.viewportEndOffset
    val viewportHeight = viewportEnd - viewportStart

    val existing = visible.firstOrNull { it.index == targetIndex }
    val firstVisibleIndex = visible.first().index

    // Decide whether the target is above the viewport, below it, or already
    // visible. For an item that is laid out, use its measured bounds; for one
    // scrolled off-screen, infer direction from its index.
    val above: Boolean
    val below: Boolean
    if (existing != null) {
        above = existing.offset < viewportStart
        below = existing.offset + existing.size > viewportEnd
    } else {
        above = targetIndex < firstVisibleIndex
        below = !above
    }

    when {
        // Above the viewport (or fully off-screen above): align top edge to
        // the viewport top.
        above -> lazyState.scrollToItem(targetIndex)
        // Below the viewport (or off-screen below): bring the item to the top
        // first (guarantees it is laid out and visible), then, if it fits with
        // room to spare, scroll backward so it sits at the viewport bottom and
        // the preceding context above the caret stays on screen — matching
        // CM6's "nearest" alignment.
        below -> {
            lazyState.scrollToItem(targetIndex)
            val now = lazyState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetIndex }
            if (now != null) {
                val slack = (viewportHeight - now.size).toFloat()
                if (slack > 0f) {
                    // scrollBy(negative) moves content down, revealing earlier
                    // items above and pushing the target toward the bottom.
                    lazyState.scrollBy(-slack)
                }
            }
        }
        // else: already fully visible, nothing to do.
    }
}

/** Layout info saved when [onGloballyPositioned] fires before the parent. */
private class PendingLineLayout(
    val lineNumber: Int,
    val lineFrom: Int,
    val coords: LayoutCoordinates,
    val result: TextLayoutResult
)

/**
 * Pick the index of the visible text line a hit at vertical position [y]
 * resolves to. [tops] and [sizes] are the parallel arrays of each visible
 * line's top and height (live LazyColumn layout, in layout order).
 *
 * Returns the line whose `tops[i]..tops[i] + sizes[i]` range contains [y]; if
 * [y] falls in an inter-line gap or outside the rendered range, it clamps to
 * the nearest line — preferring the line BELOW (first whose top is past [y]) so
 * downward gaps resolve consistently with [LineLayoutCache.posAtCoords], then
 * the last visible line for a [y] below everything. Returns null ONLY when
 * there are no visible lines.
 *
 * Extracted from [posFromVisibleItems] so this never-null clamping — the #165
 * fix that stops a click falling back to the stale absolute-position cache — is
 * unit-testable without a live LazyColumn.
 */
internal fun chooseHitLineIndex(tops: FloatArray, sizes: FloatArray, y: Float): Int? {
    if (tops.isEmpty()) return null
    for (i in tops.indices) {
        val top = tops[i]
        if (y >= top && y < top + sizes[i]) return i
    }
    for (i in tops.indices) {
        if (tops[i] > y) return i
    }
    return tops.size - 1
}

/**
 * Compute a document position from a tap offset using the [LazyColumn]'s own
 * layout info (the LIVE line positions), not the [LineLayoutCache].
 *
 * This is the authoritative hit-test for click/drag/hover: it reads
 * `lazyState.layoutInfo` every call, so it tracks scrolling even when the
 * cache's `topPx`/`leftPx` (written in `onGloballyPositioned`) have gone stale
 * because the callback didn't re-fire after a scroll (#165). It returns null
 * ONLY when there are no visible text lines at all (e.g. an empty document or
 * the very first frame); for any in-viewport coordinate it resolves to a line,
 * clamping a y that falls in an inter-line gap or outside the rendered range to
 * the nearest visible line — so the caller never has to fall back to the
 * absolute-position cache, which is the stale source the click used to land on
 * (a pre-scroll, up-and-to-the-left position).
 */
private fun posFromVisibleItems(
    offset: Offset,
    lazyState: LazyListState,
    columnItems: List<ColumnItem>,
    textLayoutResults: Map<Int, TextLayoutResult>,
    hasGutters: Boolean,
    gutterWidthDp: Dp,
    density: Density,
    horizontalScrollPx: Int,
    contentTopPaddingPx: Float
): Int? {
    val contentStartPx = with(density) {
        (if (hasGutters) gutterWidthDp.toPx() else 0f) + 6.dp.toPx()
    }
    // Visible text-line items in layout order (skip block widgets / gutters).
    val textItems = lazyState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
        (columnItems.getOrNull(info.index) as? ColumnItem.TextLine)
            ?.let { item -> info to item }
    }
    if (textItems.isEmpty()) return null

    // LazyListItemInfo.offset starts at y=0, but the LazyColumn renders its
    // content shifted down by `contentTopPadding` (the content padding sits
    // above the first item). The pointer `offset.y` is in that padded space, so
    // the item tops must be shifted by the same amount or every click resolves
    // ~contentTopPadding px too low — biasing toward the next line down (#169).
    val tops = FloatArray(textItems.size) {
        textItems[it].first.offset.toFloat() + contentTopPaddingPx
    }
    val sizes = FloatArray(textItems.size) { textItems[it].first.size.toFloat() }
    val idx = chooseHitLineIndex(tops, sizes, offset.y) ?: return null
    val (info, item) = textItems[idx]

    val itemTop = info.offset.toFloat() + contentTopPaddingPx
    val layout = textLayoutResults[item.lineNumber.value]
        ?: return item.from.value // no layout yet, return line start
    val localY = (offset.y - itemTop).coerceIn(0f, info.size.toFloat())
    // Add the horizontal scroll offset: the click x is in viewport space, but
    // the text is shifted left by the scroll amount, so the true position
    // within the line is x - contentStart + scroll.
    val localX = (offset.x - contentStartPx + horizontalScrollPx)
        .coerceAtLeast(0f)
    val expandedOffset = layout.getOffsetForPosition(Offset(localX, localY))
    val charOffset = unmapTabOffset(expandedOffset, item.tabOffsetMap)
    // Clamp to the LAYOUT's char length, not the ColumnItem's [from, to) span.
    // `charOffset` was resolved against `layout`, so the layout's own text
    // length is its natural domain. The `columnItems` snapshot can lag the
    // TextLayoutResult by a frame after a content change: BasicText recomposes
    // and writes its new layout (via onTextLayout) before this snapshot catches
    // up, leaving item.to-item.from stale — e.g. 0 on a just-populated line
    // while the layout already holds the full text. Clamping a correctly
    // resolved offset to that stale span collapses every hit to item.from,
    // teleporting the caret to the line start (#171). The layout, being the
    // source `charOffset` came from, is the consistent bound. When the snapshot
    // is fresh the two lengths agree, so this only changes behaviour in the
    // stale window.
    val layoutCharLen = unmapTabOffset(layout.layoutInput.text.length, item.tabOffsetMap)
    return item.from.value + charOffset.coerceIn(0, layoutCharLen)
}
