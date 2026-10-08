package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
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
 * 三条来路本身不改变「这是文件 / 图片」这件事，只在**读不出内容**时分出不同的交代：打开是用户
 * 挑错了文件、拖入是把路径留在框里、粘贴则要吞掉（否则系统会把文件名贴进来）。
 */
enum class DevToolInputOrigin {
    /** 按下粘贴键，从剪贴板来的。 */
    Paste,

    /** 从别处拖进输入区的。 */
    Drop,

    /** 通过「打开文件」对话框挑的。 */
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
 * 工具输入区——**文本 / 文件 / 图片三种输入的唯一入口**。
 *
 * 在这之前，各工具的输入区是「代码框 + 一层层外挂」拼出来的：一个 `rememberFilePaste` 管粘贴
 * 文件、一个 `devToolFileDrop` 管拖放、一个 `DevToolInputActions` 管打开与清空，谁也没把
 * 「这一格能接住什么」说全。
 * 于是每加一种输入都得把每个工具的调用点各改一遍，漏一处就是「这个工具能、那个工具不能」——
 * Base64 的图片粘贴就是这么漏掉的。
 *
 * 这一个控件把四路输入收成一份契约：
 *  - **文本**：手打、粘贴文本，照旧走 [DevToolCodeField]（行号、高亮、光标、选区都不变）；
 *  - **文件**：对话框打开、从访达拖入、粘贴板里复制的文件（见 [onFiles]）；
 *  - **图片**：从浏览器一类应用粘贴 / 拖入的图片（[onImage] 非空时才有这一路）；
 *  - **非文本来源**：文件 / 图片被工具接手之后，可以用 [sourceCard] 拿一张卡片替掉文本框
 *    （Base64 载入文件后的那张卡就是它），拖放与粘贴在卡片上照旧生效。
 *
 * **粘贴的主路在窗口层**（`DevToolPasteKey`）：卡片一上来文本框就没了，内容区里一个焦点节点都
 * 不剩，挂在这里的键盘拦截根本收不到 `⌘V`——「已经载入一张图，再粘个文件替换它」失效的就是
 * 这个。所以输入区把自己的处理函数登记给窗口，按键从那儿进来；下面那层 `onPreviewKeyEvent`
 * 只是没人在窗口层接时的退路。
 *
 * **控件不认识文件内容**：它只把路径交给 [onFiles]，读成文本还是读成字节由工具决定（格式化类
 * 工具读文本，Base64 编码要的是原始字节）。[onFiles] 返回的**文本**由控件安置：粘贴时插在光标
 * 处（因此走的是代码框自己的插入路径，选区与光标都对），打开 / 拖入时整篇替掉正文。
 *
 * 收图片这一路刻意是**可选**的（[onImage] 为 `null` 表示这个框不吃图片）：不填时控件连键盘都
 * 不碰，免得把「粘一段文字」这种别的工具该正常处理的事吞掉。
 *
 * @param label 标题行左侧的框名；[showLabel] 为假时不用。
 * @param value 正文。[sourceCard] 非空时不用。
 * @param onValueChange 正文变化：用户手打、粘贴文本，以及由控件替用户填进来的文件内容
 *   （那一路状态栏另有「来自文件」的判断，见 `DevToolsPanel.sourceLabel`）。
 * @param host 宿主能力：剪贴板与拖放载荷的解析、文件对话框都在它那里。
 * @param modifier 整块输入区的修饰符（宽度 / 高度 / `weight` 都由调用方给）。`sourceCard` 也拿
 *   这一份，因此卡片与文本框占的地方完全一致。
 * @param placeholder 空内容时的占位提示。
 * @param softWrap 长行折行还是横向滚出去。见 `CodeFieldSpec.softWrap`。
 * @param folding 允许折叠。见 `CodeFieldSpec.folding`。
 * @param scan 扫描器（高亮与折叠的区间）。见 `CodeFieldSpec.scan`。
 * @param showLabel 是否画自带的那行标题（框名 + 动作）。为假时整条标题行——连同 [showOpenAction]
 *   那两个动作与 [actions]——都不出现，框名与动作由调用方在外面安排（时间戳工具就是这么用的）。
 * @param showOpenAction 是否带「打开文件」那个动作。默认带上；手敲内容才是主用法的输入框
 *   （数学表达式）可以关掉，免得按钮与旁边的控件抢位置。
 * @param actions 只属于这个框的额外动作，排在控件自带的那两个之后。
 * @param onClear 用户点「清空」。默认就是把正文清空——但清空与「手打」在状态栏里不是一回事，
 *   所以留出这一路让工具自己定（见 Base64 工具对 `typed` 的处理）。
 * @param onFiles 文件落进输入区（打开 / 拖入 / 粘贴）。**返回值**：这次读出的文本——粘贴时插在
 *   光标处，打开 / 拖入时整篇替掉正文；空串表示「这次输入我已经安置好了」，控件不会再往框里填
 *   任何东西并**吞掉**这次粘贴（系统那条「粘成文件名」的路因此不会走）；`null` 与空串对打开 /
 *   拖入是一回事。默认实现把能当文本读的读进来，读不出的按 [DevToolInputOrigin] 分别交代。
 * @param onImage 剪贴板 / 拖放里的图片字节落到这里，由工具接手（Base64 把它当编码来源）。为
 *   `null` 表示这个输入框不收图片。
 * @param sourceCard 载入非文本来源（文件 / 图片）后替掉文本框的那张卡片；它拿到的是与文本框
 *   同一份 [modifier]（含拖放与粘贴的接线），为 `null` 时一直画文本框。卡片这一侧的 [onFiles]
 *   只负责**安置**内容——那里没有光标，没有「插在哪里」这回事。
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

