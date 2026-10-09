package com.qcmian.clipper.devtools.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.draganddrop.DragAndDropEvent
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.icons.ClipperIconKind

/**
 * 内置的数据类型名。
 *
 * 类型名是工具与探测结果之间唯一的约定：工具在 [DevTool.acceptedDataTypes] 里声明自己能吃哪些，
 * 探测器在 `DataTypeDetector` 里声称某段文本属于哪个——两边只在这一组常量上对得上。
 */
object DataTypes {
    /** 兜底类型：任何非空文本都算。 */
    const val TEXT = "text"
    const val JSON = "json"
    const val XML = "xml"
    const val URL = "url"

    /** Unix 时间戳：一串纯数字（秒或毫秒）。时间戳转换工具与探测器在这一组常量上对齐。 */
    const val TIMESTAMP = "timestamp"

    /** 一段 Base64（含带 `data:` 头的写法）：字符都在字母表里、且解得开。 */
    const val BASE64 = "base64"
}

/** 工具在侧边栏里的分组。枚举顺序就是分组在侧边栏里的先后。 */
enum class DevToolGroup(val label: String) {
    FORMATTER("格式化"),
    CALCULATION("计算"),
    ENCODER("编解码"),
    TEXT("文本"),
    CONVERTER("转换"),
    GENERATOR("生成"),
    OTHER("其它"),
}

/** 工具的元信息：侧边栏与内容区标题都读它。 */
@Immutable
data class DevToolMetadata(
    /** 稳定标识，用于记住「选中的是哪一个工具」。 */
    val id: String,
    val name: String,
    /** 一句话说明，显示在内容区标题下方。 */
    val description: String,
    val group: DevToolGroup,
    val icon: ClipperIconKind = ClipperIconKind.BRACES,
)

/**
 * 主面板提供给工具的少量宿主能力。
 *
 * 刻意只开这几道口子：工具不该自己去碰系统剪贴板、原生文件对话框或界面全局状态——那些统一由
 * 面板负责，工具的职责被限制在「算 + 画」。
 *
 * 文件那一组只负责**拿路径**，不负责读写：读写走 `readTextFileOrNull` / `writeTextFile`
 * （kotlinx-io，留在 commonMain），于是工具侧一行就能读能写，也不必为文件系统开
 * `expect`/`actual`；而原生对话框与拖放载荷的解析只有宿主做得了。
 */
interface DevToolHost {
    /** 把结果写回系统剪贴板（面板会顺带提示一次）。 */
    fun copyToClipboard(text: String)

    /**
     * 把一张 **PNG 图片**写回系统剪贴板（面板会顺带提示一次）。
     *
     * 与 [copyToClipboard] 分开而不是让它收字节：文本与图片在剪贴板上是两种东西，用同一个口子
     * 就得在目标端猜「这次粘出来的该是字还是图」。条码工具生成的码图走这一路。
     *
     * 默认空实现：只提供文本复制的宿主（例如测试替身）不必为此改动。
     */
    fun copyImageToClipboard(png: ByteArray) {}

    /** 在面板底部状态栏闪一条临时提示（会自动消失，取代原先浮在内容上的气泡）。 */
    fun showStatus(message: String)

    /**
     * 向窗口底部的状态栏报告一行**常驻**说明（如「25 行 · 222 字符」）；`null` 表示清空。
     *
     * 与 [showStatus] 分工不同：那个是「刚刚发生了什么」的一次性提示，会自动消失；这个是
     * 「眼前这份内容是什么状态」，一直显示到下一次报告为止。字符数、行数这类跟着输入实时
     * 变动的数字走这里——它们不该占用编辑区上方那一行。
     *
     * 实现方应当在**副作用**里调用（`LaunchedEffect(计数)`），不要直接在组合中调用：那等于在
     * 组合期间写状态。
     */
    fun reportStatus(text: String?)

    /** 弹出「打开」对话框，返回用户挑中的路径；取消时返回 `null`。 */
    fun pickFileToOpen(): String?

    /**
     * 弹出「保存」对话框，返回用户挑中的路径；取消时返回 `null`。
     *
     * [suggestedName] 是预填的文件名，免得用户自己想一个。
     */
    fun pickFileToSave(suggestedName: String): String?

