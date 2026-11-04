package com.qcmian.logviewer.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qcmian.logviewer.core.model.FileRef

/** 诊断日志出口（桌面接文件写入，绕过 stdout 缓冲；其余平台空操作） */
object DiagLog {
    var write: (String) -> Unit = {}
    fun log(s: String) = write(s + "\n")
}

/** 自动化验证：启动即打开的文件路径（桌面由 LOGVIEWER_AUTOOPEN 环境变量注入） */
object AutoOpenPath {
    var value: String? = null
}

/**
 * 平台能力接口：文件选择/读取/保存、剪贴板、偏好存储、文件监听。
 * 各平台在入口处实现并注入 LogViewerApp。
 */
interface LogViewerPlatform {
    /** 系统文件选择器（多选），取消时回调空列表 */
    fun pickFiles(onResult: (List<FileRef>) -> Unit)

    /** 打开日志源（kotlinx-io 顺序读）；默认实现覆盖内存字节与文件路径两类引用，特殊来源（如 Android SAF）由平台覆写 */
    fun openReader(ref: FileRef): OpenedLogSource? = defaultOpenLogSource(ref)

    /** 保存文本文件（保存过滤器集合 / 导出结果），取消回调 false */
    fun saveTextFile(defaultName: String, content: String, onDone: (Boolean) -> Unit)

    /** 写入剪贴板；html 非空时平台支持带格式粘贴 */
    fun setClipboard(text: String, html: String? = null)

    fun getClipboardText(): String?

    /** 简单键值存储（偏好/会话/最近文件） */
    fun putPref(key: String, value: String)
    fun getPref(key: String): String?
}

/** 全局键修饰状态（桌面多选：Ctrl/Cmd + 点击、Shift + 点击） */
object KeyboardState {
    var ctrl by androidx.compose.runtime.mutableStateOf(false)
    var shift by androidx.compose.runtime.mutableStateOf(false)
    var alt by androidx.compose.runtime.mutableStateOf(false)
}

/** 打开的日志源：kotlinx-io 顺序 Source + 文件大小 */
class OpenedLogSource(
    val source: kotlinx.io.Source,
    val sizeBytes: Long,
)

/** 通用打开逻辑：内存字节 → Buffer（common）；文件路径 → 平台 openPathSource */
fun defaultOpenLogSource(ref: FileRef): OpenedLogSource? = runCatching {
    when (val pd = ref.platformData) {
        is ByteArray -> OpenedLogSource(
            source = kotlinx.io.Buffer().apply { write(pd) },
            sizeBytes = pd.size.toLong(),
        )
        is String -> openPathSource(pd.removePrefix("file://"))
        else -> null
    }
}.getOrNull()

/**
 * 按文件路径打开（kotlinx-io files 包仅 JVM/Native 有实现；Web 无文件系统返回 null）。
 * 这是唯一的平台分叉点：每个平台 3~6 行。
 */
expect fun openPathSource(path: String): OpenedLogSource?

/** 当前毫秒时间戳（平台实现） */
expect fun nowMillis(): Long

/**
 * 桌面右键点击（F2-9 行操作菜单）。
 * 移动端无右键，actual 直接返回原 Modifier（长按菜单由 combinedClickable 承担）。
 */
expect fun androidx.compose.ui.Modifier.secondaryClick(onSecondary: (androidx.compose.ui.geometry.Offset) -> Unit): androidx.compose.ui.Modifier

/** 上下调整光标（拖动条 hover 提示；桌面 AWT Cursor，其他平台无操作） */
expect fun androidx.compose.ui.Modifier.nsResizeCursor(): androidx.compose.ui.Modifier

/** 拖动多选（桌面鼠标）：主键按下拖过 slop 后按下行→当前行区间选中；移动端暂不启用 */
expect fun androidx.compose.ui.Modifier.dragSelect(
    state: com.qcmian.logviewer.state.ViewerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
): androidx.compose.ui.Modifier

/** 跨语言入口桥：Swift / JS 侧把外部打开的文件路径送进来（调用方均在主线程） */
object SharedBus {
    private val pending = ArrayDeque<String>()
    var listener: (() -> Unit)? = null

    fun offer(path: String) {
        pending.addLast(path)
        listener?.invoke()
    }

    fun consumeAll(): List<String> {
        val out = pending.toList()
        pending.clear()
        return out
    }
}
