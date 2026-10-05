package com.qcmian.clipper.core.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monkopedia.kodemirror.commands.defaultKeymap
import com.monkopedia.kodemirror.commands.history
import com.monkopedia.kodemirror.language.bracketMatching
import com.monkopedia.kodemirror.language.foldGutter
import com.monkopedia.kodemirror.state.ChangeSpec
import com.monkopedia.kodemirror.state.Compartment
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.SelectionSpec
import com.monkopedia.kodemirror.state.TransactionSpec
import com.monkopedia.kodemirror.state.asInsert
import com.monkopedia.kodemirror.state.extensionListOf
import com.monkopedia.kodemirror.view.EditorSession
import com.monkopedia.kodemirror.view.EditorTheme
import com.monkopedia.kodemirror.view.KodeMirror
import com.monkopedia.kodemirror.view.LocalContentTextStyle
import com.monkopedia.kodemirror.view.drawSelection
import com.monkopedia.kodemirror.view.editable
import com.monkopedia.kodemirror.view.editorContentStyle
import com.monkopedia.kodemirror.view.editorTheme
import com.monkopedia.kodemirror.view.highlightActiveLine
import com.monkopedia.kodemirror.view.keymapOf
import com.monkopedia.kodemirror.view.lineWrapping
import com.monkopedia.kodemirror.view.onChange
import com.monkopedia.kodemirror.view.rememberEditorSession
import com.monkopedia.kodemirror.view.select
import com.monkopedia.kodemirror.view.setDoc
import com.qcmian.clipper.core.ui.theme.hintColor
import com.monkopedia.kodemirror.view.lineNumbers as gutterLineNumbers
import com.monkopedia.kodemirror.view.placeholder as kodemirrorPlaceholder

/**
 * [DevToolCodeField] 的第二种实现：内联进来的 CodeMirror 6（见 `:kodemirror` 模块）。
 *
 * **参数面与原生实现逐一对齐**，因此面板里的引擎开关可以随时把同一个框换成这一套，调用方一行都
 * 不用改。对应关系：
 *
 * | 参数 | 这一侧怎么落地 |
 * |---|---|
 * | [label] / [actions] / [showLabel] | 与原生实现逐行相同地画一行标题（标题在左、动作在右） |
 * | [value] / [onValueChange] | 会话只建一次，外部换内容走 `setDoc`；变更回调只有**非回灌**的那次才出去 |
 * | [editable] | `editable` facet——**只关输入那一路**（文本输入、文件粘贴、当前行底纹） |
 * | [placeholder] | `placeholder { }` 占位组件，用与正文同一套度量 |
 * | [isError] | 正文整篇改用 `error` 色（与原生框一样，错误说明照样交给同一个框显示） |
 * | [softWrap] | `lineWrapping`；关掉时由 KodeMirror 自己横向滚动 |
 * | [lineNumbers] / [folding] | `lineNumbers` 与 `foldGutter()` 两个扩展各自决定占不占那一列 |
 * | [scan] | 本项目的扫描器 → 装饰器（配色）＋ foldService（折叠区间），见 [KodemirrorScan] |
 * | [filePaste] | 同样拦在粘贴处理之前，否则系统只给「文件名」 |
 *
 * **滚动条在编辑器内部**（这是与原生实现最大的一处差异，但两边看起来是同一套）：上游只画了横向
 * 那条，纵向没有——本仓库在内联源码里照横向的样子补了一条（见 `:kodemirror` 的 README「本仓库
 * 补丁」），与横向同款：同粗细、同一条极淡的轨道、同滑块色、纯拖拽。因此**这一侧不挂任何外挂
 * 构件**，横竖两条都由 KodeMirror 自己画在正文之上（拖动它真的能滚，见 `KodemirrorCodeFieldTest`）。
 * 拖动时指针事件只写一个目标值，**每帧才往 `LazyListState` 落一次位**（每次落位都要重测一屏，
 * 逐事件落位会一帧重测好几遍）——所以拖条的手感以滚轮为上限：纵向每帧要量新露出的行，那是
 * `LazyListState` 的本分，换谁驱动都省不掉；原生框的正文是普通 `ScrollState`（整篇只排版一次、
 * 滚动只做整体偏移），这是它与原生框唯一的手感差别。
 *
 * 唯一还能量出来的差别是**让出的宽度**：KodeMirror 内部让 10dp，原生那条让 15dp
 * （`VerticalScrollbarWidth + DevToolScrollbarGap`），于是折行位置差 5dp。
 *
 * **剩两处已知差别**（都是这两套实现天然不同的地方，不是 bug）：
 *
 *  1. **当前行底纹**：KodeMirror 的 `highlightActiveLine` 不看焦点，而原生框只在**聚焦时**画。
 *     只读结果框两边都不画；输入框这一侧，未聚焦时 KodeMirror 会显示一条底色。
 *  2. **装订线宽度**：KodeMirror 的装订线是「左边距 + 行号列 + 右边距」，没有原生框那个
 *     行号与折叠列之间的固定间距，宽度因此不是像素级相同。
 *
 * 另外，[editable] / [lineNumbers] / [folding] / [softWrap] 在这一侧会随参数变化**重配扩展**
 * （原生框是组合期直接生效），所以调用方即使动态改这些开关也能对上。
 */