    val dropModifier = Modifier.devToolInputDrop(
        host = host,
        onFiles = { paths -> accept(paths, DevToolInputOrigin.Drop) },
        onImage = onImage?.let { deliver -> { bytes -> deliver(bytes, DevToolInputOrigin.Drop) } },
    )

    val card = sourceCard

    /**
     * 这一次粘贴接不接——**窗口层与输入区自己那层键盘拦截共用同一份判断**（两处各写一遍早晚
     * 会分叉）。顺序与拖放一致：先文件后图片。
     *
     * **文件只接卡片那一侧，图片两侧都接**：
     *  - 文件要插在**光标处**，只有代码框自己的 `filePaste` 钩子做得到（见 [rememberFilePaste]），
     *    卡片上没有光标，插在哪儿没有依据；
     *  - 图片没有「插进文本」这回事，交给工具安置即可，所以文本框那一侧也由控件接（用户不必
     *    先点一下编辑区才粘得进图）。
     *
     * 文本一概放行：文本框那一侧有选区、有撤销栈，粘贴得由代码框自己来；卡片那一侧没有文本框，
     * 文本粘贴照旧留空（要把文本填进去，先清掉来源回到文本框）。
     */
    fun handlePaste(): Boolean {
        val paths = host.clipboardFilePaths()
        if (paths.isNotEmpty()) {
            // 文件：只接卡片那一侧（文本框那侧留给代码框的钩子）。
            if (card == null) return false
            accept(paths, DevToolInputOrigin.Paste)
            return true
        }

        val deliver = onImage ?: return false
        val bytes = host.clipboardImage() ?: return false
        deliver(bytes, DevToolInputOrigin.Paste)
        return true
    }

    // **主路**：窗口层接到的粘贴（见 `DevToolPasteKey`）。登记的是「读当前值」这一层间接——
    // 卡片与文本框来回切、当前工具换了，登记不能停在旧的那一份状态上。
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

    if (card != null) {
        // 卡片替掉文本框，拖放与粘贴跟着一起接过来：否则载入第一个文件之后，输入区再拖什么、
        // 粘什么都不会有反应（它已经不是挂着接收器的那个节点了）。
        Box(modifier.then(pasteModifier).then(dropModifier)) {
            card(Modifier.fillMaxSize())
        }
        return
    }

    DevToolCodeField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(pasteModifier).then(dropModifier),
        placeholder = placeholder,
        softWrap = softWrap,
        actions = {
            // 两个自带动作只在标题行里出现：`showLabel` 为假时整行不画，它们也跟着消失。
            if (showOpenAction) {
                DevToolFieldAction(
                    kind = ClipperIconKind.FOLDER,
                    tooltip = "打开文件",
                    onClick = {
                        host.pickFileToOpen()?.let { accept(listOf(it), DevToolInputOrigin.Open) }
                    },
                )
                Spacer(Modifier.width(4.dp))
            }
            DevToolFieldAction(
                kind = ClipperIconKind.TRASH,
                tooltip = "清空",
                enabled = value.isNotEmpty(),
                onClick = onClear,
            )
            actions()
        },
        folding = folding,
        scan = scan,
        showLabel = showLabel,
        filePaste = rememberFilePaste(host, onFiles),
    )
}

/**
 * 「粘贴文件」：按下粘贴键时先让 [onFiles] 把剪贴板里的文件安置掉。
 *
 * 返回值遵守 `CodeFieldSpec.filePaste` 的约定：剪贴板里不是文件时给 `null`，交回系统默认粘贴；
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
 * `target` 要跨重组保持同一个实例（`dragAndDropTarget` 靠它的身份维持拖放会话），因此两个回调
 * 都经 [rememberUpdatedState] 取当前值，而不是把某一次重组的那一份捕进 `remember`。
 *
 * 对工具可见（不是 `private`）：一页可以有几处接受拖入的落点——输入区是一处，工具自己在别处画的
 * 框（比如签名那一栏的密钥库路径）也是一处。粘贴**不能**这么分（见 `DevToolPasteKey`），拖放可以。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.devToolInputDrop(
    host: DevToolHost,
    onFiles: (List<String>) -> Unit,
    onImage: ((ByteArray) -> Unit)?,
): Modifier {
    val acceptFiles by rememberUpdatedState(onFiles)
    val acceptImage by rememberUpdatedState(onImage)
    val target = remember(host) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
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
