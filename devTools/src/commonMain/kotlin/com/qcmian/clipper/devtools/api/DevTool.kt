package com.qcmian.clipper.devtools.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
}

/** 工具在侧边栏里的分组。枚举顺序就是分组在侧边栏里的先后。 */
enum class DevToolGroup(val label: String) {
    FORMATTER("格式化"),
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
 * 刻意只开这两个口子：工具不该自己去碰系统剪贴板或界面全局状态——写回与提示统一由面板负责，
 * 工具的职责被限制在「算 + 画」。
 */
interface DevToolHost {
    /** 把结果写回系统剪贴板（面板会顺带提示一次）。 */
    fun copyToClipboard(text: String)

    /** 在面板底部闪一条状态提示。 */
    fun showStatus(message: String)
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
