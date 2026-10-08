package com.qcmian.clipper.core.ui.code

/*
 * 这一包**刻意是 public**，尽管它只服务本项目内部两个调用方：预览面板（`:shared`）与开发者
 * 工具（`:devTools`）。原因是 Kotlin 的 `internal` 是**按模块**可见的：代码框搬进 `:shared`
 * 之后，`:devTools` 就成了「另一个模块」，`internal` 会把整套 API 挡在外面。
 *
 * 换句话说：这一包的边界不是「模块」，而是「本项目的界面层」。库外没有消费者，不存在 API
 * 稳定性负担；写在这里是为了让后来人不必再猜一次「为什么它没标 internal」。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import com.monkopedia.kodemirror.state.Compartment
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.SelectionSpec
import com.monkopedia.kodemirror.state.TransactionSpec
import com.monkopedia.kodemirror.state.extensionListOf
import com.monkopedia.kodemirror.view.EditorSession
import com.monkopedia.kodemirror.view.EditorTheme
import com.monkopedia.kodemirror.view.LocalContentTextStyle
import com.monkopedia.kodemirror.view.drawSelection
import com.monkopedia.kodemirror.view.editable
import com.monkopedia.kodemirror.view.editorContentStyle
import com.monkopedia.kodemirror.view.editorTheme
import com.monkopedia.kodemirror.view.highlightActiveLine
import com.monkopedia.kodemirror.view.keymapOf
import com.monkopedia.kodemirror.view.lineNumbers as gutterLineNumbers
import com.monkopedia.kodemirror.view.lineWrapping
import com.monkopedia.kodemirror.view.onChange
import com.monkopedia.kodemirror.view.placeholder as kodemirrorPlaceholder
import com.monkopedia.kodemirror.view.rememberEditorSession
import com.monkopedia.kodemirror.view.select
import com.monkopedia.kodemirror.view.setDoc
import com.qcmian.clipper.core.ui.theme.hintColor

/**
 * 代码输入框——**业务层看到的唯一入口，也是唯一的实现**：跑在内联的 CodeMirror 6 上
 * （见 `:kodemirror` 模块）。
 *
 * 参数与 KodeMirror 的对应关系：
 *
 * | 参数 | 这一侧怎么落地 |
 * |---|---|
 * | [label] / [actions] / [showLabel] | 画一行标题（标题在左、动作在右） |
 * | [value] / [onValueChange] | 会话只建一次，外部换内容走 `setDoc`；变更回调只有**非回灌**的那次才出去 |
 * | [editable] | `editable` facet——**只关输入那一路**（文本输入、文件粘贴、当前行底纹） |
 * | [placeholder] | `placeholder { }` 占位组件，用与正文同一套度量 |
 * | [isError] | 正文整篇改用 `error` 色（错误说明照样交给同一个框显示） |
 * | [softWrap] | `lineWrapping`；关掉时由 KodeMirror 自己横向滚动 |
 * | [lineNumbers] / [folding] | `lineNumbers` 与 `foldGutter()` 两个扩展各自决定占不占那一列 |
 * | [scan] | 本项目的扫描器 → 装饰器（配色）＋ foldService（折叠区间），见 [KodemirrorScan] |
 * | [filePaste] | 同样拦在粘贴处理之前，否则系统只给「文件名」 |
 *
 * **滚动条在编辑器内部**：上游只画了横向那条，纵向没有——本仓库在内联源码里照横向的样子补了一条
 * （见 `:kodemirror` 的 README「本仓库补丁」），与横向同款：同粗细、同一条极淡的轨道、同滑块色、
 * 纯拖拽。因此**这一侧不挂任何外挂构件**，横竖两条都由 KodeMirror 自己画在正文之上（拖动它真的
 * 能滚，见 `KodemirrorCodeFieldTest`）。拖动时指针事件只写一个目标值，**每帧才往 `LazyListState`
 * 落一次位**（每次落位都要重测一屏，逐事件落位会一帧重测好几遍）——所以拖条的手感以滚轮为上限：
 * 纵向每帧要量新露出的行，那是 `LazyListState` 的本分，换谁驱动都省不掉。
 *
 * 另外，[editable] / [lineNumbers] / [folding] / [softWrap] 会随参数变化**重配扩展**，
 * 所以调用方即使动态改这些开关也能对上。
 *
 * @param label 标题行左侧的框名；[showLabel] 为假时不用。
 * @param value 真实文档。折叠与高亮只改显示，这个值始终是原样。
 * @param onValueChange 用户编辑之后的正文。
 * @param modifier 整块的修饰符（宽度 / 高度 / `weight` 由调用方给）。
 * @param editable 这一格能不能**改写**。为 `false` 时是一个只读结果框（各种结果框、预览面板）：
 *   键盘输入、粘贴、拖放文件都进不去，[onValueChange] 一次都不会响。
 *
 *   但它**不是纯展示件**：插入光标照画、点得动、拖得出选区、⌘C 复制得走。结果框里挑一段复制是
 *   常规期待，预览面板同理，所以两档的差别只有「能不能改」。
 *
 *   `editable` facet **只**关掉输入那一路（文本输入、文件粘贴、当前行底纹），手势与选区绘制不看它。
 * @param placeholder 空内容时的占位提示。
 * @param isError [value] 本身是一条失败说明（而不是排好版的内容）：正文整篇改用错误色。这样调用方
 *   可以像普通结果一样把错误交给同一个框，不必在框外另开一行提示。
 * @param softWrap 长行折到下一行，还是横向滚出去。默认**不折**：这是代码，一行一条记录，折行会把
 *   「一行」这个结构本身弄没，对照两份 JSON 时尤其误导。
 * @param actions 标题行右端那块地方，放只属于**这个框**的动作（输入框的「打开文件 / 清空」、结果框的
 *   「保存文件 / 复制」）。做成插槽而不是几个布尔开关：控件不该认识「打开文件」是什么，那是调用方
 *   与宿主之间的事（见 `DevToolFieldActions`）。
 * @param lineNumbers 显示左侧行号。关掉后不占那一列；与 [folding] 同时为假时装订线整列消失。
 * @param folding 允许折叠（折叠箭头与 `…` 占位符）。关掉后不折叠，高亮与括号配对照旧——给「本来就
 *   没有块可折」的输入（数学表达式、Base64 长串）用。
 * @param scan 扫描器：把正文拆成着色片段、可折叠区间与行起点。默认按 JSON 扫，XML 工具传 `::scanXml`，
 *   纯文本（数学表达式）传 `::scanPlain`。只影响显示层（配色与折叠），与「合不合法 / 排成什么样」
 *   无关——那是各工具自己的解析器。它必须在同一个调用点上**保持稳定**（只在建会话期取一次）。
 * @param showLabel 是否画自带的那行标题（左侧框名 + 右侧 [actions]）。为 `false` 时整条标题行——
 *   连同 [actions]——都不出现：调用方把框名放到别处（例如左侧一列统一的标签），并自行安排原本挂在
 *   标题行上的动作。
 * @param filePaste 「粘贴文件」：按下粘贴键时先问它。返回文件**内容**就地插入；返回 `null` 表示剪贴板
 *   里不是文件，按系统默认粘贴。拦截是必要的：系统对「复制的文件」只提供**文件名**这一种文本表示
 *   （实测见 `FinderCopyTest`），不拦的话文本框里永远只有文件名。为 `null` 时完全不插手。
 */
