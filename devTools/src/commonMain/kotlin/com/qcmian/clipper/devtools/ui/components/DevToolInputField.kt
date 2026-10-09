package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.ui.code.CodeFieldHeader
import com.qcmian.clipper.core.ui.code.CodeStructure
import com.qcmian.clipper.core.ui.code.DevToolCodeField
import com.qcmian.clipper.core.ui.code.scanPlain
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.LocalDevToolPasteKey
import com.qcmian.clipper.devtools.api.isPasteShortcut
import com.qcmian.clipper.devtools.api.readTextFileOrNull

/**
 * 一次**非文本**输入落到输入区时是从哪来的。
 *
 * 几条来路本身不改变「这是文件 / 图片」这件事，只在**读不出内容**时分出不同的交代：打开（含在
 * 文件页那行路径里敲完回车）是用户挑错了文件、拖入是把路径留在框里、粘贴则要吞掉（否则系统会把
 * 文件名贴进来）。
 */
enum class DevToolInputOrigin {
    /** 按下粘贴键，从剪贴板来的。 */
    Paste,

    /** 从别处拖进输入区的。 */
    Drop,

    /** 用户点名指了一个文件：文件对话框挑的，或在文件页那行路径里敲完按回车。 */
    Open,
}

/**
 * 图片没有文件名，卡片与状态栏总得有个称呼——按来路分开叫。
 *
 * 三条来路各是一个处境：粘贴进来的本来就在剪贴板上，拖进来的是一个没说名字的载荷，「打开」挑的
 * 则是磁盘上那张图。都叫「剪贴板图片」会让人以为拖错了地方。Base64 与条码两个工具都从这一个口子
 * 取名字，同一个来源因此在两处叫同一个词。
 */
internal fun imageInputName(origin: DevToolInputOrigin): String = when (origin) {
    DevToolInputOrigin.Paste -> "剪贴板图片"
    DevToolInputOrigin.Drop -> "拖入的图片"
    DevToolInputOrigin.Open -> "图片"
}

/**
 * 输入区的**两页**：这一位装的是手打的文本，还是一个文件 / 图片。
 *
 * 两页**彻底分开**，各自只收自己那一种：文本页只有代码框（文件、图片都不接，会提示往文件页走），
 * 文件页只有一条可敲的绝对路径与那张来源卡片。工具要的是字节时一段文本没法编辑，两者本就互斥，
 * 摆成两页只是把这件事说出来。
 */
enum class DevToolInputMode(val label: String, val tooltip: String) {
    Text("文本", "手打，或粘贴一段文本"),
    File("文件", "打开、拖入、粘贴一个文件 / 图片，或直接敲一条绝对路径"),
}

