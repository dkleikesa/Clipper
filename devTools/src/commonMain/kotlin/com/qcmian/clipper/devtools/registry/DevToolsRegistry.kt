package com.qcmian.clipper.devtools.registry

import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.detect.BuiltInDataTypeDetectors
import com.qcmian.clipper.devtools.detect.DataTypeDetector
import com.qcmian.clipper.devtools.detect.detectTypes
import com.qcmian.clipper.devtools.tools.json.JsonDevTool
import com.qcmian.clipper.devtools.tools.math.MathDevTool
import com.qcmian.clipper.devtools.tools.timestamp.TimestampDevTool
import com.qcmian.clipper.devtools.tools.xml.XmlDevTool

/**
 * 插件表：所有可用的开发者工具，加上用于「自动判断数据类型」的探测器集合。
 *
 * 它把两件事接起来——探测器说「这段文本是 JSON」，工具说「我能吃 JSON」——并在打开面板时据此
 * 挑出应当默认选中的那一个。工具与探测器彼此不认识，各自增删都不会影响对方。
 *
 * 无状态、纯查询：可以在任意线程调用，也可以被界面直接读（见 `DevToolsPanel`）。由宿主编一份
 * 实例交给面板即可（见桌面端的组合根 `main.kt`；剪贴板那边不碰它）。
 */
class DevToolsRegistry(
    /** 全部工具，声明顺序即侧边栏里同组内的顺序。 */
    val tools: List<DevTool>,
    val detectors: List<DataTypeDetector> = BuiltInDataTypeDetectors,
) {
    private val toolsById: Map<String, DevTool> = tools.associateBy { it.metadata.id }

    fun tool(id: String): DevTool? = toolsById[id]

    /** 探测 [text] 命中的所有类型，最具体的在前；无内容时为空列表。 */
    fun detectTypes(text: String): List<String> = detectors.detectTypes(text)

    /**
     * 按与 [detectedTypes] 的匹配程度给工具排序：**能直接吃下最具体的那种类型的排在前面**，
     * 不匹配的保持声明顺序排在后面。
     *
     * 用「命中类型在探测结果里的下标」当排序键，而不是命中与否：探测结果本身已经从具体到宽泛
     * 排好了（`json` → `text`），因此同样命中时，吃 `json` 的工具优先于只吃 `text` 的工具。
     */
    fun rankedTools(detectedTypes: List<String>): List<DevTool> {
        if (detectedTypes.isEmpty()) return tools
        return tools.sortedBy { tool ->
            val index = detectedTypes.indexOfFirst { it in tool.acceptedDataTypes }
            if (index < 0) UNMATCHED else index
        }
    }

    companion object {
        /** 匹配不上任何探测类型时的排序键，稳定排在所有命中项之后。 */
        private const val UNMATCHED = Int.MAX_VALUE

        /**
         * 内置工具集合。
         *
         * **新增一个插件只需要在这里加一行**：工具自己声明名称、分组、图标与能吃的类型，
         * 面板与自动选中逻辑都会自动带上它。
         */
        fun builtIn(): DevToolsRegistry = DevToolsRegistry(
            tools = listOf(
                JsonDevTool,
                XmlDevTool,
                MathDevTool,
                TimestampDevTool,
            ),
        )
    }
}