@Composable
fun DevToolCodeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    placeholder: String = "",
    isError: Boolean = false,
    softWrap: Boolean = false,
    actions: @Composable () -> Unit = {},
    lineNumbers: Boolean = true,
    folding: Boolean = true,
    scan: (String) -> CodeStructure = ::scanJson,
    showLabel: Boolean = true,
    filePaste: (() -> String?)? = null,
) {
    val scheme = MaterialTheme.colorScheme
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
                    // 占位文字比正文淡一档，但度量与正文一致（同字号、同行高），位置因此对得上。
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

    // 外部换了内容（换条目、点了格式化）才整块回灌，并把光标放到文末。
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
            CodeFieldHeader(label = label, actions = actions)
            Spacer(Modifier.height(6.dp))
        }
        // 编辑区**必须有有界高度**：KodeMirror 自己滚，拿到无界约束时它会按整篇文档的高度铺开。
        // 横竖两条滚动条都由 KodeMirror 自己画在正文之上（竖向那条是本仓库内联时补的，上游只画了
        // 横向——见 `:kodemirror` 的 README）。这一侧因此不必外挂任何东西。
        //
        // 正文与右键菜单都归 `CodeFieldEditor`：菜单只能挂在**这一块**上——它的 offset 以这一块
        // 为锚，点在哪就开在哪。
        CodeFieldEditor(
            session = session,
            editable = editable,
            filePaste = filePaste,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(codeColors.editorBackground)
                .border(1.dp, scheme.outline.copy(alpha = 0.6f), shape)
                .then(filePasteInterceptor(session, filePaste, editable)),
        )
    }
}