/**
 * 工具输入区——**文本 / 文件 / 图片几种输入的唯一入口**。
 *
 * 在这之前，各工具的输入区是「代码框 + 一层层外挂」拼出来的：一个 `rememberFilePaste` 管粘贴
 * 文件、一个 `devToolFileDrop` 管拖放、一个 `DevToolInputActions` 管打开与清空，谁也没把
 * 「这一格能接住什么」说全。
 * 于是每加一种输入都得把每个工具的调用点各改一遍，漏一处就是「这个工具能、那个工具不能」——
 * Base64 的图片粘贴就是这么漏掉的。
 *
 * 这一个控件把几路输入收成一份契约，并摆成**两页**：
 *  - **文本页**：手打、粘贴文本，照旧走 [DevToolCodeField]（行号、高亮、光标、选区都不变）；
 *  - **文件页**：一条**可敲的绝对路径** + 一张来源卡片（[sourceCard]）。文件与图片都只从这一页
 *    进来——对话框打开、从访达拖入、粘贴板里复制的文件（见 [onFiles]），以及从浏览器一类应用
 *    粘贴 / 拖入的图片（[onImage] 非空时才有这一路）。路径那行也可以直接敲或粘一条绝对路径，
 *    按回车交给工具（与「打开文件」同一条路，见 [sourcePath]）。
 *
 * **两页彻底分开**，各自只收自己那一种：文本页上不摆「打开文件」，也不摆能接文件的落点——摆着
 * 却接不了等于骗人去拖；文件页里没有代码框，能敲的只有那条路径。文本页收到文件 / 图片时控件**接住
 * 并说一句**「往文件页放」：放行会让系统把文件名贴进正文，静默吞掉又像是没反应，两条都不行。
 *
 * **页签换的是一整页**（输入框是什么、能接什么、下面画什么），所以用 [DevToolTabBar] 画成页签，
 * 而不是工具栏里那种分段控件。有来源（[hasSource]）时自动落在文件页——来源可能是刚拖进来的，
 * 也可能是打开面板时灌进来的，后者控件自己看不见，只能由工具说。
 *
 * **粘贴的主路在窗口层**（`DevToolPasteKey`）：文件页里没有文本框，内容区里一个焦点节点都不剩，
 * 挂在这里的键盘拦截根本收不到 `⌘V`——「已经载入一张图，再粘个文件替换它」失效的就是这个。所以
 * 输入区把自己的处理函数登记给窗口，按键从那儿进来；下面那层 `onPreviewKeyEvent` 只是没人在窗口
 * 层接时的退路。
 *
 * **控件不认识文件内容**：它只把路径交给 [onFiles]，读成文本还是读成字节由工具决定（格式化类
 * 工具读文本，Base64 编码要的是原始字节）。[onFiles] 返回的**文本**由控件安置：没有文件页的那
 * 一位，粘贴时插在**光标处**（因此走的是代码框自己的插入路径，选区与光标都对），打开 / 拖入时
 * 整篇替掉正文。
 *
 * 收图片这一路刻意是**可选**的（[onImage] 为 `null` 表示这个框不吃图片）：不填时控件连键盘都
 * 不碰，免得把「粘一段文字」这种别的工具该正常处理的事吞掉。
 *
 * @param label 标题行左侧的框名；[showLabel] 为假时不用。
 * @param value **文本页**的正文。
 * @param onValueChange 正文变化：用户手打、粘贴文本，以及由控件替用户填进来的文件内容。
 * @param host 宿主能力：剪贴板与拖放载荷的解析、文件对话框都在它那里。
 * @param modifier 整块输入区的修饰符（宽度 / 高度 / `weight` 都由调用方给）。两页共用这一份，
 *   因此翻页时占的地方纹丝不动。
 * @param placeholder 文本页空内容时的占位提示。
 * @param softWrap 长行折行还是横向滚出去。见 `DevToolCodeField` 的 `softWrap`。
 * @param folding 允许折叠。见 `DevToolCodeField` 的 `folding`。
 * @param scan 扫描器（高亮与折叠的区间）。见 `DevToolCodeField` 的 `scan`。
 * @param showLabel 是否画自带的那行标题（框名 + 页签 + 动作）。为假时整条标题行——连同两个自带
 *   动作、[actions] 与页签——都不出现，框名与动作由调用方在外面安排（时间戳工具就是这么用的）。
 * @param showOpenAction 文件页是否带「打开文件」那个动作。默认带上；手敲内容才是主用法的输入框
 *   （数学表达式）可以关掉，免得按钮与旁边的控件抢位置。
 * @param actions 只属于这个框的额外动作，排在控件自带的那两个之后。
 * @param onClear **文本页**里点「清空」。默认就是把正文清空；工具若还要顺手做点别的可以覆盖这一路。
 * @param onClearSource **文件页**里点「清除」：只清来源，别顺手把文本也抹了——那是翻回文本页要
 *   看的东西（两者分开，正是因为两页各留各的）。
 * @param hasSource 现在有没有来源。它同时管两件事：**有来源就落在文件页**（来源可能是刚拖进来
 *   的，也可能是打开面板时灌进来的——后者控件自己看不见，只能由工具说），以及「清除」可不可点。
 * @param allowText 这一位吃不吃手打的文本。为 `false` 时只有文件页（条码解码页：那页没有可敲的
 *   正文，摆一个文本框只会让人以为能粘一段字进去），于是也不显示页签。
 * @param sourcePath 文件页那行路径里显示的**绝对路径**（当前来源的）。来源在剪贴板上、没有磁盘
 *   路径时给空串，那一行就只是空的输入框。工具持值：控件不自己记，敲的字经回车交给 [onFiles]。
 * @param pathPlaceholder 路径那行空着时的占位提示。
 * @param onFiles 文件落进输入区（打开 / 拖入 / 粘贴 / 在路径行里敲完回车）。**返回值**：这次读出
 *   的文本——没有文件页的那一位，粘贴时插在光标处，打开 / 拖入时整篇替掉正文；空串表示「这次
 *   输入我已经安置好了」，控件不会再往框里填任何东西并**吞掉**这次粘贴（系统那条「粘成文件名」
 *   的路因此不会走）；`null` 与空串对打开 / 拖入是一回事。默认实现把能当文本读的读进来，读不出
 *   的按 [DevToolInputOrigin] 分别交代。
 * @param onImage 剪贴板 / 拖放里的图片字节落到这里，由工具接手（Base64 把它当编码来源）。为
 *   `null` 表示这个输入框不收图片。
 * @param sourceCard 文件页的内容（**含空态**），为 `null` 表示这一位不吃文件（于是不显示页签）。
 *   它拿到的是卡片那块地方（标题行与路径行在外，归控件画），因此只管把内容画出来。文件落进来
 *   怎么安置同样走 [onFiles]（「换一个文件」也是它）。
 */
