package com.qcmian.clipper.core.ui.code

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.monkopedia.kodemirror.state.ChangeSpec
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.SelectionSpec
import com.monkopedia.kodemirror.state.TransactionSpec
import com.monkopedia.kodemirror.state.asInsert
import com.monkopedia.kodemirror.view.EditorSession
import com.monkopedia.kodemirror.view.KodeMirror
import com.monkopedia.kodemirror.view.clipboardCopy
import com.monkopedia.kodemirror.view.clipboardCut
import com.monkopedia.kodemirror.view.clipboardPaste
import com.monkopedia.kodemirror.view.selectAll
import com.qcmian.clipper.core.ui.components.AppContextMenu
import com.qcmian.clipper.core.ui.components.AppContextMenuAction
import com.qcmian.clipper.core.ui.components.AppContextMenuEntry

/**
 * 编辑区：KodeMirror 本体 + 它**自己的右键菜单**（剪切 / 复制 / 粘贴 / 全选）。
 *
 * **为什么代码框要自己做一个**：单行输入框（`BasicTextField`）在桌面端自带一套（Compose 给
 * `BasicTextField` 挂了上下文菜单组件）；而代码框跑在 KodeMirror 上，正文是它自己画的，整棵树里
 * 没有一个原生输入框，右键落在上面只会把光标挪过去。于是「选一段、右键、复制」在结果框里做不到，
 * 而这恰恰是结果框最常见的用法（各类结果框都只读，键盘之外没有第二个出口）。
 *
 * 菜单交给 [AppContextMenu] 画——**与主面板里那份选中集菜单同一副长相**：平台的默认上下文菜单
 * （`ContextMenuArea` 那一套）配色与尺寸跟这套中性灰主题不是一家人，而自己再搭一套 Material 下拉
 * 又会与主面板不一致。位置就用指针落点（见 [AppContextMenu] 的 `at` / `anchor`）。
 *
 * 四行都直接落到 KodeMirror 自己的命令上（[clipboardCut] / [clipboardCopy] / [clipboardPaste] /
 * [selectAll]）：它们作用在**会话**上，所以菜单与 ⌘C / ⌘V 是同一套行为——复制出来的是选区，
 * 粘贴落在光标处并替换选区，不是另写一份。
 *
 * 三重限定，与只读结果框的约定一致（见 `DevToolCodeField.editable`）：
 *  - 剪切 / 粘贴**只在可编辑时**可点：只读框那一侧 `editable` facet 只关输入那一路，命令绕不过它
 *    （命令走的是程序化派发），得在这里自己挡住；
 *  - 复制 / 全选在只读框里照常可点——从结果框里挑一段拷走是常规期待；
 *  - 右键点在**选区之外**时先把光标落过去（桌面编辑器的规矩），点在选区**之内**时保留选区。
 *
 * @param session 这一块的会话。
 * @param editable 能不能改写。只读时剪切 / 粘贴两项变淡、点不动。
 * @param filePaste 与 `DevToolCodeField` 的 `filePaste` 同一个约定：菜单里的「粘贴」也先问它一声，
 *   否则「剪贴板里是文件」时会把系统给的那个**文件名**贴进来（那正是这个钩子存在的理由）。
 * @param modifier 整块的修饰符（外壳的底色、描边、按键拦截都由调用方挂在这一层）。
 */
@Composable
fun CodeFieldEditor(
    session: EditorSession,
    editable: Boolean,
    filePaste: (() -> String?)?,
    modifier: Modifier = Modifier,
) {
    // 菜单开在哪：指针位置（相对这一块）＋ 这一块在窗口里的原点——菜单要按窗口收边，得把两者
    // 一起换算过去（见 `AppContextMenu` 的 `at` 与 `anchor`）。
    var menuAt by remember(session) { mutableStateOf<Offset?>(null) }
    var windowOrigin by remember(session) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            // 原点**常驻**量着：等到菜单打开那一刻才量会晚一帧，第一帧会摆错位置再跳一下。
            .onGloballyPositioned { windowOrigin = it.positionInWindow() }
            .then(Modifier.secondaryPress(session) { menuAt = it })
    ) {
        KodeMirror(session = session, modifier = Modifier.fillMaxSize())

        // 只在开着的时候建这几行：里面要读会话状态（有没有选区、有没有内容），常挂着等于让编辑器
        // 每敲一个字都跟着重组一次。
        menuAt?.let { at ->
            AppContextMenu(
                at = at,
                anchor = windowOrigin,
                onDismiss = { menuAt = null },
                entries = codeFieldMenuEntries(
                    session = session,
                    editable = editable,
                    filePaste = filePaste,
                    onDone = { menuAt = null },
                ),
            )
        }
    }
}