    /**
     * 从一次拖放里取出被拖进来的文件路径；拖的不是文件（例如一段选中的文字）时返回空表。
     *
     * 之所以由宿主解析：这要读平台自己的拖放载荷（桌面端是 AWT 的 `Transferable`），属于平台
     * 细节；工具只该拿到一串路径。
     */
    fun droppedFilePaths(event: DragAndDropEvent): List<String>

    /**
     * 从一次拖放里取出被拖进来的**图片**字节；拖的不是图片（是一条文件路径、一段文字）时返回 `null`。
     *
     * 与 [droppedFilePaths] 的分工：拖进来的是一张**图片文件**时走那一路（要的是那个文件本身，
     * 而不是它的图标）；从浏览器一类应用里拖过来的图片没有文件路径，只有粘贴板上的图片表示，
     * 走这一路。同属平台细节，工具只该拿到字节。
     */
    fun droppedImage(event: DragAndDropEvent): ByteArray? = null

    /**
     * 剪贴板里若放着**文件**（Finder / 资源管理器里复制的那种），返回它们的路径；没有文件时为空表。
     *
     * 只给「粘贴文件内容」用。系统对复制的文件只提供**文件名**这一种文本表示（实测见
     * `FinderCopyTest`），所以想知道用户想粘的是那个文件**本身**，只能去读粘贴板上的文件 URL——
     * 同 [droppedFilePaths]，属于平台细节，工具只该拿到路径。
     */
    fun clipboardFilePaths(): List<String> = emptyList()

    /**
     * 剪贴板里若放着**图片**，返回它的字节：优先原样搬一份界面画得出来的表示（PNG / JPEG…），
     * 只给得出像素时编成 PNG；没有图片时返回 `null`。
     *
     * 与 [clipboardFilePaths] 同一分工，两者都问时**先问文件**：在访达里复制一张图片，粘贴板上
     * 既有文件 URL、也有图片表示（图标），先看图片就会把图标当成用户要的内容。
     */
    fun clipboardImage(): ByteArray? = null
}

/**
 * 一个开发者工具插件。
 *
 * 实现方只需要回答三件事：自己叫什么（[metadata]）、能直接吃哪种数据（[acceptedDataTypes]）、
 * 界面长什么样（[Content]）。它不用管自己怎么被列出、怎么被选中，也不用管输入从
 * 哪里来——那些都是主面板（`DevToolsPanel`）的职责。
 *
 * 新增一个插件只有两步：实现本接口，然后到 `DevToolsRegistry.builtIn()` 里登记一行。
 */
interface DevTool {
    val metadata: DevToolMetadata

    /**
     * 该工具**直接**支持的数据类型名（取值见 [DataTypes]）。
     *
     * 命中时主面板会把它排在推荐位、并在带入剪贴板内容时默认选中它；留空表示它不消费外部输入
     * （例如纯生成器），仍然会出现在侧边栏里，只是不会参与自动选中。
     */
    val acceptedDataTypes: Set<String> get() = emptySet()

    /**
     * 渲染工具界面。
     *
     * @param input 主面板转交的剪贴板记录；`null` 表示这次没有输入（用户直接从侧边栏点进来，
     *   或历史为空）。工具应当把它「灌」进自己的输入区——惯用写法见 `JsonDevTool`：一个
     *   `remember` 的输入状态配一个 `LaunchedEffect(input)`。
     *
     *   拿到的是**整条 [ClipItem]**，不是一段正文。要文本时用 [devToolText]：它给出「这一条
     *   该怎么当文本看」（识别原文 → **文件内容** → 正文 → 附加表示提取的文字 → 标题）；直接
     *   读 [ClipItem.previewText] 对文件类条目只会拿到路径。其余按需自取：[ClipItem.image] 是
     *   图片字节，[ClipItem.contents] 是 HTML / RTF 这些表示的原始字节。其余字段（[ClipItem.pin]、
     *   [ClipItem.numberOfCopies]、时间戳…）是剪贴板历史的记账信息，工具不该用。
     */
    @Composable
    fun Content(input: ClipItem?, host: DevToolHost)
}