@Composable
fun DevToolInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    host: DevToolHost,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    softWrap: Boolean = false,
    folding: Boolean = true,
    scan: (String) -> CodeStructure = ::scanPlain,
    showLabel: Boolean = true,
    showOpenAction: Boolean = true,
    actions: @Composable () -> Unit = {},
    onClear: () -> Unit = { onValueChange("") },
    onClearSource: () -> Unit = {},
    hasSource: Boolean = false,
    allowText: Boolean = true,
    sourcePath: String = "",
    pathPlaceholder: String = "粘贴或输入绝对路径",
    onFiles: (paths: List<String>, origin: DevToolInputOrigin) -> String? =
        { paths, origin -> readFilesAsText(host, paths, origin) },
    onImage: ((bytes: ByteArray, origin: DevToolInputOrigin) -> Unit)? = null,
    sourceCard: (@Composable (Modifier) -> Unit)? = null,
) {
    // 文件落进来之后怎么安置。空串与 null 都表示「控件不必再动正文」：工具要么自己接管了
    // （Base64 编码要走字节），要么已经给过提示。
    fun accept(paths: List<String>, origin: DevToolInputOrigin) {
        val text = onFiles(paths, origin)
        if (!text.isNullOrEmpty()) onValueChange(text)
    }

    val card = sourceCard
    // 吃不吃文件：给了卡片就是吃（不吃文件的那一位不传它）。
    val eatsFiles = card != null

    /**
     * 当前在哪一页。
     *
     * **有来源就是文件页**——来源可能是刚拖进来的、也可能是打开面板时灌进来的（那一路控件自己
     * 看不见），拿它当判据两种都盖得住。没有来源时才是用户自己点的那一页（只有文件页的工具除外，
     * 见 [allowText]）。
     */
    var picked by remember { mutableStateOf(DevToolInputMode.Text) }
    val mode = when {
        hasSource -> DevToolInputMode.File
        allowText -> picked
        else -> DevToolInputMode.File
    }
    val onFilePage = mode == DevToolInputMode.File
    // 两页都吃时才摆页签：只吃文本的（格式化类工具）与只吃文件的（条码解码页）都没得选。
    val showTabs = allowText && eatsFiles

    /**
     * 文件页那行路径里**正在敲的字**。
     *
     * 初始就是当前来源的绝对路径，来源一变（换了文件、清掉了来源）跟着复位——框里显示的因此始终
     * 是「工具现在用的是哪条路径」，中间那点手打只是草稿。回车才算数：边敲边读盘会让「读不了这个
     * 文件」在打字途中反复弹。
     */
    var pathDraft by remember { mutableStateOf(sourcePath) }
    LaunchedEffect(sourcePath) { pathDraft = sourcePath }

    // 在文本页上收到文件 / 图片：说清该往哪一页放，并**吞掉**这次粘贴——放行的话系统对「复制的
    // 文件」只给文件名这一种文本表示，正文里因此会冒出一个文件名；而静默吞掉又像是没反应。
    fun redirectToFilePage(what: String): Boolean {
        host.showStatus("这里现在是「文本」页：粘$what 请切到「文件」页")
        return true
    }

    // 拖入时整块描一圈主色：一页可能有几个落点（比如签名页的 APK 与 KeyStore 各一格），
    // 不给提示就不知道松手会落到哪一个。
    var dragHover by remember { mutableStateOf(false) }
    // 落点只摆在**吃文件的那一页**：文本页是纯文本，摆一个能接文件的落点，等于对刚点了「文本」
    // 的人说这里能接文件。不吃文件的那一位没有页签可选，拖入是它取文件的唯口子，照旧一直挂着。
    val dropLive = !eatsFiles || onFilePage
    val dropModifier = if (dropLive) {
        Modifier.devToolInputDrop(
            host = host,
            onFiles = { paths -> accept(paths, DevToolInputOrigin.Drop) },
            onImage = onImage?.let { deliver ->
                { bytes: ByteArray -> deliver(bytes, DevToolInputOrigin.Drop) }
            },
            onHoverChange = { dragHover = it },
        )
    } else {
        Modifier
    }

    /**
     * 这一次粘贴接不接——**窗口层与输入区自己那层键盘拦截共用同一份判断**（两处各写一遍早晚
     * 会分叉）。顺序与拖放一致：先文件后图片。
     *
     * 文件与图片都只在**文件页**上接；文本页收到它们时接住并说一句往哪一页走（见
     * [redirectToFilePage]）。不吃文件的那一位没有页签，文件照旧放行：让事件走到代码框自己的
     * `filePaste` 钩子上——那里能插在**光标处**。
     *
     * 文本一概放行：有文本框时那里有选区、有撤销栈，粘贴得由代码框自己来。
     */
    fun handlePaste(): Boolean {
        val paths = host.clipboardFilePaths()
        if (paths.isNotEmpty()) {
            if (!eatsFiles) return false
            if (!onFilePage) return redirectToFilePage("文件")
            accept(paths, DevToolInputOrigin.Paste)
            return true
        }

        val deliver = onImage ?: return false
        // **先取图片、再判页**：剪贴板里没有图片时直接放行，让普通文本粘贴照走代码框。反过来
        // （先按「这一页不接」把这次粘贴拦下）会把一段文本也当成图片拦掉——Base64 / Hash / Hex
        // 的文本页因此粘不进任何东西。
        val bytes = host.clipboardImage() ?: return false
        if (eatsFiles && !onFilePage) return redirectToFilePage("图片")
        deliver(bytes, DevToolInputOrigin.Paste)
        return true
    }

    // **主路**：窗口层接到的粘贴（见 `DevToolPasteKey`）。登记的是「读当前值」这一层间接——
    // 翻页、当前工具换了，登记不能停在旧的那一份状态上。
    val pasteKey = LocalDevToolPasteKey.current
    val handleLatest by rememberUpdatedState { handlePaste() }
    DisposableEffect(pasteKey) {
        val unregister = pasteKey?.register { handleLatest() }
        onDispose { unregister?.invoke() }
    }

    // **退路**：宿主没提供窗口入口时（离屏渲染、别处的窗口宿主）自己接。这条要求输入区里有
    // 焦点节点——谁点过谁才有，所以它只够兜底，不能当主路。返回 `false` 表示放行，事件继续往下
    // 走到代码框自己的钩子或系统默认粘贴上去。
    val pasteModifier = Modifier.onPreviewKeyEvent { event ->
        if (!event.isPasteShortcut()) false else handlePaste()
    }

    /**
     * 标题行右端那两个自带动作，**两页共用这一份**（从前它们随文本框一起消失，逼着每张卡片
     * 自己重画一遍）。两个动作都跟着**当前那一页**走：
     *  - 「打开」只在文件页：文本页收的就是文本，摆一个「打开文件」等于说这一页也吃文件；
     *  - 「清除」只清当前那一页的内容——文本与文件各留各的，正是翻页能来回的前提。
     */
    val fieldActions: @Composable () -> Unit = {
        if (showOpenAction && onFilePage) {
            DevToolFieldAction(
                kind = ClipperIconKind.FOLDER,
                tooltip = if (hasSource) "换一个文件" else "打开文件",
                onClick = {
                    host.pickFileToOpen()?.let { path ->
                        accept(listOf(path), DevToolInputOrigin.Open)
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
        }
        if (onFilePage) {
            DevToolFieldAction(
                kind = ClipperIconKind.TRASH,
                tooltip = "清除来源",
                enabled = hasSource,
                onClick = onClearSource,
            )
        } else {
            DevToolFieldAction(
                kind = ClipperIconKind.TRASH,
                tooltip = "清空",
                enabled = value.isNotEmpty(),
                onClick = onClear,
            )
        }
        actions()
    }

    /** 标题行中段那两页签：两种都吃时才出现。 */
    val pages: @Composable RowScope.() -> Unit = {
        if (showTabs) {
            Spacer(Modifier.width(10.dp))
            DevToolTabBar(
                options = DevToolInputMode.entries,
                selected = mode,
                optionLabel = { it.label },
                onSelect = { target ->
                    // 切到「文本」就是放弃文件来源——「这一页现在装文本」，与那个垃圾桶是同一件事。
                    // 反方向不清文本：字是你敲的，不该因为看了一眼文件就没了（这正是翻页的意义）。
                    if (target == DevToolInputMode.Text) {
                        if (hasSource) onClearSource()
                        picked = DevToolInputMode.Text
                    } else {
                        picked = DevToolInputMode.File
                    }
                },
                modifier = Modifier.weight(1f),
                // 基线由标题行整行画（见下面那段）：页签那条线因此横贯到右端那排动作底下，
                // 而不是只到页签为止。
                baseline = false,
            )
        }
    }

    // 「粘贴文件」那条钩子挂在代码框上：它能把内容插在**光标处**。只有**没有文件页**的那一位
    // 才用它——有文件页时，文本页是纯文本（文件由输入区自己接住并说一句），文件页里没有代码框。
    val filePasteHook: (() -> String?)? =
        if (eatsFiles) null else rememberFilePaste(host, onFiles)

    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)

    // 拖放与粘贴挂在这一整块上：文件页里没有文本框，挂在里面那个节点上会收不到（见上面那段）。
    Box(modifier.then(pasteModifier).then(dropModifier)) {
        Column(Modifier.fillMaxSize()) {
            // 标题行画在这里，两页共用：卡片只画它下面那一块内容。
            if (showLabel) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // 页签底下的基线铺满整行：右端那排动作也落在同一条线上，读起来是
                        // 「这一页是从这条线上翻开的」。
                        .then(
                            if (showTabs) {
                                Modifier.drawBehind {
                                    val y = size.height - 0.5.dp.toPx()
                                    drawLine(
                                        color = colors.outline.copy(alpha = 0.5f),
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = 1.dp.toPx(),
                                    )
                                }
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    CodeFieldHeader(label = label, leading = pages, actions = fieldActions)
                }
                Spacer(Modifier.height(8.dp))
            }
            if (onFilePage) {
                // 文件页 = 一条路径 + 一张卡片。路径那行**能敲**：手上已经有一条路径时，
                // 不必先去访达里把那个文件找出来。回车才算数（见 `pathDraft`）。
                DevToolSingleLineField(
                    value = pathDraft,
                    onValueChange = { pathDraft = it },
                    placeholder = pathPlaceholder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key != Key.Enter && event.key != Key.NumPadEnter) {
                                return@onPreviewKeyEvent false
                            }
                            val path = pathDraft.trim()
                            if (path.isNotEmpty()) accept(listOf(path), DevToolInputOrigin.Open)
                            true
                        },
                )
                Spacer(Modifier.height(8.dp))
                // 卡片只画内容那一块：标题行、页签、路径行、打开 / 清除、额外动作都归控件。
                card?.invoke(Modifier.fillMaxWidth().weight(1f))
            } else {
                DevToolCodeField(
                    label = label,
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    placeholder = placeholder,
                    softWrap = softWrap,
                    // 标题行上面已经画过（两页共用一份），这里只画正文那一块。
                    showLabel = false,
                    actions = {},
                    folding = folding,
                    scan = scan,
                    filePaste = filePasteHook,
                )
            }
        }
        if (dragHover) {
            Box(Modifier.matchParentSize().clip(shape).border(1.dp, colors.primary, shape))
        }
    }
}