/**
 * 编辑框与「来源卡」共用的标题行：框名在左、动作在右，字号 13sp（与应用正文同一档）。
 *
 * 抽出来是因为同一个框有两种形态——可编辑的文本框，与替掉它的来源卡（文件 / 图片）——标题行
 * 必须**两种形态都在**、且一模一样。让每张卡片自己重画一遍的结果是：动作位置对不上、用词也
 * 会漂（「清除文件」/「清除来源」）。
 *
 * @param leading 框名与动作之间那块地方。输入区把「文本 / 文件」两页签摆在这儿，于是页签跟着
 *   标题行走、两种形态都在；不传时整行只有框名与动作。接收者是 [RowScope]：页签要以
 *   `Modifier.weight` 占住中段，右边那排动作才落在标题行末端。
 */
@Composable
fun CodeFieldHeader(
    label: String,
    actions: @Composable () -> Unit,
    leading: @Composable RowScope.() -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.hintColor)
        leading()
        Spacer(Modifier.weight(1f))
        actions()
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
    // 这一位就是 [DevToolCodeField] 的 `editable` 落到 KodeMirror 的形态：**只关输入那一路**——文本
    // 输入（输入法、键位里的插入兜底）、文件粘贴，以及下面那条当前行底纹。插入光标、点击落点与选区
    // 绘制**不看它**：只读框照样有光标、选得中、拷得走，只是改不动（见 `EditorSessionImpl.programmaticDocChange`）。
    // 这里必须写全限定名：参数就叫 `editable`，同名 facet 会被它遮住。
    parts += com.monkopedia.kodemirror.view.editable.of(editable)
    // 只读结果框不画当前行底纹：那里没有「正在编辑的行」，一条底色只会与左侧输入框争注意力。
    if (editable) parts += highlightActiveLine
    // 行号与折叠箭头各占一列：两个开关各自决定自己占不占宽，关掉就不占。
    if (lineNumbers) parts += gutterLineNumbers
    if (folding) parts += foldGutter()
    if (softWrap) parts += lineWrapping
    return extensionListOf(*parts.toTypedArray())
}

/**
 * 「粘贴文件」：拦在 KodeMirror 自己的粘贴处理**之前**。
 *
 * 拦截是必要的：系统对「复制的文件」只提供**文件名**这一种文本表示，
 * 不拦的话粘进来永远只有文件名。挂在外层 Box 上即可——预览事件从根往下走，装订线里那个负责收
 * 键盘的隐藏输入框还没轮到处理，先经过这里。
 *
 * 菜单里的「粘贴」走的是同一个钩子、同一段插入规则（见 [insertAtCursor]）。
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
        session.insertAtCursor(content)
        true
    }
}

/**
 * 「这份文档变更是我们自己灌进去的」这一事实。
 *
 * 普通字段而不是状态：它只在一次同步派发的前后被读写，不参与重组（见 [DevToolCodeField] 里
 * `setDoc` 前后那几行）。
 */
private class EditEcho {
    var suppress: Boolean = false
}