/**
 * 菜单那四行。
 *
 * 「可不可点」按**打开这一刻**的会话状态算：没有选区就剪不了也拷不了，只读就剪不了也粘不进，
 * 一篇空文档没什么可全选。点完顺手把菜单收掉（[onDone]）——收不收由调用方说了算，是
 * [AppContextMenu] 的约定。
 *
 * 键位提示写死成 `⌘X` / `⌘C` / `⌘V` / `⌘A`：这几条由 KodeMirror 自己的键位表（`defaultKeymap`）
 * 决定，不随应用设置走，也不像剪贴板面板那些绑定那样能被用户改。
 */
private fun codeFieldMenuEntries(
    session: EditorSession,
    editable: Boolean,
    filePaste: (() -> String?)?,
    onDone: () -> Unit,
): List<AppContextMenuEntry> {
    val hasSelection = !session.state.selection.main.empty
    val hasContent = session.state.doc.length > 0

    fun item(title: String, shortcut: String, enabled: Boolean, action: () -> Unit) =
        AppContextMenuAction(title = title, shortcut = shortcut, enabled = enabled) {
            onDone()
            action()
        }

    return listOf(
        item("剪切", "⌘X", enabled = editable && hasSelection) { clipboardCut(session) },
        item("复制", "⌘C", enabled = hasSelection) { clipboardCopy(session) },
        item("粘贴", "⌘V", enabled = editable) { pasteIntoEditor(session, filePaste) },
        item("全选", "⌘A", enabled = hasContent) { session.selectAll() },
    )
}

/**
 * 右键按下：先把光标落到点处（或保住已有选区），再把菜单位置交出去。
 *
 * 这一次按下**必须吃掉**（`change.consume()`）：编辑器那颗 `awaitEachGesture` 不看按键，
 * 右键一样会被当成「落光标」处理——刚选好一段、右键想复制，选区先被收成一个光标。
 * 消费发生在 `Initial` 那一趟（父节点先看），所以编辑器那颗 `awaitFirstDown` 根本不会收到它。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.secondaryPress(
    session: EditorSession,
    onOpen: (Offset) -> Unit,
): Modifier = pointerInput(session) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press) continue
            if (!event.buttons.isSecondaryPressed) continue
            val change = event.changes.firstOrNull() ?: continue
            change.consume()
            session.moveCaretForContextMenu(change.position)
            onOpen(change.position)
        }
    }
}

/** 右键落在**选区之外**时把光标挪过去；落在选区之内时一动不动（那是「选好了再右键」）。 */
private fun EditorSession.moveCaretForContextMenu(position: Offset) {
    val pos = posAtCoords(position.x, position.y) ?: return
    val selection = state.selection.main
    if (!selection.empty && pos >= selection.from.value && pos <= selection.to.value) return
    dispatch(TransactionSpec(selection = SelectionSpec.CursorSpec(DocPos(pos))))
}

/**
 * 菜单里的「粘贴」：与 ⌘V 同一条路——先问 [filePaste]，它不管才走 KodeMirror 自己的文本粘贴。
 *
 * 空串是 [filePaste] 的「这次输入我已经安置好了」（工具自己吞掉了那个文件），此时什么都不做。
 */
private fun pasteIntoEditor(session: EditorSession, filePaste: (() -> String?)?) {
    when (val fromFile = filePaste?.invoke()) {
        null -> clipboardPaste(session)
        "" -> Unit
        else -> session.insertAtCursor(fromFile)
    }
}

/**
 * 把 [text] 插在光标处：选区被替换掉，光标落在插入内容之后。
 *
 * 「粘贴文件」那条路（`DevToolCodeField` 的 `filePaste`）与菜单里的粘贴都走这里：插入规则只写
 * 一次，两处才不会一个把选区留下、一个替换掉。
 */
internal fun EditorSession.insertAtCursor(text: String) {
    val selection = state.selection.main
    dispatch(
        TransactionSpec(
            changes = ChangeSpec.Single(
                from = selection.from,
                to = selection.to,
                insert = text.asInsert(),
            ),
            selection = SelectionSpec.CursorSpec(selection.from + text.length),
        )
    )
}