/**
 * 「粘贴文件」：按下粘贴键时先让 [onFiles] 把剪贴板里的文件安置掉。
 *
 * 返回值遵守 `DevToolCodeField` 的 `filePaste` 约定：剪贴板里不是文件时给 `null`，交回系统默认粘贴；
 * 是文件就一定给一段字符串（空串也算），**吞掉**这次粘贴。拦住这一道是必要的：系统对「复制的
 * 文件」只提供**文件名**这一种文本表示（实测见 `FinderCopyTest`），不拦的话文本框里永远只有
 * 文件名，而不是文件内容。
 *
 * 用 [rememberUpdatedState] 而不是直接把 [onFiles] 捕进 `remember`：那个 lambda 每次重组都是新
 * 实例，捕旧了会让粘贴按上一帧的状态去安置文件。
 */
@Composable
private fun rememberFilePaste(
    host: DevToolHost,
    onFiles: (paths: List<String>, origin: DevToolInputOrigin) -> String?,
): () -> String? {
    val accept by rememberUpdatedState(onFiles)
    return remember(host) {
        {
            val paths = host.clipboardFilePaths()
            if (paths.isEmpty()) null
            else accept(paths, DevToolInputOrigin.Paste).orEmpty()
        }
    }
}

/**
 * 让这一片区域接受拖入的**文件与图片**：先认文件（落下的是一张图片文件时也走这一路，读到的
 * 是那个文件本身，而不是它的图标），其次认图片。
 *
 * 两样都不是时返回 `false`，让事件继续冒泡：拖一段选中的文字进来，应当由系统按「往文本框里拖
 * 文字」处理，而不是被这里吞掉。
 *
 * `target` 要跨重组保持同一个实例（`dragAndDropTarget` 靠它的身份维持拖放会话），因此几个回调
 * 都经 [rememberUpdatedState] 取当前值，而不是把某一次重组的那一份捕进 `remember`。
 *
 * 对工具可见（不是 `private`）：一页可以有几处接受拖入的落点——输入区（`DevToolInputField`）是
 * 一处，文件行（`DevToolFileField`）也是一处。[onHoverChange] 让每一处各自描出「松手会落到我
 * 这儿」，一页有几个落点时才不会猜。粘贴**不能**这么分（见 `DevToolPasteKey`），拖放可以。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.devToolInputDrop(
    host: DevToolHost,
    onFiles: (List<String>) -> Unit,
    onImage: ((ByteArray) -> Unit)?,
    /** 指针拖入 / 离开这一块时回调，用来画「松手会落到这里」的高亮；为 `null` 时不提示。 */
    onHoverChange: ((Boolean) -> Unit)? = null,
): Modifier {
    val acceptFiles by rememberUpdatedState(onFiles)
    val acceptImage by rememberUpdatedState(onImage)
    val hover by rememberUpdatedState(onHoverChange)
    val target = remember(host) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                hover?.invoke(true)
            }

            override fun onExited(event: DragAndDropEvent) {
                hover?.invoke(false)
            }

            // 松手、或拖拽被取消，都要复位；`onDrop` 之后紧跟的 `onEnded` 只是重复一次，无害。
            override fun onEnded(event: DragAndDropEvent) {
                hover?.invoke(false)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover?.invoke(false)
                val paths = host.droppedFilePaths(event)
                if (paths.isNotEmpty()) {
                    acceptFiles(paths)
                    return true
                }
                val image = acceptImage ?: return false
                val bytes = host.droppedImage(event) ?: return false
                image(bytes)
                return true
            }
        }
    }
    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { true },
        target = target,
    )
}