@Composable
fun KodemirrorCodeField(spec: CodeFieldSpec) {
    // 与原生实现同一个写法：把契约摊成局部名，参数含义见 `CodeFieldSpec`。
    val label = spec.label
    val value = spec.value
    val onValueChange = spec.onValueChange
    val modifier = spec.modifier
    val editable = spec.editable
    val placeholder = spec.placeholder
    val isError = spec.isError
    val softWrap = spec.softWrap
    val actions = spec.actions
    val lineNumbers = spec.lineNumbers
    val folding = spec.folding
    val scan = spec.scan
    val showLabel = spec.showLabel
    val filePaste = spec.filePaste

    val scheme = MaterialTheme.colorScheme
    val hint = MaterialTheme.hintColor
    val codeColors = rememberCodeColors()
    val shape = RoundedCornerShape(6.dp)
    val theme = rememberKodemirrorTheme(codeColors)
    val contentStyle = remember(scheme, isError) { kodemirrorContentStyle(scheme, isError) }

    // 扫描器与 compartment 都**只建一次**，而且不能拿 `scan` 当 remember 的 key：调用点传的
    // `::scanJson` 这类函数引用每次组合都是新对象，用它当 key 会换掉 StateField 实例——而会话里
    // 注册的是旧那一个，折叠与高亮会**静默失效**。这个坑很安静，所以在这里写死。
    val scanner = remember { KodemirrorScan(scan) }
    val switchable = remember { Compartment() }

    // 「这次文档变更是我们自己灌进去的」。KodeMirror 的文档变更回调没有「谁改的」这一栏，而外部
    // 回灌（换剪贴板条目、点了格式化）也走同一条路。不区分的话，工具会收到自己刚写进去的那份文本
    // 再解析一遍——大文档上就是双倍开销。
    val echo = remember { EditEcho() }
    val latestOnValueChange = rememberUpdatedState(onValueChange)

    // 占位提示是**会变的**：调用方按模式 / 码制 / 输入格式换它（URL 的编解码方向、条码的码制、
    // 时间戳的输入格式）。而下面那个占位扩展只建一次、被会话长期持有——直接捕 `placeholder`
    // 会把它**冻在首次组合那一刻**（切了码制，框里还挂着上一句提示）。经 State 读，值变了
    // 那层 BasicText 才会重画。同 `latestOnValueChange` 的做法。
    val latestPlaceholder = rememberUpdatedState(placeholder)

    val session = rememberEditorSession(
        doc = value,
        extensions = remember {
            extensionListOf(
                // ── 建会话时定下来的那部分：历史（自带撤销 / 重做键位）、选区绘制、括号配对高亮、
                // 默认键位（含复制 / 剪切 / 粘贴），以及扫描器带来的折叠区间。
                history(),
                drawSelection,
                bracketMatching(),
                keymapOf(defaultKeymap),
                scanner.extensions,
                kodemirrorPlaceholder {
                    // 与原生框同字号、同色调：占位文字比正文淡一档，但度量一致，位置因此对得上。
                    // 文本经 [latestPlaceholder] 读，不直接捕参数——理由见它的说明。
                    BasicText(
                        text = latestPlaceholder.value,
                        style = LocalContentTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        ),
                    )
                },
                // ── 会变的那些（主题 / 正文样式 / 高亮配色 / 软折行 / 行号 / 折叠 / 只读）整块放进
                // 一个 compartment，下面的 effect 在它们变化时重配一次。
                switchable.of(
                    switchableExtensions(
                        scanner = scanner,
                        colors = codeColors,
                        theme = theme,
                        contentStyle = contentStyle,
                        editable = editable,
                        softWrap = softWrap,
                        lineNumbers = lineNumbers,
                        folding = folding,
                    )
                ),
                // 文档变更回调：回灌那一次被 `echo` 挡住，其余照常交给工具。
                onChange { text -> if (!echo.suppress) latestOnValueChange.value(text) },
            )
        },
    )

    // 外部换了内容（换条目、点了格式化）才整块回灌，并把光标放到文末——与原生框同一行为。
    // 首次组合时两者相等，因此这里不会派发事务（那时视图可能还没挂上会话）。
    LaunchedEffect(value) {
        if (session.state.doc.toString() == value) return@LaunchedEffect
        echo.suppress = true
        try {
            session.setDoc(value)
            session.select(DocPos(value.length))
        } finally {
            echo.suppress = false
        }
    }

    // 开关或主题变了就重配。用一个新对象当 key，免得依赖一长串参数的 equals。
    val switchKey = remember(softWrap, lineNumbers, folding, editable, theme, contentStyle, codeColors) {
        Any()
    }
    var appliedSwitch by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(switchKey) {
        // 首次组合不派发：会话刚刚才按同一份参数建好。
        if (appliedSwitch == null) {
            appliedSwitch = switchKey
            return@LaunchedEffect
        }
        appliedSwitch = switchKey
        session.dispatch(
            TransactionSpec(
                effects = listOf(
                    switchable.reconfigure(
                        switchableExtensions(
                            scanner = scanner,
                            colors = codeColors,
                            theme = theme,
                            contentStyle = contentStyle,
                            editable = editable,
                            softWrap = softWrap,
                            lineNumbers = lineNumbers,
                            folding = folding,
                        )
                    )
                )
            )
        )
    }

    Column(modifier.fillMaxWidth()) {
        if (showLabel) {
            // 与原生框同一版式：标签在左、动作在右，字号也对齐（13sp）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = hint)
                Spacer(Modifier.weight(1f))
                actions()
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(codeColors.editorBackground)
                .border(1.dp, scheme.outline.copy(alpha = 0.6f), shape)
                .then(filePasteInterceptor(session, filePaste, editable)),
        ) {
            // **必须有有界高度**：KodeMirror 自己滚，拿到无界约束时它会按整篇文档的高度铺开。
            // 横竖两条滚动条都由 KodeMirror 自己画在正文之上（竖向那条是本仓库内联时补的，
            // 上游只画了横向——见 `:kodemirror` 的 README）。这一侧因此不必外挂任何东西。
            KodeMirror(session = session, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 「随时可能变」的那几项扩展。
 *
 * 单独拎出来是因为 Base64 的结果框带一个**折行开关**（[softWrap] 是会变的），主题也会随浅 / 深色
 * 切换——它们不能像其余扩展那样「建会话时定下来就不再变」。
 */
private fun switchableExtensions(
    scanner: KodemirrorScan,
    colors: CodeColors,
    theme: EditorTheme,
    contentStyle: TextStyle,
    editable: Boolean,
    softWrap: Boolean,
    lineNumbers: Boolean,
    folding: Boolean,
): Extension {
    val parts = ArrayList<Extension>(8)
    parts += editorTheme.of(theme)
    parts += editorContentStyle.of(contentStyle)
    parts += codeHighlight(scanner, colors)
    // 这一位就是 [CodeFieldSpec.editable] 落到 KodeMirror 的形态：**只关输入那一路**——文本输入
    // （输入法、键位里的插入兜底）、文件粘贴，以及下面那条当前行底纹。插入光标、点击落点与选区
    // 绘制**不看它**：只读框照样有光标、选得中、拷得走，只是改不动（见 `CodeFieldSpec.editable`
    // 与 `EditorSessionImpl.programmaticDocChange`）。
    // 这里必须写全限定名：参数就叫 `editable`，同名 facet 会被它遮住。
    parts += com.monkopedia.kodemirror.view.editable.of(editable)
    // 只读结果框不画当前行底纹——原生框也是这么定的：那里没有「正在编辑的行」，一条底色只会与
    // 左侧输入框争注意力。
    if (editable) parts += highlightActiveLine
    // 行号与折叠箭头各占一列：关掉就不占，与原生框「两个开关各自决定自己占不占宽」一致。
    if (lineNumbers) parts += gutterLineNumbers
    if (folding) parts += foldGutter()
    if (softWrap) parts += lineWrapping
    return extensionListOf(*parts.toTypedArray())
}

/**
 * 「粘贴文件」：拦在 KodeMirror 自己的粘贴处理**之前**。
 *
 * 拦截是必要的，理由与原生框那边相同：系统对「复制的文件」只提供**文件名**这一种文本表示，
 * 不拦的话粘进来永远只有文件名。挂在外层 Box 上即可——预览事件从根往下走，装订线里那个负责收
 * 键盘的隐藏输入框还没轮到处理，先经过这里。
 */
private fun filePasteInterceptor(
    session: EditorSession,
    filePaste: (() -> String?)?,
    editable: Boolean,
): Modifier {
    if (filePaste == null || !editable) return Modifier
    return Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != Key.V) return@onPreviewKeyEvent false
        if (!event.isMetaPressed && !event.isCtrlPressed) return@onPreviewKeyEvent false
        val content = filePaste() ?: return@onPreviewKeyEvent false
        // 与原生框同一条插入规则：选区被替换掉，光标落在插入内容之后。
        val selection = session.state.selection.main
        session.dispatch(
            TransactionSpec(
                changes = ChangeSpec.Single(
                    from = selection.from,
                    to = selection.to,
                    insert = content.asInsert(),
                ),
                selection = SelectionSpec.CursorSpec(selection.from + content.length),
            )
        )
        true
    }
}

/**
 * 「这份文档变更是我们自己灌进去的」这一事实。
 *
 * 普通字段而不是状态：它只在一次同步派发的前后被读写，不参与重组（见 `KodemirrorCodeField` 里
 * `setDoc` 前后那几行）。
 */
private class EditEcho {
    var suppress: Boolean = false
}
