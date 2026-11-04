package com.qcmian.logviewer.core.model

import kotlinx.serialization.Serializable

/* ==================== 过滤器模型（3.3.2） ==================== */

/** 过滤器类型：包含 / 排除 */
enum class FilterType(val zh: String) {
    INCLUDE("包含"),
    EXCLUDE("排除");
}

/** 匹配方式：文本包含 / 正则 / 按标记 */
enum class MatchMode(val zh: String) {
    STRING("文本包含"),
    REGEX("正则表达式"),
    MARKER("按标记");
}

/** 两种显示模式：显示命中=仅显示命中行（默认，排前）；全部显示=显示全部行且命中行高亮 */
enum class DisplayMode(val zh: String) {
    SHOW_ONLY_FILTERED("显示命中"),
    SHOW_ALL("全部显示");
}

@Serializable
data class FilterConfig(
    val id: Long = nextId(),
    val name: String = "",
    val pattern: String = "",
    val type: FilterType = FilterType.INCLUDE,
    val matchMode: MatchMode = MatchMode.STRING,
    val caseSensitive: Boolean = false,
    /** 前景色 ARGB，null 表示不覆盖 */
    val foreground: Long? = null,
    /** 背景色 ARGB，null 表示不覆盖 */
    val background: Long? = null,
    val enabled: Boolean = true,
    val description: String = "",
    /** MatchMode.MARKER 时使用的标记索引（0..7） */
    val markerIndex: Int = 0,
) {
    companion object {
        private var counter: Long = kotlin.random.Random.nextLong(1L shl 40)
        fun nextId(): Long {
            counter += 1
            return counter
        }
    }
}

/** 过滤器集合文件（F3-9 跨会话保存/加载） */
@Serializable
data class FilterSet(
    val name: String = "未命名集合",
    val showOnlyFiltered: Boolean = false,
    val filters: List<FilterConfig> = emptyList(),
)

/* ==================== 标记系统（3.4） ==================== */

object MarkerPalette {
    /** 8 种标记颜色（对齐 TAT） */
    val colors = listOf(
        0xFFFB7185UL, 0xFFF5A524UL, 0xFFFACC15UL, 0xFFA3E635UL,
        0xFF22D3EEUL, 0xFF5B8CFFUL, 0xFFA78BFAUL, 0xFFF472B6UL,
    )
    val names = listOf(
        "标记 1（红）", "标记 2（橙）", "标记 3（黄）", "标记 4（绿）",
        "标记 5（青）", "标记 6（蓝）", "标记 7（紫）", "标记 8（粉）",
    )
    fun colorOf(index: Int): Long = colors[index.coerceIn(0, 7)].toLong()
}

/* ==================== 预设（F3-8） ==================== */

object FilterPresets {
    fun levelPresets(): List<FilterConfig> = listOf(
        FilterConfig(
            name = "FATAL", pattern = "FATAL EXCEPTION|FATAL",
            matchMode = MatchMode.REGEX, foreground = 0xFFFFFFFFUL.toLong(),
            background = 0xFFB91C1CUL.toLong(), description = "致命错误/崩溃",
        ),
        FilterConfig(
            name = "ERROR", pattern = "\\b(ERROR|Error)\\b",
            matchMode = MatchMode.REGEX, foreground = 0xFF7F1D1DUL.toLong(),
            background = 0xFFFCA5A5UL.toLong(), description = "错误日志",
        ),
        FilterConfig(
            name = "WARN", pattern = "\\b(WARN|Warning)\\b",
            matchMode = MatchMode.REGEX, foreground = 0xFF78350FUL.toLong(),
            background = 0xFFFDE68AUL.toLong(), description = "警告日志",
        ),
        FilterConfig(
            name = "INFO", pattern = "\\bINFO\\b",
            matchMode = MatchMode.REGEX, foreground = 0xFF1E3A8AUL.toLong(),
            background = 0xFFBFDBFEUL.toLong(), description = "信息日志",
        ),
        FilterConfig(
            name = "DEBUG", pattern = "\\bDEBUG\\b",
            matchMode = MatchMode.REGEX, foreground = 0xFF374151UL.toLong(),
            background = 0xFFE5E7EBUL.toLong(), description = "调试日志",
        ),
    )

    fun androidCrashSet(): List<FilterConfig> = listOf(
        FilterConfig(
            name = "Crash", pattern = "FATAL EXCEPTION",
            foreground = 0xFFFFFFFFUL.toLong(), background = 0xFFB91C1CUL.toLong(),
        ),
        FilterConfig(
            name = "ANR", pattern = "ANR in |Input dispatching timed out",
            matchMode = MatchMode.REGEX, foreground = 0xFF4C1D95UL.toLong(),
            background = 0xFFDDD6FEUL.toLong(),
        ),
        FilterConfig(
            name = "Network", pattern = "Network|okhttp|retrofit",
            matchMode = MatchMode.REGEX, foreground = 0xFF172554UL.toLong(),
            background = 0xFFBFDBFEUL.toLong(),
        ),
    )
}

/* ==================== 快捷键（F9-8 自定义） ==================== */

/* ==================== 会话与最近文件（F9-5） ==================== */

@Serializable
data class FindSnapshot(
    val query: String = "",
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
)

@Serializable
data class SessionData(
    val fileName: String,
    val fileUri: String,
    val encoding: String = "UTF-8",
    val filters: List<FilterConfig> = emptyList(),
    val displayMode: DisplayMode = DisplayMode.SHOW_ONLY_FILTERED,
    val markers: Map<Int, Set<Int>> = emptyMap(),
    val scrollLine: Int = 0,
    val currentLine: Int = -1,
    val find: FindSnapshot = FindSnapshot(),
    val updatedAt: Long = 0L,
)

@Serializable
data class RecentEntry(
    val fileName: String,
    val fileUri: String,
    val lineCount: Int,
    val sizeBytes: Long,
    val lastOpenedAt: Long,
)

/* ==================== 其他 ==================== */

/** 单行解析后的展示信息 */
data class DisplayLine(
    val index: Int,
    val isMatch: Boolean,
)

/** 平台无关的文件引用 */
class FileRef(
    val name: String,
    val uri: String,
    val platformData: Any? = null,
) {
    override fun toString(): String = name
}