/**
 * 默认的「文件 → 文本」：能当文本读的读进来，读不出的按来路分别交代。
 *
 * 三条来路对「读不出」的期待并不一样，这也是 [DevToolInputOrigin] 存在的理由：
 *  - 打开：用户挑错了文件，说一句「读不了这个文件」就够，正文不动；
 *  - 拖入：把**路径**留在框里——拖进来的多半就是个文件，路径本身也是一条线索；
 *  - 粘贴：说一句提示并吞掉这次粘贴，否则系统会把文件名贴进来。
 */
private fun readFilesAsText(
    host: DevToolHost,
    paths: List<String>,
    origin: DevToolInputOrigin,
): String? {
    // 拖入：读得出的给内容，读不出的退回**路径**——拖进来的多半就是个文件，路径本身也是线索。
    if (origin == DevToolInputOrigin.Drop) {
        return paths.joinToString("\n") { readTextFileOrNull(it) ?: it }
    }

    val texts = paths.mapNotNull(::readTextFileOrNull)
    if (texts.isNotEmpty()) return texts.joinToString("\n")

    // 剩下两条来路都读不出内容：打开是用户挑错了文件；粘贴则必须**吞掉**（否则系统会把文件名
    // 贴进来——不拦这一道，「粘贴文件」这个钩子就没有存在的理由了）。
    host.showStatus(
        if (origin == DevToolInputOrigin.Paste) {
            "剪贴板里的文件读不出文本，用「打开文件」或直接拖进来"
        } else {
            "读不了这个文件：${paths.first()}"
        }
    )
    return ""
}
